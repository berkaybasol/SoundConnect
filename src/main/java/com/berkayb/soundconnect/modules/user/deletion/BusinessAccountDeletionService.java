package com.berkayb.soundconnect.modules.user.deletion;

import com.berkayb.soundconnect.modules.event.management.EventManagementRepository;
import com.berkayb.soundconnect.modules.event.service.EventService;
import com.berkayb.soundconnect.modules.event.support.EventScheduleClock;
import com.berkayb.soundconnect.modules.media.enums.MediaOwnerType;
import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.studio.room.dto.request.StudioRoomArchiveRequest;
import com.berkayb.soundconnect.modules.studio.room.service.StudioRoomService;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BusinessAccountDeletionService {
    private final JdbcTemplate jdbc;
    private final EntityManager entityManager;
    private final StudioRoomService rooms;
    private final EventManagementRepository schedules;
    private final EventScheduleClock clock;
    private final EventService events;
    private final MediaAssetService media;

    /** Runs under the same user lock as application approval and the final identity erasure. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void purgeAccount(UUID userId) {
        jdbc.update("""
                delete from tbl_application_mail_intent where
                  (purpose='VENUE_APPLICATION_ADMIN' and application_id in
                    (select id from tbl_venue_applications where user_id=?)) or
                  (purpose in ('STUDIO_APPLICATION_ADMIN','STUDIO_APPLICATION_DECISION') and application_id in
                    (select id from tbl_studio_applications where applicant_id=?))
                """, userId, userId);
        for (UUID venueId : jdbc.queryForList(
                "select id from tbl_venues where owner_id=? order by id for update", UUID.class, userId)) {
            purgeVenue(userId, venueId);
        }
        for (UUID studioId : jdbc.queryForList(
                "select id from tbl_studio_profile where user_id=? and account_erased_at is null for update", UUID.class, userId)) {
            purgeStudio(userId, studioId);
        }
    }

    private void purgeVenue(UUID userId, UUID venueId) {
        // Recurrence generation locks the band before the plan. Finish any in-flight
        // generation before enumerating the venue's remaining dated occurrences.
        jdbc.queryForList("""
                select id from tbl_band where id in
                  (select band_id from event_plans where venue_id=?) order by id for update
                """, UUID.class, venueId);
        jdbc.update("""
                update event_plans set status='STOPPED', cancel_future=true, show_on_profile=false,
                    poster_image=null, version=version+1 where venue_id=?
                """, venueId);
        for (UUID eventId : schedules.upcomingIds(venueId, clock.localNow())) {
            events.deleteEventById(userId, eventId);
        }
        entityManager.flush();

        // The event belongs to its musician organizer even when this venue accepted
        // it. Clear both accepted links and pending/rejected venue name snapshots.
        jdbc.update("""
                update tbl_event set venue_id=null, venue_name_snapshot=null,
                    venue_calendar_approved=false, venue_approval_status='NOT_REQUIRED'
                where event_origin='MUSICIAN' and (venue_id=? or id in
                    (select event_id from event_venue_requests where venue_id=?))
                """, venueId, venueId);
        jdbc.update("delete from event_venue_requests where venue_id=?", venueId);
        jdbc.update("delete from artist_venue_connection_requests where venue_id=?", venueId);
        jdbc.update("delete from musician_profile_venues where venue_id=?", venueId);
        jdbc.update("delete from venue_active_bands where venue_id=?", venueId);
        jdbc.update("update tbl_table_group set venue_id=null, venue_name=null where venue_id=?", venueId);
        jdbc.update("update tbl_event set poster_image=null where event_origin='VENUE' and venue_id=?", venueId);
        for (UUID profileId : jdbc.queryForList(
                "select id from tbl_venue_profile where venue_id=? for update", UUID.class, venueId)) {
            jdbc.update("update tbl_venue_profile set profile_picture_media_id=null where id=?", profileId);
            purgePublications(userId, profileId, MediaOwnerType.VENUE_PROFILE, "VENUE");
            jdbc.update("delete from tbl_venue_profile where id=?", profileId);
        }
        purgePublications(userId, venueId, MediaOwnerType.VENUE, "VENUE");
        // Past venue-origin events and stopped recurrence history require a venue FK.
        // Drop its public eligibility and identifying fields after releasing media.
        jdbc.update("""
                update tbl_venues set name='Kapanmış mekân', address='', phone=null, website=null,
                    description=null, music_start_time=null, owner_id=null, status='PENDING', account_erased_at=now() where id=?
                """, venueId);
    }

    private void purgeStudio(UUID userId, UUID studioId) {
        var activeRooms = jdbc.query("""
                select id, version from tbl_studio_room where studio_profile_id=? and archived_at is null
                order by id for update
                """, (row, index) -> new RoomVersion(row.getObject("id", UUID.class), row.getLong("version")), studioId);
        for (RoomVersion room : activeRooms) {
            // Also cancels pending/confirmed future bookings and enqueues a durable
            // customer notification using the existing reservation target contract.
            rooms.archive(userId, room.id(), new StudioRoomArchiveRequest(room.version()));
        }
        jdbc.update("""
                delete from tbl_studio_room_photo where room_id in
                    (select id from tbl_studio_room where studio_profile_id=?)
                """, studioId);
        jdbc.update("""
                delete from tbl_studio_room_feature where room_id in
                    (select id from tbl_studio_room where studio_profile_id=?)
                """, studioId);
        jdbc.update("""
                update tbl_studio_room set short_description=null where studio_profile_id=?
                """, studioId);
        jdbc.update("""
                update tbl_studio_room_reservation set contact_phone_snapshot=null where room_id in
                    (select id from tbl_studio_room where studio_profile_id=?)
                """, studioId);
        jdbc.update("""
                delete from tbl_studio_equipment_availability_change where equipment_id in
                    (select id from tbl_studio_equipment where studio_profile_id=?)
                """, studioId);
        for (String table : java.util.List.of("tbl_studio_equipment_day", "tbl_studio_equipment_feature", "tbl_studio_equipment_photo")) {
            jdbc.update("delete from " + table + " where equipment_id in (select id from tbl_studio_equipment where studio_profile_id=?)", studioId);
        }
        jdbc.update("delete from tbl_studio_equipment where studio_profile_id=?", studioId);
        jdbc.update("""
                delete from tbl_backline_category_request_child where request_id in
                    (select id from tbl_backline_category_request where studio_profile_id=?)
                """, studioId);
        jdbc.update("delete from tbl_backline_category_request where studio_profile_id=?", studioId);
        jdbc.update("delete from studio_facilities where studio_profile_id=?", studioId);
        jdbc.update("delete from studio_profile_spotify_tracks where studio_profile_id=?", studioId);
        // Reservation history and notification targets use room/studio keys and
        // time zone. Keep that chain while dropping all public profile content.
        jdbc.update("""
                update tbl_studio_profile set name='Kapanmış stüdyo', description=null, address=null, phone=null,
                    website=null, youtube_url=null, instagram_url=null, city_id=null, district_id=null,
                    neighborhood_id=null, profile_picture_media_id=null, spotify_tracks='[]'::jsonb,
                    version=version+1, account_erased_at=now() where id=?
                """, studioId);
        purgePublications(userId, studioId, MediaOwnerType.STUDIO_PROFILE, "STUDIO");
    }

    private void purgePublications(UUID userId, UUID ownerId, MediaOwnerType ownerType, String profileType) {
        jdbc.update("delete from tbl_profile_media where profile_type=? and profile_id=?", profileType, ownerId);
        jdbc.update("delete from tbl_tracks where owner_type=? and owner_id=?", ownerType.name(), ownerId);
        for (UUID assetId : jdbc.queryForList("""
                select id from tbl_media_asset where owner_type=? and owner_id=? order by id
                """, UUID.class, ownerType.name(), ownerId)) {
            media.delete(assetId, userId, ownerType, ownerId);
        }
        entityManager.flush();
        jdbc.update("update tbl_media_asset set title=null, description=null where owner_type=? and owner_id=?", ownerType.name(), ownerId);
    }

    private record RoomVersion(UUID id, long version) {}
}

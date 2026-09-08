package com.berkayb.soundconnect.modules.event.audience;

import com.berkayb.soundconnect.modules.event.discovery.EventDiscoveryRow;
import com.berkayb.soundconnect.modules.event.publication.EventProfilePublicationRepository;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.time.*;
import java.util.*;

public interface EventAudienceRepository extends JpaRepository<EventAudienceIntent, EventAudienceIntent.Id> {
    @Query(value = "select id from tbl_user where id=:userId and status='ACTIVE' and email_verified for update", nativeQuery = true)
    Optional<UUID> lockActor(@Param("userId") UUID userId);
    @Query(value = "select id from tbl_user where id=:userId and status='ACTIVE' and email_verified for share", nativeQuery = true)
    Optional<UUID> lockActiveAccountForRead(@Param("userId") UUID userId);
    @Query(value = "select user_id from \"tbl_listener-profile\" where id=:profileId", nativeQuery = true)
    Optional<UUID> listenerUserId(@Param("profileId") UUID profileId);
    /** Fresh lock-protected scalars: an OSIV-managed profile may contain preflight-era visibility. */
    @Query(value = """
            select id as "profileId", visibility_mode as "mode", visibility_choice_completed as "choiceCompleted"
            from "tbl_listener-profile" where user_id=:userId for share
            """, nativeQuery = true)
    Optional<ListenerVisibility> listenerVisibility(@Param("userId") UUID userId);
    interface ListenerVisibility {
        UUID getProfileId();
        String getMode();
        boolean getChoiceCompleted();
    }
    // Audience writes never mutate the event. Share the read fence across actors,
    // while still excluding event deletion, schedule changes and consent edits.
    @Query(value = "select id from tbl_event where id=:eventId for share", nativeQuery = true)
    Optional<UUID> lockEvent(@Param("eventId") UUID eventId);

    String LIVE = """
            from tbl_event event join tbl_venues venue on venue.id=event.venue_id
            join tbl_user venue_owner on venue_owner.id=venue.owner_id
            join tbl_city city on city.id=venue.city_id
            join tbl_district district on district.id=venue.district_id
            join tbl_neighborhood neighborhood on neighborhood.id=venue.neighborhood_id
            join tbl_event_audience_intent intent on intent.event_id=event.id
            where event.event_origin='VENUE' and event.venue_calendar_approved
                and event.event_date is not null and event.start_time is not null
                and venue.status='APPROVED' and venue_owner.status='ACTIVE' and venue_owner.email_verified
                and district.city_id=city.id and neighborhood.district_id=district.id
                and intent.user_id=:userId and intent.intent<>'NONE'
            """;
    String PERIOD = " and (:period='ALL' or (:period='PAST' and " + EventProfilePublicationRepository.SQL_EFFECTIVE_END
            + " <= " + EventProfilePublicationRepository.SQL_NOW + ") or (:period='UPCOMING' and "
            + EventProfilePublicationRepository.SQL_EFFECTIVE_END + " > " + EventProfilePublicationRepository.SQL_NOW + "))";
    String ORDER = " order by case when :period='UPCOMING' then event.event_date end asc,"
            + " case when :period='UPCOMING' then " + EventProfilePublicationRepository.SQL_START_SECONDS + " end asc,"
            + " case when :period<>'UPCOMING' then event.event_date end desc,"
            + " case when :period<>'UPCOMING' then " + EventProfilePublicationRepository.SQL_START_SECONDS + " end desc,event.id";

    @Query(value = "select event.id " + LIVE + PERIOD + ORDER,
            countQuery = "select count(*) " + LIVE + PERIOD, nativeQuery = true)
    Page<UUID> privateIds(@Param("userId") UUID userId, @Param("period") String period,
                         @Param("nowDate") LocalDate nowDate, @Param("nowTime") LocalTime nowTime,
                         @Param("storageMidnight") LocalTime storageMidnight, Pageable pageable);
    @Query(value = "select event.id " + LIVE + " and intent.published_on_profile" + PERIOD + " order by intent.published_at desc,event.id",
            countQuery = "select count(*) " + LIVE + " and intent.published_on_profile" + PERIOD, nativeQuery = true)
    Page<UUID> publicIds(@Param("userId") UUID userId, @Param("period") String period,
                        @Param("nowDate") LocalDate nowDate, @Param("nowTime") LocalTime nowTime,
                        @Param("storageMidnight") LocalTime storageMidnight, Pageable pageable);
    @Query("select intent from EventAudienceIntent intent where intent.id.userId=:userId and intent.id.eventId in :ids")
    List<EventAudienceIntent> pageStates(@Param("userId") UUID userId, @Param("ids") Collection<UUID> ids);

    /** Same scalar card projection and current target eligibility as public discovery. */
    @Query("""
            select new com.berkayb.soundconnect.modules.event.discovery.EventDiscoveryRow(
                event.id,event.title,event.posterImage,musician.id,musicianUser.username,musician.stageName,
                band.id,band.name,event.manualPerformerName,venue.id,venue.name,city.name,district.name,neighborhood.name,
                event.eventDate,event.startTime,event.endTime,event.description)
            from Event event join event.venue venue join venue.owner owner
            join venue.city city join venue.district district join venue.neighborhood neighborhood
            left join event.musicianProfile musician
                on event.performerApprovalStatus=com.berkayb.soundconnect.modules.event.enums.EventPerformerApprovalStatus.APPROVED
            left join musician.user musicianUser
            left join event.band band
                on event.performerApprovalStatus=com.berkayb.soundconnect.modules.event.enums.EventPerformerApprovalStatus.APPROVED
            where event.id in :ids and event.eventOrigin=com.berkayb.soundconnect.modules.event.enums.EventOrigin.VENUE
                and event.venueCalendarApproved=true
                and event.eventDate is not null and event.startTime is not null
                and venue.status=com.berkayb.soundconnect.modules.venue.enums.VenueStatus.APPROVED
                and owner.status=com.berkayb.soundconnect.modules.user.enums.UserStatus.ACTIVE and owner.emailVerified=true
                and district.city.id=city.id and neighborhood.district.id=district.id
            """)
    List<EventDiscoveryRow> eventCards(@Param("ids") Collection<UUID> ids);
}

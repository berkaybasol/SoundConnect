package com.berkayb.soundconnect.modules.user.deletion;

import com.berkayb.soundconnect.modules.media.enums.MediaOwnerType;
import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.repository.MusicianProfileRepository;
import com.berkayb.soundconnect.modules.setlistcreator.repository.SetlistRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MusicianAccountDataCleaner {
    private final MusicianProfileRepository profiles;
    private final SetlistRepository setlists;
    private final JdbcTemplate jdbc;
    private final MediaAssetService media;
    private final EntityManager entities;

    @Transactional(propagation = Propagation.MANDATORY)
    public void erase(UUID userId) {
        var profile = profiles.findByUserIdForUpdate(userId).orElse(null);
        if (profile == null) return;
        UUID id = profile.getId();
        detachFromCalendars(userId, id);
        jdbc.update("delete from artist_venue_connection_requests where musician_profile_id=?", id);
        jdbc.update("delete from tbl_profile_media where profile_type='MUSICIAN' and profile_id=?", id);
        // A historical setlist with a band owner stays with that band.
        jdbc.update("update tbl_setlist set musician_profile_id=null where musician_profile_id=? and band_id is not null", id);
        setlists.deleteAll(setlists.findAllByMusicianProfile_Id(id));
        jdbc.update("delete from tbl_tracks where (owner_type='MUSICIAN_PROFILE' and owner_id=?) or (owner_type='USER' and owner_id=?)", id, userId);
        profile.setProfilePictureMediaId(null);
        entities.flush();
        for (UUID asset : jdbc.queryForList("select id from tbl_media_asset where owner_type='MUSICIAN_PROFILE' and owner_id=? order by id", UUID.class, id)) {
            media.delete(asset, userId, MediaOwnerType.MUSICIAN_PROFILE, id);
        }
        entities.flush();
        jdbc.update("update tbl_media_asset set title=null,description=null where owner_type='MUSICIAN_PROFILE' and owner_id=?", id);
        jdbc.update("delete from tbl_musician_feed_feedback where viewer_user_id=?", userId);
        jdbc.update("delete from tbl_musician_feed_telemetry_event where viewer_user_id=?", userId);
        jdbc.update("delete from tbl_musician_feed_page_replay where viewer_user_id=?", userId);
        jdbc.update("delete from tbl_musician_feed_delivery where viewer_user_id=?", userId);
        jdbc.update("delete from tbl_musician_feed_preferences where musician_profile_id=?", id);
        jdbc.update("delete from tbl_musician_calendar_settings where musician_profile_id=?", id);
        profile.getUser().setMusicianProfile(null);
        profiles.delete(profile);
        entities.flush();
    }

    private void detachFromCalendars(UUID userId, UUID profileId) {
        // Recurrence writers acquire the plan before the event. Preserve the venue's series.
        jdbc.update("""
                update event_plans set musician_profile_id=null, performer_name_snapshot=null,
                    manual_performer_name=null, consent_status='NOT_REQUIRED', show_on_profile=false,
                    accepted_publication=null, decided_by_user_id=null, version=version+1, consent_revision=consent_revision+1
                where musician_profile_id=?
                """, profileId);
        jdbc.update("update event_plan_occurrences set override_musician_profile_id=null where override_musician_profile_id=?", profileId);
        jdbc.update("""
                update tbl_event set musician_profile_id=null,manual_performer_name=null,
                    performer_approval_status='NOT_REQUIRED',profile_calendar_approved=false,
                    profile_publication_version=profile_publication_version+1
                where event_origin='VENUE' and (musician_profile_id=? or id in
                  (select event_id from event_performer_requests where musician_profile_id=?))
                """, profileId, profileId);
        jdbc.update("delete from event_performer_requests where musician_profile_id=?", profileId);
        jdbc.update("delete from event_member_publications where musician_profile_id=?", profileId);
        jdbc.update("delete from tbl_event where event_origin='MUSICIAN' and musician_profile_id=? and organizer_user_id=?", profileId, userId);
        jdbc.update("delete from musician_event_creations where organizer_user_id=?", userId);
    }
}

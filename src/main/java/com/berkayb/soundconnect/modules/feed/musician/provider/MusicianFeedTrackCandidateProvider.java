package com.berkayb.soundconnect.modules.feed.musician.provider;

import com.berkayb.soundconnect.modules.feed.musician.api.*;
import com.berkayb.soundconnect.modules.feed.musician.candidate.*;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

@Component
public class MusicianFeedTrackCandidateProvider implements MusicianFeedCandidateProvider {
    private static final String SQL = """
            with publications as (
                select track.id as track_id, track.media_asset_id, track.title, track.duration_seconds, track.bpm,
                       track.owner_type, track.owner_id, track.created_at,
                       coalesce(musician.user_id, listener.user_id, studio.user_id, venue.owner_id, band_actor.user_id) as author_user_id,
                       case when track.owner_type='VENUE_PROFILE' then venue.id
                            else track.owner_id end as author_profile_id,
                       case track.owner_type
                           when 'MUSICIAN_PROFILE' then 'MUSICIAN'
                           when 'LISTENER_PROFILE' then 'LISTENER'
                           when 'STUDIO_PROFILE' then 'STUDIO'
                           when 'VENUE_PROFILE' then 'VENUE'
                           when 'BAND' then 'BAND' end as author_profile_type,
                       coalesce(band.name, venue.name, musician.stage_name, musician.name,
                                studio.name, listener.name) as profile_display_name,
                       coalesce(band.profile_picture_media_id, venue_profile.profile_picture_media_id,
                                musician.profile_picture_media_id, studio.profile_picture_media_id,
                                listener.profile_picture_media_id) as avatar_media_id,
                       listener.visibility_mode as listener_visibility,
                       listener.visibility_choice_completed as listener_choice,
                       venue.status as venue_status,
                       exists(select 1 from tbl_band_member own_member
                              where track.owner_type='BAND' and own_member.band_id=track.owner_id
                                and own_member.user_id=:viewerId and own_member.status='ACTIVE') as owned_band
                from tbl_tracks track
                join tbl_media_asset media on media.id=track.media_asset_id
                    and media.owner_type=track.owner_type and media.owner_id=track.owner_id
                left join tbl_musician_profile musician
                    on track.owner_type='MUSICIAN_PROFILE' and musician.id=track.owner_id
                left join \"tbl_listener-profile\" listener
                    on track.owner_type='LISTENER_PROFILE' and listener.id=track.owner_id
                left join tbl_studio_profile studio
                    on track.owner_type='STUDIO_PROFILE' and studio.id=track.owner_id
                left join tbl_venue_profile venue_profile
                    on track.owner_type='VENUE_PROFILE' and venue_profile.id=track.owner_id
                left join tbl_venues venue on venue.id=venue_profile.venue_id
                left join tbl_band band on track.owner_type='BAND' and band.id=track.owner_id
                left join lateral (
                    select member.user_id from tbl_band_member member
                    join tbl_user member_account on member_account.id=member.user_id
                        and member_account.status='ACTIVE' and member_account.email_verified
                        and member_account.erased_at is null
                    where member.band_id=band.id and member.status='ACTIVE'
                    order by case when member.band_role='FOUNDER' then 0 else 1 end, member.id limit 1
                ) band_actor on true
                where media.status='READY' and media.visibility='PUBLIC'
                  and coalesce(media.playback_url, media.source_url) is not null
                  and track.owner_type in ('MUSICIAN_PROFILE','LISTENER_PROFILE','STUDIO_PROFILE','VENUE_PROFILE','BAND')
                  and track.created_at <= :anchor
            )
            select publication.*, media.playback_url, media.source_url,
                   account.user_name as author_username,
                   coalesce(publication.profile_display_name, account.user_name) as author_display_name,
                   coalesce(avatar.playback_url, avatar.source_url, avatar.thumbnail_url, account.profile_picture) as author_avatar_url,
                   (case when publication.owner_type='BAND' then band_follow.id is not null
                         else following.id is not null end) as followed_by_viewer,
                   ((publication.author_user_id=:viewerId) or publication.owned_band) as owned_by_viewer,
                   (select count(*) from tbl_like likes where likes.target_type='MEDIA' and likes.target_id=publication.media_asset_id) as like_count,
                   (select count(*) from tbl_comment comments where comments.target_type='MEDIA'
                       and comments.target_id=publication.media_asset_id and not comments.is_deleted) as comment_count,
                   exists(select 1 from tbl_like mine where mine.user_id=:viewerId and mine.target_type='MEDIA'
                       and mine.target_id=publication.media_asset_id) as liked_by_me
            from publications publication
            join tbl_user account on account.id=publication.author_user_id
            join tbl_media_asset media on media.id=publication.media_asset_id
            left join tbl_media_asset avatar on avatar.id=publication.avatar_media_id
                and avatar.status='READY' and avatar.visibility='PUBLIC'
            left join tbl_follow following on publication.owner_type<>'BAND'
                and following.follower_id=:viewerId
                and following.following_id=publication.author_user_id
            left join tbl_band_follow band_follow on publication.owner_type='BAND'
                and band_follow.follower_id=:viewerId and band_follow.band_id=publication.owner_id
            where account.status='ACTIVE' and account.email_verified and account.erased_at is null
              and (publication.owner_type<>'LISTENER_PROFILE'
                   or (publication.listener_visibility='STANDARD' and publication.listener_choice
                       and exists(select 1 from user_roles membership join tbl_role role on role.id=membership.role_id
                           where membership.user_id=account.id and role.name='ROLE_LISTENER')
                       and not exists(select 1 from user_roles membership join tbl_role role on role.id=membership.role_id
                           where membership.user_id=account.id and role.name in ('ROLE_ADMIN','ROLE_OWNER',
                               'ROLE_MUSICIAN','ROLE_VENUE','ROLE_STUDIO','ROLE_ORGANIZER','ROLE_PRODUCER'))
                       and not exists(select 1 from tbl_musician_profile profile where profile.user_id=account.id)
                       and not exists(select 1 from tbl_studio_profile profile where profile.user_id=account.id)
                       and not exists(select 1 from tbl_organizer_profile profile where profile.user_id=account.id)
                       and not exists(select 1 from tbl_producer_profile profile where profile.user_id=account.id)
                       and not exists(select 1 from tbl_venues venue where venue.owner_id=account.id)))
              and (publication.owner_type<>'VENUE_PROFILE' or publication.venue_status='APPROVED')
              and publication.author_user_id<>:viewerId and not publication.owned_band
              and not exists(select 1 from tbl_musician_feed_feedback feedback
                  where feedback.viewer_user_id=:viewerId and (
                    (feedback.action in ('HIDE','REPORT')
                     and feedback.item_id='TRACK:' || publication.track_id::text)
                    or (feedback.action='MUTE_AUTHOR'
                        and feedback.author_profile_type=publication.author_profile_type
                        and feedback.author_profile_id=publication.author_profile_id)))
              and not exists(select 1 from tbl_musician_feed_delivery delivered
                  where delivered.viewer_user_id=:viewerId and delivered.feed_session_id=:feedSessionId
                    and (delivered.item_id='TRACK:' || publication.track_id::text
                      or (delivered.item_type<>'ACTIVITY_COMMENT' and delivered.target_type='MEDIA'
                          and delivered.target_id=publication.media_asset_id)))
              and (case when publication.owner_type='BAND' then band_follow.id is not null
                        else following.id is not null end)=:followingPool
            order by publication.created_at desc, publication.track_id desc
            limit :limit
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public MusicianFeedTrackCandidateProvider(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override public String providerId() { return "track-publications"; }
    @Override public Set<MusicianFeedItemType> supportedTypes() { return Set.of(MusicianFeedItemType.TRACK); }

    @Override
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW, timeout = 5)
    public List<MusicianFeedCandidate> findCandidates(MusicianFeedCandidateRequest request) {
        if (!request.supportedTypes().contains(MusicianFeedItemType.TRACK)) return List.of();
        int discoveryLimit = Math.max(1, request.limit() / 4);
        int followingLimit = Math.max(0, request.limit() - discoveryLimit);
        var parameters = new MapSqlParameterSource()
                .addValue("viewerId", request.viewerUserId())
                .addValue("feedSessionId", request.feedSessionId())
                .addValue("anchor", MusicianFeedJdbcSupport.timestamp(request.anchor()));
        List<MusicianFeedCandidate> result = new ArrayList<>(request.limit());
        if (followingLimit > 0) result.addAll(jdbc.query(SQL, parameters
                .addValue("followingPool", true).addValue("limit", followingLimit), this::candidate));
        result.addAll(jdbc.query(SQL, parameters.addValue("followingPool", false)
                .addValue("limit", discoveryLimit), this::candidate));
        return List.copyOf(result);
    }

    private MusicianFeedCandidate candidate(ResultSet row, int index) throws SQLException {
        UUID trackId = MusicianFeedJdbcSupport.uuid(row, "track_id");
        UUID mediaId = MusicianFeedJdbcSupport.uuid(row, "media_asset_id");
        var author = MusicianFeedJdbcSupport.author(row);
        String playback = row.getString("playback_url");
        if (playback == null || playback.isBlank()) playback = row.getString("source_url");
        var payload = new MusicianFeedPayloads.Track(trackId, mediaId, row.getString("title"), playback,
                (Integer) row.getObject("duration_seconds"), (Integer) row.getObject("bpm"));
        return new MusicianFeedCandidate("TRACK:" + trackId, MusicianFeedItemType.TRACK, 1,
                MusicianFeedJdbcSupport.instant(row, "created_at"),
                MusicianFeedJdbcSupport.publicationReason(author, MusicianFeedReasonCode.DISCOVERY),
                author, new MusicianFeedItemResponse.Target("MEDIA", mediaId),
                MusicianFeedJdbcSupport.engagement(row, "MEDIA", mediaId), null,
                MusicianFeedJdbcSupport.standardFeedback(), payload,
                author.followedByViewer() ? 1_000_000L : 300_000L,
                0, author.followedByViewer() ? MusicianFeedLane.FOLLOWING
                        : MusicianFeedLane.GENERAL_DISCOVERY, row.getBoolean("owned_by_viewer"));
    }
}

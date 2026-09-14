package com.berkayb.soundconnect.modules.feed.musician.provider;

import static com.berkayb.soundconnect.modules.feed.musician.moderation.MusicianFeedRestrictionSql.*;

import com.berkayb.soundconnect.modules.feed.musician.api.*;
import com.berkayb.soundconnect.modules.feed.musician.candidate.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

@Component
public class MusicianFeedTrackCandidateProvider implements MusicianFeedCandidateProvider {
    private static final String ELIGIBLE_TRACK_SQL = """
            with publications as (
                select track.id as track_id, track.media_asset_id, track.title, track.duration_seconds, track.bpm,
                       track.owner_type, track.owner_id, track.created_at,
                       publisher.*
                from tbl_tracks track
                join tbl_media_asset media on media.id=track.media_asset_id
                    and media.owner_type=track.owner_type and media.owner_id=track.owner_id
                -- Exactly one owner branch can match. Correlation avoids joining
                -- every track to unrelated publisher tables before the page LIMIT.
                join lateral (
                    select musician.user_id as author_user_id, musician.id as author_profile_id,
                           'MUSICIAN'::text as author_profile_type, null::text as profile_display_name,
                           musician.profile_picture_media_id as avatar_media_id,
                           null::text as listener_visibility, null::boolean as listener_choice,
                           null::text as venue_status, false as owned_band
                    from tbl_musician_profile musician
                    where track.owner_type='MUSICIAN_PROFILE' and musician.id=track.owner_id
                    union all
                    select listener.user_id, listener.id, 'LISTENER', listener.name,
                           listener.profile_picture_media_id, listener.visibility_mode::text,
                           listener.visibility_choice_completed, null::text, false
                    from \"tbl_listener-profile\" listener
                    where track.owner_type='LISTENER_PROFILE' and listener.id=track.owner_id
                    union all
                    select studio.user_id, studio.id, 'STUDIO', studio.name,
                           studio.profile_picture_media_id, null::text, null::boolean, null::text, false
                    from tbl_studio_profile studio
                    where track.owner_type='STUDIO_PROFILE' and studio.id=track.owner_id
                    union all
                    select venue.owner_id, venue.id, 'VENUE', venue.name,
                           venue_profile.profile_picture_media_id, null::text, null::boolean, venue.status::text, false
                    from tbl_venue_profile venue_profile join tbl_venues venue on venue.id=venue_profile.venue_id
                    where track.owner_type='VENUE_PROFILE' and venue_profile.id=track.owner_id
                    union all
                    select band_actor.user_id, band.id, 'BAND', band.name,
                           band.profile_picture_media_id, null::text, null::boolean, null::text,
                           exists(select 1 from tbl_band_member own_member
                               where own_member.band_id=band.id and own_member.user_id=:viewerId
                                 and own_member.status='ACTIVE')
                    from tbl_band band
                    join lateral (
                        select member.user_id from tbl_band_member member
                        join tbl_user member_account on member_account.id=member.user_id
                            and member_account.status='ACTIVE' and member_account.email_verified
                            and member_account.erased_at is null
                        where member.band_id=band.id and member.status='ACTIVE'
                        order by case when member.band_role='FOUNDER' then 0 else 1 end, member.id limit 1
                    ) band_actor on true
                    where track.owner_type='BAND' and band.id=track.owner_id
                ) publisher on true
                where media.status='READY' and media.visibility='PUBLIC'
                  and coalesce(media.playback_url, media.source_url) is not null
                  and track.owner_type in ('MUSICIAN_PROFILE','LISTENER_PROFILE','STUDIO_PROFILE','VENUE_PROFILE','BAND')
                  and (not :listenerAudience or track.owner_type<>'STUDIO_PROFILE')
                  and track.created_at <= :anchor
                  and track.id=source_track.id
            )
            select publication.*, media.playback_url, media.source_url, media.content_audience,
                   /* ARTIST_CITY_MATCH */ as artist_city_match,
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
              and (not :listenerAudience or media.content_audience='MAINSTAGE')
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
              and /* FEED_MODERATION */
              -- Separate equality probes keep viewer/session history out of a
              -- nested-loop OR join as the delivery ledger grows.
              and not exists(select 1 from tbl_musician_feed_feedback feedback
                  where feedback.viewer_user_id=:viewerId and feedback.action in ('HIDE','REPORT')
                    and feedback.item_id='TRACK:' || publication.track_id::text offset 0)
              and not exists(select 1 from tbl_musician_feed_feedback feedback
                  where feedback.viewer_user_id=:viewerId and feedback.action='MUTE_AUTHOR'
                    and feedback.author_profile_type=publication.author_profile_type
                    and feedback.author_profile_id=publication.author_profile_id offset 0)
              and not exists(select 1 from tbl_musician_feed_delivery delivered
                  where delivered.viewer_user_id=:viewerId and delivered.feed_session_id=:feedSessionId
                    and delivered.item_id='TRACK:' || publication.track_id::text offset 0)
              and not exists(select 1 from tbl_musician_feed_delivery delivered
                  where delivered.viewer_user_id=:viewerId and delivered.feed_session_id=:feedSessionId
                    and delivered.item_type<>'ACTIVITY_COMMENT' and delivered.target_type='MEDIA'
                    and delivered.target_id=publication.media_asset_id offset 0)
              and (case when publication.owner_type='BAND' then band_follow.id is not null
                        else following.id is not null end)=:followingPool
              and (:followingPool or not :venueAudience or publication.author_profile_type in ('MUSICIAN','BAND'))
              and (:artistCityPool='ALL'
                   or (:artistCityPool='LOCAL' and /* ARTIST_CITY_MATCH */)
                   or (:artistCityPool='OTHER' and not /* ARTIST_CITY_MATCH */))
            offset 0
            """.replace("/* FEED_MODERATION */", allowed(item("'TRACK:' || publication.track_id::text"),
                    target("'MEDIA'", "publication.media_asset_id")))
            .replace("/* ARTIST_CITY_MATCH */", MusicianFeedArtistDiscovery.cityMatchSql(
                    "publication.author_profile_type", "publication.author_profile_id", "account.city_id"));

    // ORDER BY contains only publication keys. The lateral boundary keeps every
    // eligibility check before the outer LIMIT while preventing PostgreSQL from
    // hydrating/sorting the entire catalogue or materializing session history.
    // Membership sets are a cheap exact pool prefilter; canonical follows remain
    // checked by ELIGIBLE_TRACK_SQL before any candidate can be returned.
    private static final String SQL = """
            select eligible.* from (
                select track.id,track.created_at from tbl_tracks track
                where track.created_at<=:anchor
                  and track.owner_type in ('MUSICIAN_PROFILE','LISTENER_PROFILE','STUDIO_PROFILE','VENUE_PROFILE','BAND')
                  and (not :listenerAudience or track.owner_type<>'STUDIO_PROFILE')
                  and (:followingPool or not :venueAudience or track.owner_type in ('MUSICIAN_PROFILE','BAND'))
                  and (case track.owner_type
                    when 'MUSICIAN_PROFILE' then track.owner_id in (
                        select profile.id from tbl_musician_profile profile join tbl_follow followed
                            on followed.following_id=profile.user_id where followed.follower_id=:viewerId)
                    when 'LISTENER_PROFILE' then track.owner_id in (
                        select profile.id from \"tbl_listener-profile\" profile join tbl_follow followed
                            on followed.following_id=profile.user_id where followed.follower_id=:viewerId)
                    when 'STUDIO_PROFILE' then track.owner_id in (
                        select profile.id from tbl_studio_profile profile join tbl_follow followed
                            on followed.following_id=profile.user_id where followed.follower_id=:viewerId)
                    when 'VENUE_PROFILE' then track.owner_id in (
                        select profile.id from tbl_venue_profile profile join tbl_venues venue on venue.id=profile.venue_id
                            join tbl_follow followed on followed.following_id=venue.owner_id where followed.follower_id=:viewerId)
                    when 'BAND' then track.owner_id in (
                        select followed.band_id from tbl_band_follow followed where followed.follower_id=:viewerId)
                    else false end)=:followingPool
                order by track.created_at desc,track.id desc
                offset 0
            ) source_track cross join lateral (
            """ + ELIGIBLE_TRACK_SQL + """
            ) eligible
            order by source_track.created_at desc,source_track.id desc
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
        return MusicianFeedArtistDiscovery.findPublications(jdbc, SQL, request,
                (row, index) -> MusicianFeedArtistDiscovery.prioritize(candidate(row, index), request,
                        row.getBoolean("artist_city_match"), true));
    }

    private MusicianFeedCandidate candidate(ResultSet row, int index) throws SQLException {
        UUID trackId = MusicianFeedJdbcSupport.uuid(row, "track_id");
        UUID mediaId = MusicianFeedJdbcSupport.uuid(row, "media_asset_id");
        var author = MusicianFeedJdbcSupport.author(row);
        String playback = row.getString("playback_url");
        if (playback == null || playback.isBlank()) playback = row.getString("source_url");
        var payload = new MusicianFeedPayloads.Track(trackId, mediaId, row.getString("title"), playback,
                (Integer) row.getObject("duration_seconds"), (Integer) row.getObject("bpm"), row.getString("content_audience"));
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

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
public class MusicianFeedProfileMediaCandidateProvider implements MusicianFeedCandidateProvider {
    private static final String SQL = """
            with publications as (
                select attachment.id as attachment_id, attachment.media_asset_id, attachment.profile_type,
                       attachment.profile_type as author_profile_type,
                       attachment.profile_id, attachment.role, attachment.created_at,
                       coalesce(musician.user_id, listener.user_id, studio.user_id, venue.owner_id, band_actor.user_id) as author_user_id,
                       case when attachment.profile_type='VENUE' then venue.id
                            else attachment.profile_id end as author_profile_id,
                       coalesce(band.name, venue.name, musician.stage_name, musician.name,
                                studio.name, listener.name) as profile_display_name,
                       coalesce(band.profile_picture_media_id, venue_profile.profile_picture_media_id,
                                musician.profile_picture_media_id, studio.profile_picture_media_id,
                                listener.profile_picture_media_id) as avatar_media_id,
                       listener.visibility_mode as listener_visibility,
                       listener.visibility_choice_completed as listener_choice,
                       venue.status as venue_status,
                       exists(select 1 from tbl_band_member own_member
                              where attachment.profile_type='BAND' and own_member.band_id=attachment.profile_id
                                and own_member.user_id=:viewerId and own_member.status='ACTIVE') as owned_band
                from tbl_profile_media attachment
                join tbl_media_asset media on media.id=attachment.media_asset_id
                    and media.owner_id=attachment.profile_id
                    and media.owner_type=case attachment.profile_type
                        when 'MUSICIAN' then 'MUSICIAN_PROFILE'
                        when 'LISTENER' then 'LISTENER_PROFILE'
                        when 'STUDIO' then 'STUDIO_PROFILE'
                        when 'VENUE' then 'VENUE_PROFILE'
                        when 'BAND' then 'BAND' end
                left join tbl_musician_profile musician
                    on attachment.profile_type='MUSICIAN' and musician.id=attachment.profile_id
                left join \"tbl_listener-profile\" listener
                    on attachment.profile_type='LISTENER' and listener.id=attachment.profile_id
                left join tbl_studio_profile studio
                    on attachment.profile_type='STUDIO' and studio.id=attachment.profile_id
                left join tbl_venue_profile venue_profile
                    on attachment.profile_type='VENUE' and venue_profile.id=attachment.profile_id
                left join tbl_venues venue on venue.id=venue_profile.venue_id
                left join tbl_band band on attachment.profile_type='BAND' and band.id=attachment.profile_id
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
                  and attachment.profile_type in ('MUSICIAN','LISTENER','STUDIO','VENUE','BAND')
                  and attachment.role in ('GALLERY','FEATURED_VIDEO','INTRO_VIDEO')
                  and attachment.created_at <= :anchor
            )
            select publication.*, media.kind, media.source_url, media.playback_url, media.thumbnail_url,
                   media.title, media.description, media.duration_seconds, media.width, media.height,
                   account.user_name as author_username,
                   coalesce(publication.profile_display_name, account.user_name) as author_display_name,
                   coalesce(avatar.playback_url, avatar.source_url, avatar.thumbnail_url, account.profile_picture) as author_avatar_url,
                   (case when publication.profile_type='BAND' then band_follow.id is not null
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
            left join tbl_follow following on publication.profile_type<>'BAND'
                and following.follower_id=:viewerId
                and following.following_id=publication.author_user_id
            left join tbl_band_follow band_follow on publication.profile_type='BAND'
                and band_follow.follower_id=:viewerId and band_follow.band_id=publication.profile_id
            where account.status='ACTIVE' and account.email_verified and account.erased_at is null
              and (publication.profile_type<>'LISTENER'
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
              and (publication.profile_type<>'VENUE' or publication.venue_status='APPROVED')
              and publication.author_user_id<>:viewerId and not publication.owned_band
              and not exists(select 1 from tbl_musician_feed_feedback feedback
                  where feedback.viewer_user_id=:viewerId and (
                    (feedback.action in ('HIDE','REPORT')
                     and feedback.item_id='PROFILE_MEDIA:' || publication.attachment_id::text)
                    or (feedback.action='MUTE_AUTHOR'
                        and feedback.author_profile_type=publication.profile_type
                        and feedback.author_profile_id=publication.author_profile_id)))
              and not exists(select 1 from tbl_musician_feed_delivery delivered
                  where delivered.viewer_user_id=:viewerId and delivered.feed_session_id=:feedSessionId
                    and (delivered.item_id='PROFILE_MEDIA:' || publication.attachment_id::text
                      or (delivered.item_type<>'ACTIVITY_COMMENT' and delivered.target_type='MEDIA'
                          and delivered.target_id=publication.media_asset_id)))
              and (case when publication.profile_type='BAND' then band_follow.id is not null
                        else following.id is not null end)=:followingPool
            order by publication.created_at desc, publication.attachment_id desc
            limit :limit
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public MusicianFeedProfileMediaCandidateProvider(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override public String providerId() { return "profile-media-publications"; }
    @Override public Set<MusicianFeedItemType> supportedTypes() { return Set.of(MusicianFeedItemType.PROFILE_MEDIA); }

    @Override
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW, timeout = 5)
    public List<MusicianFeedCandidate> findCandidates(MusicianFeedCandidateRequest request) {
        if (!request.supportedTypes().contains(MusicianFeedItemType.PROFILE_MEDIA)) return List.of();
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
            UUID attachmentId = MusicianFeedJdbcSupport.uuid(row, "attachment_id");
            UUID mediaId = MusicianFeedJdbcSupport.uuid(row, "media_asset_id");
            var author = MusicianFeedJdbcSupport.author(row);
            String source = row.getString("source_url");
            String playback = row.getString("playback_url");
            String kind = row.getString("kind");
            String display = "IMAGE".equals(kind) ? source
                    : row.getString("thumbnail_url") != null ? row.getString("thumbnail_url")
                    : playback != null ? playback : source;
            var payload = new MusicianFeedPayloads.ProfileMedia(mediaId, kind, display,
                    playback == null ? source : playback, row.getString("thumbnail_url"),
                    row.getString("title"), row.getString("description"),
                    (Integer) row.getObject("duration_seconds"), (Integer) row.getObject("width"),
                    (Integer) row.getObject("height"));
            return new MusicianFeedCandidate("PROFILE_MEDIA:" + attachmentId,
                    MusicianFeedItemType.PROFILE_MEDIA, 1,
                    MusicianFeedJdbcSupport.instant(row, "created_at"),
                    MusicianFeedJdbcSupport.publicationReason(author, MusicianFeedReasonCode.DISCOVERY),
                    author, new MusicianFeedItemResponse.Target("MEDIA", mediaId),
                    MusicianFeedJdbcSupport.engagement(row, "MEDIA", mediaId), null,
                    MusicianFeedJdbcSupport.standardFeedback(), payload,
                    author.followedByViewer() ? 980_000L : 285_000L,
                    0, author.followedByViewer() ? MusicianFeedLane.FOLLOWING
                            : MusicianFeedLane.GENERAL_DISCOVERY, row.getBoolean("owned_by_viewer"));
    }
}

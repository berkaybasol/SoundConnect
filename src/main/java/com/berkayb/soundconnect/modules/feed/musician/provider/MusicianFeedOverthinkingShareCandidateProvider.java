package com.berkayb.soundconnect.modules.feed.musician.provider;

import com.berkayb.soundconnect.modules.feed.musician.api.*;
import com.berkayb.soundconnect.modules.feed.musician.candidate.*;
import com.berkayb.soundconnect.modules.overthinking.dto.response.OverthinkingPostResponseDto;
import com.berkayb.soundconnect.modules.overthinking.service.OverthinkingPostService;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
public class MusicianFeedOverthinkingShareCandidateProvider implements MusicianFeedCandidateProvider {
    private static final String SQL = """
            select share.id as share_id, share.note, share.published_at,
                   account.id as author_user_id, listener.id as author_profile_id,
                   'LISTENER' as author_profile_type, account.user_name as author_username,
                   coalesce(listener.name, account.user_name) as author_display_name,
                   coalesce(avatar.playback_url, avatar.source_url, avatar.thumbnail_url,
                            account.profile_picture) as author_avatar_url,
                   true as followed_by_viewer, share.source_post_id as source_id,
                   (select count(*) from tbl_like likes where likes.target_type='OVERTHINKING_PROFILE_SHARE'
                       and likes.target_id=share.id) as like_count,
                   (select count(*) from tbl_comment comments where comments.target_type='OVERTHINKING_PROFILE_SHARE'
                       and comments.target_id=share.id and not comments.is_deleted) as comment_count,
                   exists(select 1 from tbl_like mine where mine.user_id=:viewerId
                       and mine.target_type='OVERTHINKING_PROFILE_SHARE' and mine.target_id=share.id) as liked_by_me
            from tbl_overthinking_profile_share share
            join tbl_user account on account.id=share.owner_user_id
            join \"tbl_listener-profile\" listener on listener.id=share.listener_profile_id
                and listener.user_id=account.id and listener.visibility_mode='STANDARD'
                and listener.visibility_choice_completed
            join tbl_follow following on following.follower_id=:viewerId and following.following_id=account.id
            join tbl_overthinking_post source on source.id=share.source_post_id
            left join tbl_media_asset avatar on avatar.id=listener.profile_picture_media_id
                and avatar.status='READY' and avatar.visibility='PUBLIC'
            where share.published_at<=:anchor and account.id<>:viewerId
              and account.status='ACTIVE' and account.email_verified and account.erased_at is null
              and exists(select 1 from user_roles membership join tbl_role role on role.id=membership.role_id
                  where membership.user_id=account.id and role.name='ROLE_LISTENER')
              and not exists(select 1 from user_roles membership join tbl_role role on role.id=membership.role_id
                  where membership.user_id=account.id and role.name in ('ROLE_ADMIN','ROLE_OWNER','ROLE_MUSICIAN',
                      'ROLE_VENUE','ROLE_STUDIO','ROLE_ORGANIZER','ROLE_PRODUCER'))
              and not exists(select 1 from tbl_musician_profile profile where profile.user_id=account.id)
              and not exists(select 1 from tbl_studio_profile profile where profile.user_id=account.id)
              and not exists(select 1 from tbl_organizer_profile profile where profile.user_id=account.id)
              and not exists(select 1 from tbl_producer_profile profile where profile.user_id=account.id)
              and not exists(select 1 from tbl_venues owned_venue where owned_venue.owner_id=account.id)
              and not exists(select 1 from tbl_musician_feed_feedback feedback
                  where feedback.viewer_user_id=:viewerId and (
                    (feedback.action in ('HIDE','REPORT')
                     and feedback.item_id='OVERTHINKING_PROFILE_SHARE:' || share.id::text)
                    or (feedback.action='MUTE_AUTHOR' and feedback.author_profile_type='LISTENER'
                        and feedback.author_profile_id=listener.id)))
              and not exists(select 1 from tbl_musician_feed_delivery delivered
                  where delivered.viewer_user_id=:viewerId and delivered.feed_session_id=:feedSessionId
                    and (delivered.item_id='OVERTHINKING_PROFILE_SHARE:' || share.id::text
                      or (delivered.item_type<>'ACTIVITY_COMMENT'
                          and delivered.target_type='OVERTHINKING_PROFILE_SHARE'
                          and delivered.target_id=share.id)))
            order by share.published_at desc, share.id desc
            limit :limit
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final OverthinkingPostService posts;

    public MusicianFeedOverthinkingShareCandidateProvider(
            NamedParameterJdbcTemplate jdbc,
            OverthinkingPostService posts
    ) {
        this.jdbc = jdbc;
        this.posts = posts;
    }

    @Override public String providerId() { return "listener-overthinking-profile-shares"; }
    @Override public Set<MusicianFeedItemType> supportedTypes() {
        return Set.of(MusicianFeedItemType.OVERTHINKING_PROFILE_SHARE);
    }

    @Override
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW, timeout = 5)
    public List<MusicianFeedCandidate> findCandidates(MusicianFeedCandidateRequest request) {
        if (!request.supportedTypes().contains(MusicianFeedItemType.OVERTHINKING_PROFILE_SHARE)) return List.of();
        var parameters = new MapSqlParameterSource().addValue("viewerId", request.viewerUserId())
                .addValue("feedSessionId", request.feedSessionId())
                .addValue("anchor", MusicianFeedJdbcSupport.timestamp(request.anchor()))
                .addValue("limit", request.limit());
        List<ShareRow> rows = jdbc.query(SQL, parameters, (row, index) -> {
            UUID shareId = MusicianFeedJdbcSupport.uuid(row, "share_id");
            return new ShareRow(shareId, MusicianFeedJdbcSupport.uuid(row, "source_id"),
                    row.getString("note"), MusicianFeedJdbcSupport.instant(row, "published_at"),
                    MusicianFeedJdbcSupport.author(row),
                    MusicianFeedJdbcSupport.engagement(row, "OVERTHINKING_PROFILE_SHARE", shareId));
        });
        Map<UUID, OverthinkingPostResponseDto> sources = MusicianFeedOverthinkingSources.resolve(
                posts, request.viewerUserId(), rows.stream().map(ShareRow::sourceId).toList());
        List<MusicianFeedCandidate> result = new ArrayList<>(rows.size());
        for (ShareRow row : rows) {
            OverthinkingPostResponseDto source = sources.get(row.sourceId());
            // A concurrent source deletion must fail closed instead of emitting a broken card.
            if (source == null) continue;
            var payload = new MusicianFeedPayloads.ProfileShare(
                    row.shareId(), row.note(), row.publishedAt(), source);
            result.add(new MusicianFeedCandidate("OVERTHINKING_PROFILE_SHARE:" + row.shareId(),
                    MusicianFeedItemType.OVERTHINKING_PROFILE_SHARE, 1, row.publishedAt(),
                    MusicianFeedJdbcSupport.publicationReason(row.author(), MusicianFeedReasonCode.DISCOVERY),
                    row.author(), new MusicianFeedItemResponse.Target("OVERTHINKING_PROFILE_SHARE", row.shareId()),
                    row.engagement(), null, MusicianFeedJdbcSupport.standardFeedback(), payload,
                    970_000L, 0, MusicianFeedLane.MODULE_SHARE, false));
        }
        return List.copyOf(result);
    }

    private record ShareRow(
            UUID shareId,
            UUID sourceId,
            String note,
            Instant publishedAt,
            MusicianFeedItemResponse.Author author,
            MusicianFeedItemResponse.Engagement engagement
    ) { }
}

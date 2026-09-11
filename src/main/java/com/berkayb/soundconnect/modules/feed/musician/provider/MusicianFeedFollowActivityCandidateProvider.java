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
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
public class MusicianFeedFollowActivityCandidateProvider implements MusicianFeedCandidateProvider {
    private static final int MAX_VISIBLE_ACTORS = 3;

    private static final String SQL = """
            with profile_candidates as (
                select musician.id as profile_id, 'MUSICIAN' as profile_type, account.id as user_id,
                       account.user_name as username,
                       coalesce(musician.stage_name, musician.name, account.user_name) as display_name,
                       musician.description as bio, musician.profile_picture_media_id as avatar_id,
                       city.name as location, account.profile_picture as legacy_avatar, 0 as profile_priority
                from tbl_musician_profile musician join tbl_user account on account.id=musician.user_id
                    and account.status='ACTIVE' and account.email_verified and account.erased_at is null
                left join tbl_city city on city.id=account.city_id
                union all
                select listener.id, 'LISTENER', account.id, account.user_name,
                       coalesce(listener.name, account.user_name), listener.description,
                       listener.profile_picture_media_id, city.name, account.profile_picture, 1
                from \"tbl_listener-profile\" listener join tbl_user account on account.id=listener.user_id
                    and account.status='ACTIVE' and account.email_verified and account.erased_at is null
                left join tbl_city city on city.id=account.city_id
                where listener.visibility_mode='STANDARD' and listener.visibility_choice_completed
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
                union all
                select studio.id, 'STUDIO', account.id, account.user_name,
                       coalesce(studio.name, account.user_name), studio.description,
                       studio.profile_picture_media_id, city.name, account.profile_picture, 2
                from tbl_studio_profile studio join tbl_user account on account.id=studio.user_id
                    and account.status='ACTIVE' and account.email_verified and account.erased_at is null
                left join tbl_city city on city.id=studio.city_id
                union all
                select venue.id, 'VENUE', account.id, account.user_name, venue.name, venue.description,
                       venue_profile.profile_picture_media_id, city.name, account.profile_picture, 3
                from tbl_venue_profile venue_profile join tbl_venues venue on venue.id=venue_profile.venue_id
                join tbl_user account on account.id=venue.owner_id and account.status='ACTIVE'
                    and account.email_verified and account.erased_at is null
                join tbl_city city on city.id=venue.city_id where venue.status='APPROVED'
            ), profiles as (
                select profile_id, profile_type, user_id, username, display_name, bio, avatar_id,
                       location, legacy_avatar
                from (select candidate.*, row_number() over (
                    partition by user_id order by profile_priority, profile_id) as profile_rank
                    from profile_candidates candidate) ranked
                where profile_rank=1
            ), follow_stories as (
                select activity.id as activity_id,
                       coalesce(activity.followed_at, activity.created_at) as occurred_at,
                       actor_profile.user_id as actor_user_id, actor_profile.profile_id as actor_profile_id,
                       actor_profile.profile_type as actor_profile_type, actor_profile.username as actor_username,
                       actor_profile.display_name as actor_display_name,
                       coalesce(actor_avatar.playback_url, actor_avatar.source_url,
                                actor_avatar.thumbnail_url, actor_profile.legacy_avatar) as actor_avatar_url,
                       target_profile.profile_id as target_profile_id,
                       target_profile.profile_type as target_profile_type,
                       target_profile.user_id as target_user_id, target_profile.username as target_username,
                       target_profile.display_name as target_display_name,
                       coalesce(target_avatar.playback_url, target_avatar.source_url,
                                target_avatar.thumbnail_url, target_profile.legacy_avatar) as target_avatar_url,
                       target_profile.bio as target_bio, target_profile.location as target_location,
                       (target_follow.id is not null) as target_followed_by_viewer
                from tbl_follow activity
                join profiles actor_profile on actor_profile.user_id=activity.follower_id
                join profiles target_profile on target_profile.user_id=activity.following_id
                join tbl_follow viewer_actor on viewer_actor.follower_id=:viewerId
                    and viewer_actor.following_id=actor_profile.user_id
                left join tbl_follow target_follow on target_follow.follower_id=:viewerId
                    and target_follow.following_id=target_profile.user_id
                left join tbl_media_asset actor_avatar on actor_avatar.id=actor_profile.avatar_id
                    and actor_avatar.status='READY' and actor_avatar.visibility='PUBLIC'
                left join tbl_media_asset target_avatar on target_avatar.id=target_profile.avatar_id
                    and target_avatar.status='READY' and target_avatar.visibility='PUBLIC'
                where actor_profile.user_id<>:viewerId and target_profile.user_id<>:viewerId
                  and coalesce(activity.followed_at, activity.created_at)<=:anchor
                union all
                select activity.id, coalesce(activity.followed_at, activity.created_at),
                       actor_profile.user_id, actor_profile.profile_id, actor_profile.profile_type,
                       actor_profile.username, actor_profile.display_name,
                       coalesce(actor_avatar.playback_url, actor_avatar.source_url,
                                actor_avatar.thumbnail_url, actor_profile.legacy_avatar),
                       band.id, 'BAND', band_actor.user_id, band_actor.username, band.name,
                       coalesce(band_avatar.playback_url, band_avatar.source_url, band_avatar.thumbnail_url),
                       band.description, null::varchar, (viewer_band_follow.id is not null)
                from tbl_band_follow activity
                join profiles actor_profile on actor_profile.user_id=activity.follower_id
                join tbl_follow viewer_actor on viewer_actor.follower_id=:viewerId
                    and viewer_actor.following_id=actor_profile.user_id
                join tbl_band band on band.id=activity.band_id
                join lateral (
                    select member.user_id, member_account.user_name as username
                    from tbl_band_member member
                    join tbl_user member_account on member_account.id=member.user_id
                        and member_account.status='ACTIVE' and member_account.email_verified
                        and member_account.erased_at is null
                        and member_account.user_name is not null and btrim(member_account.user_name)<>''
                    where member.band_id=band.id and member.status='ACTIVE'
                    order by case when member.band_role='FOUNDER' then 0 else 1 end, member.id
                    limit 1
                ) band_actor on true
                left join tbl_band_follow viewer_band_follow on viewer_band_follow.follower_id=:viewerId
                    and viewer_band_follow.band_id=band.id
                left join tbl_media_asset actor_avatar on actor_avatar.id=actor_profile.avatar_id
                    and actor_avatar.status='READY' and actor_avatar.visibility='PUBLIC'
                left join tbl_media_asset band_avatar on band_avatar.id=band.profile_picture_media_id
                    and band_avatar.status='READY' and band_avatar.visibility='PUBLIC'
                where actor_profile.user_id<>:viewerId
                  and coalesce(activity.followed_at, activity.created_at)<=:anchor
                  and not exists(select 1 from tbl_band_member own_member
                      where own_member.band_id=band.id and own_member.user_id=:viewerId
                        and own_member.status='ACTIVE')
            ), eligible as (
                select story.*,
                       'ACTIVITY_FOLLOW:' || story.target_profile_type || ':'
                           || story.target_profile_id::text as item_id
                from follow_stories story
                where not exists(select 1 from tbl_musician_feed_feedback feedback
                    where feedback.viewer_user_id=:viewerId and (
                      (feedback.action in ('HIDE','REPORT') and feedback.item_id=
                          'ACTIVITY_FOLLOW:' || story.target_profile_type || ':' || story.target_profile_id::text)
                      or (feedback.action='MUTE_AUTHOR'
                          and feedback.author_profile_type=story.actor_profile_type
                          and feedback.author_profile_id=story.actor_profile_id)))
                  and not exists(select 1 from tbl_musician_feed_delivery delivered
                    where delivered.viewer_user_id=:viewerId and delivered.feed_session_id=:feedSessionId
                      and (delivered.item_id='ACTIVITY_FOLLOW:' || story.target_profile_type || ':'
                              || story.target_profile_id::text
                        or (delivered.item_type<>'ACTIVITY_COMMENT' and delivered.target_type='PROFILE'
                            and delivered.target_id=story.target_profile_id)))
            ), ranked as (
                select eligible.*,
                       row_number() over (partition by target_profile_type, target_profile_id
                           order by occurred_at desc, activity_id desc) as actor_rank,
                       count(*) over (partition by target_profile_type, target_profile_id) as actor_count
                from eligible
            )
            select * from ranked where actor_rank<=:visibleActorLimit
            order by occurred_at desc, activity_id desc
            limit :limit
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public MusicianFeedFollowActivityCandidateProvider(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override public String providerId() { return "followed-user-follow-activity"; }
    @Override public Set<MusicianFeedItemType> supportedTypes() {
        return Set.of(MusicianFeedItemType.ACTIVITY_FOLLOW);
    }

    @Override
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW, timeout = 5)
    public List<MusicianFeedCandidate> findCandidates(MusicianFeedCandidateRequest request) {
        if (!request.supportedTypes().contains(MusicianFeedItemType.ACTIVITY_FOLLOW)
                || !request.supportedTypes().contains(MusicianFeedItemType.PROFILE)) return List.of();
        var parameters = new MapSqlParameterSource().addValue("viewerId", request.viewerUserId())
                .addValue("feedSessionId", request.feedSessionId())
                .addValue("anchor", MusicianFeedJdbcSupport.timestamp(request.anchor()))
                .addValue("visibleActorLimit", MAX_VISIBLE_ACTORS)
                .addValue("limit", request.limit());
        List<FollowRow> rows = jdbc.query(SQL, parameters, this::row);
        Map<String, FollowGroup> groups = new LinkedHashMap<>();
        for (FollowRow row : rows) groups.computeIfAbsent(row.itemId(), ignored -> new FollowGroup(row)).add(row);

        List<MusicianFeedCandidate> result = groups.values().stream()
                .map(FollowGroup::candidate)
                .sorted((left, right) -> {
                    int time = right.occurredAt().compareTo(left.occurredAt());
                    return time != 0 ? time : left.itemId().compareTo(right.itemId());
                })
                .limit(request.limit())
                .toList();
        return List.copyOf(result);
    }

    private FollowRow row(ResultSet row, int index) throws SQLException {
        var actor = new MusicianFeedItemResponse.Author(
                MusicianFeedJdbcSupport.uuid(row, "actor_user_id"),
                MusicianFeedJdbcSupport.uuid(row, "actor_profile_id"), row.getString("actor_profile_type"),
                row.getString("actor_username"), row.getString("actor_display_name"),
                row.getString("actor_avatar_url"), true);
        var target = new MusicianFeedPayloads.Profile(
                MusicianFeedJdbcSupport.uuid(row, "target_profile_id"), row.getString("target_profile_type"),
                MusicianFeedJdbcSupport.uuid(row, "target_user_id"), row.getString("target_username"),
                row.getString("target_display_name"), row.getString("target_avatar_url"),
                row.getString("target_bio"), row.getString("target_location"),
                row.getBoolean("target_followed_by_viewer"));
        return new FollowRow(row.getString("item_id"), MusicianFeedJdbcSupport.instant(row, "occurred_at"),
                actor, target, row.getLong("actor_count"));
    }

    private record FollowRow(
            String itemId,
            Instant occurredAt,
            MusicianFeedItemResponse.Author actor,
            MusicianFeedPayloads.Profile target,
            long actorCount
    ) { }

    private static final class FollowGroup {
        private final FollowRow primary;
        private final LinkedHashMap<String, MusicianFeedItemResponse.Author> actors = new LinkedHashMap<>();
        private long actorCount;

        private FollowGroup(FollowRow primary) {
            this.primary = primary;
        }

        private void add(FollowRow row) {
            actorCount = Math.max(actorCount, row.actorCount());
            actors.putIfAbsent(row.actor().profileType() + ":" + row.actor().profileId(), row.actor());
        }

        private MusicianFeedCandidate candidate() {
            List<MusicianFeedItemResponse.Author> visible = actors.values().stream()
                    .limit(MAX_VISIBLE_ACTORS).toList();
            MusicianFeedItemResponse.Author actor = visible.getFirst();
            var reason = new MusicianFeedItemResponse.Reason(MusicianFeedReasonCode.FOLLOWED_USER_FOLLOWED,
                    visible, secondaryCount(actorCount, visible.size()));
            var payload = new MusicianFeedPayloads.Activity("FOLLOW", actor,
                    MusicianFeedItemType.PROFILE, primary.target());
            return new MusicianFeedCandidate(primary.itemId(), MusicianFeedItemType.ACTIVITY_FOLLOW, 1,
                    primary.occurredAt(), reason, actor,
                    new MusicianFeedItemResponse.Target("PROFILE", primary.target().profileId()),
                    null, null, MusicianFeedJdbcSupport.standardFeedback(), payload,
                    760_000L, 0, MusicianFeedLane.FOLLOWING, false);
        }
    }

    private static int secondaryCount(long total, int visible) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, total - visible));
    }
}

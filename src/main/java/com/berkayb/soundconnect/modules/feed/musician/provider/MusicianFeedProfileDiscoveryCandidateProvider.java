package com.berkayb.soundconnect.modules.feed.musician.provider;

import com.berkayb.soundconnect.modules.feed.musician.api.*;
import com.berkayb.soundconnect.modules.feed.musician.candidate.*;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Component
public class MusicianFeedProfileDiscoveryCandidateProvider implements MusicianFeedCandidateProvider {
    private static final String SQL = """
            with profiles as (
                select musician.id as profile_id, 'MUSICIAN' as profile_type, musician.user_id,
                       coalesce(musician.stage_name, musician.name) as display_name,
                       musician.description as bio, musician.profile_picture_media_id as avatar_id,
                       account.city_id, city.name as location, musician.created_at
                from tbl_musician_profile musician
                join tbl_user account on account.id=musician.user_id
                left join tbl_city city on city.id=account.city_id
                union all
                select listener.id, 'LISTENER', listener.user_id, listener.name, listener.description,
                       listener.profile_picture_media_id, account.city_id, city.name, listener.created_at
                from \"tbl_listener-profile\" listener
                join tbl_user account on account.id=listener.user_id
                left join tbl_city city on city.id=account.city_id
                where listener.visibility_mode='STANDARD' and listener.visibility_choice_completed
                  and exists(select 1 from user_roles membership join tbl_role role on role.id=membership.role_id
                      where membership.user_id=account.id and role.name='ROLE_LISTENER')
                  and not exists(select 1 from user_roles membership join tbl_role role on role.id=membership.role_id
                      where membership.user_id=account.id and role.name in ('ROLE_ADMIN','ROLE_OWNER',
                          'ROLE_MUSICIAN','ROLE_VENUE','ROLE_STUDIO','ROLE_ORGANIZER','ROLE_PRODUCER'))
                  and not exists(select 1 from tbl_musician_profile profile where profile.user_id=account.id)
                  and not exists(select 1 from tbl_studio_profile profile where profile.user_id=account.id)
                  and not exists(select 1 from tbl_organizer_profile profile where profile.user_id=account.id)
                  and not exists(select 1 from tbl_producer_profile profile where profile.user_id=account.id)
                  and not exists(select 1 from tbl_venues venue where venue.owner_id=account.id)
                union all
                select studio.id, 'STUDIO', studio.user_id, studio.name, studio.description,
                       studio.profile_picture_media_id, studio.city_id, city.name, studio.created_at
                from tbl_studio_profile studio left join tbl_city city on city.id=studio.city_id
                union all
                select venue.id, 'VENUE', venue.owner_id, venue.name, venue.description,
                       venue_profile.profile_picture_media_id, venue.city_id, city.name, venue_profile.created_at
                from tbl_venue_profile venue_profile join tbl_venues venue on venue.id=venue_profile.venue_id
                join tbl_city city on city.id=venue.city_id where venue.status='APPROVED'
                union all
                select band.id, 'BAND', band_actor.user_id, band.name, band.description,
                       band.profile_picture_media_id, null::uuid, null, band.created_at
                from tbl_band band
                join lateral (
                    select member.user_id from tbl_band_member member
                    join tbl_user member_account on member_account.id=member.user_id
                        and member_account.status='ACTIVE' and member_account.email_verified
                        and member_account.erased_at is null
                    where member.band_id=band.id and member.status='ACTIVE'
                    order by case when member.band_role='FOUNDER' then 0 else 1 end, member.id limit 1
                ) band_actor on true
            )
            select profile.profile_id as author_profile_id, profile.profile_type as author_profile_type,
                   profile.user_id as author_user_id, account.user_name as author_username,
                   coalesce(profile.display_name, account.user_name) as author_display_name,
                   coalesce(avatar.playback_url, avatar.source_url, avatar.thumbnail_url, account.profile_picture) as author_avatar_url,
                   false as followed_by_viewer, profile.bio, profile.location, profile.created_at,
                   (:hasCity and profile.city_id=:cityId
                       and profile.profile_type in ('VENUE','STUDIO')) as city_match,
                   exists(select 1 from tbl_band_member member where profile.profile_type='BAND'
                       and member.band_id=profile.profile_id and member.user_id=:viewerId
                       and member.status='ACTIVE') as owned_band
            from profiles profile
            join tbl_user account on account.id=profile.user_id
            left join tbl_media_asset avatar on avatar.id=profile.avatar_id
                and avatar.status='READY' and avatar.visibility='PUBLIC'
            left join tbl_follow following on profile.profile_type<>'BAND'
                and following.follower_id=:viewerId and following.following_id=profile.user_id
            left join tbl_band_follow band_follow on profile.profile_type='BAND'
                and band_follow.follower_id=:viewerId and band_follow.band_id=profile.profile_id
            where profile.created_at<=:anchor and profile.user_id<>:viewerId
              and account.status='ACTIVE' and account.email_verified and account.erased_at is null
              and not exists(select 1 from tbl_band_member own_member
                    where profile.profile_type='BAND' and own_member.band_id=profile.profile_id
                      and own_member.user_id=:viewerId and own_member.status='ACTIVE')
              and following.id is null and band_follow.id is null
              and not exists(select 1 from tbl_musician_feed_feedback feedback
                  where feedback.viewer_user_id=:viewerId and (
                    (feedback.action in ('HIDE','REPORT')
                     and feedback.item_id='PROFILE:' || profile.profile_id::text)
                    or (feedback.action='MUTE_AUTHOR'
                        and feedback.author_profile_type=profile.profile_type
                        and feedback.author_profile_id=profile.profile_id)))
              and not exists(select 1 from tbl_musician_feed_delivery delivered
                  where delivered.viewer_user_id=:viewerId and delivered.feed_session_id=:feedSessionId
                    and (delivered.item_id='PROFILE:' || profile.profile_id::text
                      or (delivered.item_type<>'ACTIVITY_COMMENT' and delivered.target_type='PROFILE'
                          and delivered.target_id=profile.profile_id)))
              and ((:pool='RELEVANT' and :hasCity and profile.city_id=:cityId
                         and profile.profile_type in ('VENUE','STUDIO'))
                   or (:pool='GENERAL' and not (:hasCity and profile.city_id=:cityId
                         and profile.profile_type in ('VENUE','STUDIO'))))
            order by profile.created_at desc, profile.profile_id desc
            limit :limit
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public MusicianFeedProfileDiscoveryCandidateProvider(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override public String providerId() { return "profile-discovery"; }
    @Override public Set<MusicianFeedItemType> supportedTypes() { return Set.of(MusicianFeedItemType.PROFILE); }

    @Override
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW, timeout = 5)
    public List<MusicianFeedCandidate> findCandidates(MusicianFeedCandidateRequest request) {
        if (!request.supportedTypes().contains(MusicianFeedItemType.PROFILE)) return List.of();
        UUID cityId = request.personalization().opportunityCityId();
        int totalLimit = Math.min(request.limit(), 24);
        int relevantLimit = totalLimit * 2 / 3;
        int generalLimit = totalLimit - relevantLimit;
        var parameters = new MapSqlParameterSource().addValue("viewerId", request.viewerUserId())
                .addValue("feedSessionId", request.feedSessionId())
                .addValue("anchor", MusicianFeedJdbcSupport.timestamp(request.anchor()))
                .addValue("cityId", cityId == null ? new UUID(0, 0) : cityId)
                .addValue("hasCity", cityId != null);
        List<MusicianFeedCandidate> result = new ArrayList<>(totalLimit);
        if (relevantLimit > 0) result.addAll(jdbc.query(SQL, parameters.addValue("pool", "RELEVANT")
                .addValue("limit", relevantLimit), this::candidate));
        if (generalLimit > 0) result.addAll(jdbc.query(SQL, parameters.addValue("pool", "GENERAL")
                .addValue("limit", generalLimit), this::candidate));
        return List.copyOf(result);
    }

    private MusicianFeedCandidate candidate(java.sql.ResultSet row, int index) throws java.sql.SQLException {
            var author = MusicianFeedJdbcSupport.author(row);
            var payload = new MusicianFeedPayloads.Profile(author.profileId(), author.profileType(),
                    author.userId(), author.username(), author.displayName(), author.avatarUrl(),
                    row.getString("bio"), row.getString("location"), false);
            return new MusicianFeedCandidate("PROFILE:" + author.profileId(), MusicianFeedItemType.PROFILE, 1,
                    MusicianFeedJdbcSupport.instant(row, "created_at"),
                    new MusicianFeedItemResponse.Reason(row.getBoolean("city_match")
                            ? MusicianFeedReasonCode.CITY_MATCH : MusicianFeedReasonCode.DISCOVERY, List.of(), 0),
                    author, new MusicianFeedItemResponse.Target("PROFILE", author.profileId()), null, null,
                    MusicianFeedJdbcSupport.standardFeedback(), payload,
                    220_000L, row.getBoolean("city_match") ? 90_000 : 0,
                    row.getBoolean("city_match") ? MusicianFeedLane.RELEVANT_OPPORTUNITY
                            : MusicianFeedLane.GENERAL_DISCOVERY, row.getBoolean("owned_band"));
    }
}

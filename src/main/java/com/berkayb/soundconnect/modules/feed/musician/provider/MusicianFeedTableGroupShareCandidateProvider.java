package com.berkayb.soundconnect.modules.feed.musician.provider;

import com.berkayb.soundconnect.modules.feed.musician.api.*;
import com.berkayb.soundconnect.modules.feed.musician.candidate.*;
import com.berkayb.soundconnect.modules.tablegroup.enums.TableGroupStatus;
import com.berkayb.soundconnect.modules.tablegroup.profileshare.TableGroupProfileShareResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Component
public class MusicianFeedTableGroupShareCandidateProvider implements MusicianFeedCandidateProvider {
    private static final String SQL = """
            select share.id as share_id, share.note, share.published_at, share.final_source::text as final_source,
                   account.id as author_user_id, listener.id as author_profile_id,
                   'LISTENER' as author_profile_type, account.user_name as author_username,
                   coalesce(listener.name, account.user_name) as author_display_name,
                   coalesce(avatar.playback_url, avatar.source_url, avatar.thumbnail_url, account.profile_picture) as author_avatar_url,
                   true as followed_by_viewer, (account.id=:viewerId) as owned_by_viewer,
                   table_group.id as source_id, table_group.description, table_group.venue_name,
                   city.name as city_name, district.name as district_name, table_group.meeting_at,
                   table_group.expires_at, table_group.status, table_group.max_person_count,
                   (select count(*) from tbl_table_group_participants participant
                       where participant.table_group_id=table_group.id and participant.status='ACCEPTED') as accepted_count,
                   (select count(*) from tbl_like likes where likes.target_type='TABLE_GROUP_POST'
                       and likes.target_id=share.id) as like_count,
                   (select count(*) from tbl_comment comments where comments.target_type='TABLE_GROUP_POST'
                       and comments.target_id=share.id and not comments.is_deleted) as comment_count,
                   exists(select 1 from tbl_like mine where mine.user_id=:viewerId
                       and mine.target_type='TABLE_GROUP_POST' and mine.target_id=share.id) as liked_by_me
            from tbl_table_group_profile_share share
            join tbl_user account on account.id=share.owner_user_id
            join \"tbl_listener-profile\" listener on listener.id=share.listener_profile_id
                and listener.user_id=account.id and listener.visibility_mode='STANDARD'
                and listener.visibility_choice_completed
            join tbl_follow following on following.follower_id=:viewerId and following.following_id=account.id
            join tbl_table_group table_group on table_group.id=share.table_group_id
            join tbl_city city on city.id=table_group.city_id
            left join tbl_district district on district.id=table_group.district_id
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
                     and feedback.item_id='TABLEGROUP_PROFILE_SHARE:' || share.id::text)
                    or (feedback.action='MUTE_AUTHOR' and feedback.author_profile_type='LISTENER'
                        and feedback.author_profile_id=listener.id)))
              and not exists(select 1 from tbl_musician_feed_delivery delivered
                  where delivered.viewer_user_id=:viewerId and delivered.feed_session_id=:feedSessionId
                    and (delivered.item_id='TABLEGROUP_PROFILE_SHARE:' || share.id::text
                      or (delivered.item_type<>'ACTIVITY_COMMENT'
                          and delivered.target_type='TABLE_GROUP_POST'
                          and delivered.target_id=share.id)))
              and (share.final_source is not null or (not share.final_source_frozen
                   and table_group.status='ACTIVE' and table_group.expires_at>:readAt
                   and (table_group.owner_id=share.owner_user_id or exists (
                       select 1 from tbl_table_group_participants eligible
                       where eligible.table_group_id=table_group.id
                         and eligible.user_id=share.owner_user_id and eligible.status='ACCEPTED'))))
            order by share.published_at desc, share.id desc
            limit :limit
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public MusicianFeedTableGroupShareCandidateProvider(
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper objectMapper
    ) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override public String providerId() { return "listener-tablegroup-profile-shares"; }
    @Override public Set<MusicianFeedItemType> supportedTypes() {
        return Set.of(MusicianFeedItemType.TABLEGROUP_PROFILE_SHARE);
    }

    @Override
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW, timeout = 5)
    public List<MusicianFeedCandidate> findCandidates(MusicianFeedCandidateRequest request) {
        if (!request.supportedTypes().contains(MusicianFeedItemType.TABLEGROUP_PROFILE_SHARE)) return List.of();
        var parameters = new MapSqlParameterSource().addValue("viewerId", request.viewerUserId())
                .addValue("feedSessionId", request.feedSessionId())
                .addValue("anchor", MusicianFeedJdbcSupport.timestamp(request.anchor()))
                .addValue("readAt", MusicianFeedJdbcSupport.timestamp(request.readAt()))
                .addValue("limit", request.limit());
        return jdbc.query(SQL, parameters, (row, index) -> {
            UUID shareId = MusicianFeedJdbcSupport.uuid(row, "share_id");
            TableGroupProfileShareResponse.Source source;
            String frozen = row.getString("final_source");
            if (frozen != null && !frozen.isBlank()) {
                source = readFrozenSource(frozen);
            } else {
                source = new TableGroupProfileShareResponse.Source(
                        MusicianFeedJdbcSupport.uuid(row, "source_id"), row.getString("description"),
                        row.getString("venue_name"), row.getString("city_name"), row.getString("district_name"),
                        MusicianFeedJdbcSupport.instant(row, "meeting_at"),
                        MusicianFeedJdbcSupport.instant(row, "expires_at"),
                        TableGroupStatus.valueOf(row.getString("status")), row.getInt("max_person_count"),
                        row.getLong("accepted_count"));
            }
            var author = MusicianFeedJdbcSupport.author(row);
            var payload = new MusicianFeedPayloads.ProfileShare(shareId, row.getString("note"),
                    MusicianFeedJdbcSupport.instant(row, "published_at"), source);
            return new MusicianFeedCandidate("TABLEGROUP_PROFILE_SHARE:" + shareId,
                    MusicianFeedItemType.TABLEGROUP_PROFILE_SHARE, 1,
                    MusicianFeedJdbcSupport.instant(row, "published_at"),
                    MusicianFeedJdbcSupport.publicationReason(author, MusicianFeedReasonCode.DISCOVERY),
                    author, new MusicianFeedItemResponse.Target("TABLE_GROUP_POST", shareId),
                    MusicianFeedJdbcSupport.engagement(row, "TABLE_GROUP_POST", shareId), null,
                    MusicianFeedJdbcSupport.standardFeedback(), payload,
                    950_000L, 0, MusicianFeedLane.MODULE_SHARE, false);
        });
    }

    private TableGroupProfileShareResponse.Source readFrozenSource(String json) throws java.sql.SQLException {
        try {
            return objectMapper.readValue(json, TableGroupProfileShareResponse.Source.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalidSnapshot) {
            throw new java.sql.SQLException("Invalid frozen TableGroup public snapshot", invalidSnapshot);
        }
    }
}

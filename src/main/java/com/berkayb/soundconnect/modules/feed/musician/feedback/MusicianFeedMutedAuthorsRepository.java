package com.berkayb.soundconnect.modules.feed.musician.feedback;

import com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedMutedAuthorResponse;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class MusicianFeedMutedAuthorsRepository {
    // Page the persisted preferences before resolving identities. Deleted or
    // restricted profiles must remain removable, without revealing cached names.
    private static final String SQL = """
            with page as materialized (
                select muted.id,muted.author_profile_type,muted.author_profile_id,muted.created_at
                from tbl_musician_feed_feedback muted
                where muted.viewer_user_id=:viewerId and muted.action='MUTE_AUTHOR'
                  and muted.created_at<=:anchor
                  %s
                order by muted.created_at desc,muted.id desc limit :limit
            ), profiles as (
                select muted.id as feedback_id,profile.user_id,null::text as display_name,
                       profile.profile_picture_media_id as avatar_id
                from page muted join tbl_musician_profile profile
                  on muted.author_profile_type='MUSICIAN' and profile.id=muted.author_profile_id
                union all
                select muted.id,profile.user_id,nullif(trim(profile.name),''),profile.profile_picture_media_id
                from page muted join "tbl_listener-profile" profile
                  on muted.author_profile_type='LISTENER' and profile.id=muted.author_profile_id
                where profile.visibility_mode='STANDARD' and profile.visibility_choice_completed
                  and exists(select 1 from user_roles membership join tbl_role role on role.id=membership.role_id
                      where membership.user_id=profile.user_id and role.name='ROLE_LISTENER')
                  and not exists(select 1 from user_roles membership join tbl_role role on role.id=membership.role_id
                      where membership.user_id=profile.user_id and role.name in ('ROLE_ADMIN','ROLE_OWNER',
                          'ROLE_MUSICIAN','ROLE_VENUE','ROLE_STUDIO','ROLE_ORGANIZER','ROLE_PRODUCER'))
                  and not exists(select 1 from tbl_musician_profile other where other.user_id=profile.user_id)
                  and not exists(select 1 from tbl_studio_profile other where other.user_id=profile.user_id)
                  and not exists(select 1 from tbl_organizer_profile other where other.user_id=profile.user_id)
                  and not exists(select 1 from tbl_producer_profile other where other.user_id=profile.user_id)
                  and not exists(select 1 from tbl_venues other where other.owner_id=profile.user_id)
                union all
                select muted.id,profile.user_id,nullif(trim(profile.name),''),profile.profile_picture_media_id
                from page muted join tbl_studio_profile profile
                  on muted.author_profile_type='STUDIO' and profile.id=muted.author_profile_id
                union all
                select muted.id,venue.owner_id,nullif(trim(venue.name),''),profile.profile_picture_media_id
                from page muted join tbl_venues venue
                  on muted.author_profile_type='VENUE' and venue.id=muted.author_profile_id
                left join tbl_venue_profile profile on profile.venue_id=venue.id
                where venue.status='APPROVED'
                union all
                select muted.id,member.user_id,nullif(trim(band.name),''),band.profile_picture_media_id
                from page muted join tbl_band band
                  on muted.author_profile_type='BAND' and band.id=muted.author_profile_id
                join lateral (
                    select membership.user_id from tbl_band_member membership
                    join tbl_user account on account.id=membership.user_id and account.status='ACTIVE'
                        and account.email_verified and account.erased_at is null
                    where membership.band_id=band.id and membership.status='ACTIVE'
                    order by case when membership.band_role='FOUNDER' then 0 else 1 end,membership.id limit 1
                ) member on true
            ), public_profiles as (
                select profile.feedback_id,coalesce(profile.display_name,account.user_name) as display_name,
                       coalesce(avatar.playback_url,avatar.source_url,avatar.thumbnail_url,
                           case when profile.avatar_id is null then account.profile_picture end) as avatar_url
                from profiles profile join tbl_user account on account.id=profile.user_id
                    and account.status='ACTIVE' and account.email_verified and account.erased_at is null
                left join tbl_media_asset avatar on avatar.id=profile.avatar_id
                    and avatar.status='READY' and avatar.visibility='PUBLIC'
            )
            select page.*,profile.display_name,profile.avatar_url,
                   (profile.feedback_id is not null) as available
            from page left join public_profiles profile on profile.feedback_id=page.id
            order by page.created_at desc,page.id desc
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public MusicianFeedMutedAuthorsRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Row> findPage(UUID viewerId, Instant anchor,
                              MusicianFeedMutedAuthorsCursorCodec.Position after, int limit) {
        if (viewerId == null || anchor == null || limit < 1 || limit > 51) {
            throw new IllegalArgumentException("Invalid muted-author query bounds");
        }
        var parameters = new MapSqlParameterSource().addValue("viewerId", viewerId)
                .addValue("anchor", Timestamp.from(anchor)).addValue("limit", limit);
        String keyset = "";
        if (after != null) {
            keyset = "and (muted.created_at,muted.id)<(:mutedAt,:feedbackId)";
            parameters.addValue("mutedAt", Timestamp.from(after.mutedAt()))
                    .addValue("feedbackId", after.feedbackId());
        }
        return jdbc.query(SQL.formatted(keyset), parameters, (row, index) -> new Row(
                row.getObject("id", UUID.class), new MusicianFeedMutedAuthorResponse(
                row.getString("author_profile_type"), row.getObject("author_profile_id", UUID.class),
                row.getString("display_name"), row.getString("avatar_url"), row.getBoolean("available"),
                row.getTimestamp("created_at").toInstant())));
    }

    public record Row(UUID feedbackId, MusicianFeedMutedAuthorResponse author) { }
}

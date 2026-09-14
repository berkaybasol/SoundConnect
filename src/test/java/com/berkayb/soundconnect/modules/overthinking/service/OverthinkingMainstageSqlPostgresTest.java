package com.berkayb.soundconnect.modules.overthinking.service;

import com.berkayb.soundconnect.modules.overthinking.profileshare.OverthinkingProfileShareRepository;
import com.berkayb.soundconnect.modules.overthinking.repository.OverthinkingPostRepository;
import com.berkayb.soundconnect.modules.overthinking.support.OverthinkingMainstageVisibility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/** Dedicated throwaway database: verifies the production predicates and migration, never app data. */
@Testcontainers
class OverthinkingMainstageSqlPostgresTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine")
            .withDatabaseName("mainstage_boundary_test").withUsername("boundary_test").withPassword("boundary_test")
            .withReuse(false);
    NamedParameterJdbcTemplate jdbc;
    UUID author = UUID.randomUUID();

    @BeforeEach void schema() {
        jdbc = new NamedParameterJdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        jdbc.getJdbcTemplate().execute("""
                drop table if exists tbl_overthinking_profile_share,tbl_overthinking_post,tbl_tracks,tbl_media_asset,
                    tbl_studio_profile,user_roles,tbl_role,tbl_like,soundconnect_schema_migrations;
                create table tbl_studio_profile(user_id uuid);
                create table tbl_role(id uuid primary key,name text);
                create table user_roles(user_id uuid,role_id uuid);
                create table tbl_media_asset(id uuid primary key,owner_id uuid,owner_type text,
                    content_audience varchar(16) default 'MAINSTAGE',visibility text,status text,playback_url text,source_url text);
                create table tbl_tracks(id uuid primary key,media_asset_id uuid);
                create table tbl_overthinking_post(id uuid primary key,author_id uuid,musician_track_id uuid,band_track_id uuid,
                    artist_id uuid,visibility_type text,title text,created_at timestamp);
                create table tbl_like(id uuid primary key,target_id uuid,target_type text);
                create table tbl_overthinking_profile_share(id uuid primary key,listener_profile_id uuid,owner_user_id uuid,
                    source_post_id uuid,published_at timestamptz,note text);
                """);
    }

    @Test void anonymousStudioSourcesAreExcludedByProfileOrRoleWhileListenerOriginalIdsRemain() {
        UUID normal = post(author, null, null, 1);
        UUID studioOwner = UUID.randomUUID(), studioPost = post(studioOwner, null, null, 2);
        UUID roleOwner = UUID.randomUUID(), rolePost = post(roleOwner, null, null, 3), role = UUID.randomUUID();
        jdbc.update("insert into tbl_studio_profile values(:id)", Map.of("id", studioOwner));
        jdbc.update("insert into tbl_role values(:id,'ROLE_STUDIO')", Map.of("id", role));
        jdbc.update("insert into user_roles values(:owner,:role)", Map.of("owner", roleOwner, "role", role));
        assertThat(visible()).containsExactly(normal).doesNotContain(studioPost, rolePost);
    }

    @Test void businessTracksAndStudioOwnershipStayOutButRemovedMusicDoesNotEraseRetainedText() {
        UUID normal = post(author, track("MAINSTAGE", "MUSICIAN_PROFILE"), null, 1);
        UUID business = post(author, track("BACKSTAGE", "MUSICIAN_PROFILE"), null, 2);
        UUID band = post(author, null, track("BACKSTAGE", "BAND"), 3);
        UUID studio = post(author, track("MAINSTAGE", "STUDIO_PROFILE"), null, 4);
        UUID unknown = post(author, track("UNKNOWN", "BAND"), null, 5);
        UUID removed = post(author, UUID.randomUUID(), null, 6);
        assertThat(visible()).containsExactlyInAnyOrder(normal, removed).doesNotContain(business, band, studio, unknown);
    }

    @Test void sourceAndRepostQueriesFilterBeforeLimitAndUseMatchingCounts() throws Exception {
        UUID businessTrack = track("BACKSTAGE", "MUSICIAN_PROFILE");
        for (int i = 0; i < 30; i++) post(author, businessTrack, null, i + 10);
        UUID first = post(author, null, null, 1), second = post(author, null, null, 2), third = post(author, null, null, 3);
        Query source = OverthinkingPostRepository.class.getMethod("findMainstagePage",
                UUID.class, UUID.class, boolean.class, Pageable.class).getAnnotation(Query.class);
        var parameters = new MapSqlParameterSource().addValue("authorId", null).addValue("artistId", null)
                .addValue("oldest", false).addValue("limit", 2);
        assertThat(jdbc.query(source.value() + " limit :limit", parameters, (rs, row) -> rs.getObject("id", UUID.class)))
                .containsExactly(third, second);
        assertThat(jdbc.queryForObject(source.countQuery(), parameters, Long.class)).isEqualTo(3);
        parameters.addValue("oldest", true);
        assertThat(jdbc.query(source.value() + " limit :limit", parameters, (rs, row) -> rs.getObject("id", UUID.class)))
                .containsExactly(first, second);

        UUID profile = UUID.randomUUID();
        jdbc.update("""
                insert into tbl_overthinking_profile_share(id,listener_profile_id,owner_user_id,source_post_id,published_at)
                select id,:profile,:owner,id,created_at at time zone 'UTC' from tbl_overthinking_post
                """, Map.of("profile", profile, "owner", author));
        Query shares = OverthinkingProfileShareRepository.class.getMethod("findMainstageByProfile",
                UUID.class, UUID.class, Pageable.class).getAnnotation(Query.class);
        var shareParameters = Map.of("profileId", profile, "ownerId", author, "limit", 2);
        assertThat(jdbc.query(shares.value() + " limit :limit", shareParameters, (rs, row) -> rs.getObject("source_post_id", UUID.class)))
                .containsExactly(third, second);
        assertThat(jdbc.queryForObject(shares.countQuery(), shareParameters, Long.class)).isEqualTo(3);
    }

    @Test void migrationPreservesLegacyMusicAndPrivacyWhileCanonicallyMovingStudioMedia() throws Exception {
        UUID music = UUID.randomUUID(), studio = UUID.randomUUID();
        jdbc.update("""
                insert into tbl_media_asset(id,owner_type,visibility) values
                (:music,'MUSICIAN_PROFILE','PRIVATE'),(:studio,'STUDIO_PROFILE','PUBLIC')
                """, Map.of("music", music, "studio", studio));
        String migration = Files.readString(Path.of("scripts/db/2026-09-14-mainstage-content-audience.sql"));
        jdbc.getJdbcTemplate().execute(migration);
        jdbc.getJdbcTemplate().execute(migration);
        assertThat(jdbc.queryForMap("select content_audience,visibility from tbl_media_asset where id=:id", Map.of("id", music)))
                .containsEntry("content_audience", "MAINSTAGE").containsEntry("visibility", "PRIVATE");
        assertThat(jdbc.queryForMap("select content_audience,visibility from tbl_media_asset where id=:id", Map.of("id", studio)))
                .containsEntry("content_audience", "BACKSTAGE").containsEntry("visibility", "PUBLIC");
        assertThat(jdbc.getJdbcTemplate().queryForObject("select count(*) from soundconnect_schema_migrations", Integer.class))
                .isEqualTo(1);
    }

    private List<UUID> visible() {
        return jdbc.getJdbcTemplate().query("select post.id from tbl_overthinking_post post where "
                + OverthinkingMainstageVisibility.SQL, (rs, row) -> rs.getObject(1, UUID.class));
    }
    private UUID track(String audience, String ownerType) {
        UUID id = UUID.randomUUID(), media = UUID.randomUUID();
        jdbc.update("insert into tbl_media_asset(id,owner_type,content_audience) values(:id,:owner,:audience)",
                Map.of("id", media, "owner", ownerType, "audience", audience));
        jdbc.update("insert into tbl_tracks values(:id,:media)", Map.of("id", id, "media", media));
        return id;
    }
    private UUID post(UUID owner, UUID musicianTrack, UUID bandTrack, int seconds) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into tbl_overthinking_post(id,author_id,musician_track_id,band_track_id,visibility_type,title,created_at)
                values(:id,:author,:musician,:band,'ANONYMOUS','Retained public text',timestamp '2026-09-14' + :seconds * interval '1 second')
                """, new MapSqlParameterSource().addValue("id", id).addValue("author", owner)
                .addValue("musician", musicianTrack).addValue("band", bandTrack).addValue("seconds", seconds));
        return id;
    }
}

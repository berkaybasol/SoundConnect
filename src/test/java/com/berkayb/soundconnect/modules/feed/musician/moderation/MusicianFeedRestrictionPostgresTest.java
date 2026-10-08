package com.berkayb.soundconnect.modules.feed.musician.moderation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class MusicianFeedRestrictionPostgresTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine")
            .withDatabaseName("musician_restrictions").withUsername("soundconnect").withPassword("soundconnect");
    private final ObjectMapper mapper = new ObjectMapper();
    private final Instant now = Instant.parse("2026-09-13T10:00:00Z");
    private MusicianFeedRestrictionRepository repository;
    private MusicianFeedModerationPolicy policy;
    private JdbcTemplate sql;

    @BeforeEach
    void schema() {
        var dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        sql = new JdbcTemplate(dataSource);
        sql.execute("drop schema public cascade; create schema public");
        sql.execute("create table tbl_musician_feed_content_report(id uuid primary key)");
        sql.execute("""
                create table tbl_musician_feed_restriction(report_id uuid primary key,scope_key varchar(320) not null,
                    active boolean not null,orphaned boolean not null default false,applied_by_user_id uuid not null,applied_at timestamptz not null,
                    updated_at timestamptz not null);
                create index idx_musician_feed_restriction_scope on tbl_musician_feed_restriction(scope_key,active,report_id)
                """);
        repository = new MusicianFeedRestrictionRepository(new NamedParameterJdbcTemplate(dataSource));
        policy = new MusicianFeedModerationPolicy(repository, new MusicianFeedModerationScopeResolver(mapper));
    }

    @Test
    void applyAndReactivationMarkMissingReportsWithoutChangingThePersistedScope() {
        UUID report = UUID.randomUUID(), actor = UUID.randomUUID();
        sql.update("insert into tbl_musician_feed_content_report(id) values (?)", report);
        String original = "TARGET:MEDIA:" + UUID.randomUUID();
        repository.apply(report, original, actor, now);
        assertThat(sql.queryForObject("select orphaned from tbl_musician_feed_restriction where report_id=?", Boolean.class, report))
                .isFalse();
        repository.restore(report, now.plusSeconds(1));
        sql.update("delete from tbl_musician_feed_content_report where id=?", report);
        repository.apply(report, "TARGET:MEDIA:" + UUID.randomUUID(), actor, now.plusSeconds(2));
        assertThat(sql.queryForObject("select orphaned from tbl_musician_feed_restriction where report_id=?", Boolean.class, report))
                .isTrue();
        assertThat(repository.byReport(report).orElseThrow().scopeKey()).isEqualTo(original);
    }

    @Test
    void restoringOneReportCannotUndoAnotherReportAndUsesThePersistedScope() {
        UUID media = UUID.randomUUID(), actor = UUID.randomUUID();
        var first = subject(UUID.randomUUID(), media);
        var second = subject(UUID.randomUUID(), media);
        policy.remove(first, actor, now);
        policy.remove(second, actor, now);
        assertThat(policy.isRestricted(first)).isTrue();
        policy.restore(first, actor, now.plusSeconds(1));
        assertThat(policy.isRestricted(first)).isFalse();
        assertThat(policy.isSubjectRestricted(first)).isTrue();
        assertThat(repository.activeScopes(List.of("TARGET:MEDIA:" + media))).containsExactly("TARGET:MEDIA:" + media);
        // Even if a caller supplies changed or no longer resolvable source data,
        // restore only toggles the original report restriction.
        var changed = subject(second.reportId(), UUID.randomUUID());
        policy.restore(changed, actor, now.plusSeconds(2));
        assertThat(policy.isSubjectRestricted(first)).isFalse();
        policy.remove(changed, actor, now.plusSeconds(3));
        assertThat(repository.byReport(second.reportId()).orElseThrow().scopeKey()).isEqualTo("TARGET:MEDIA:" + media);
    }

    @Test
    void restrictionLookupIsBoundedByRequestedScopesAndInactiveRowsDoNotBlock() {
        sql.update("""
                insert into tbl_musician_feed_restriction(report_id,scope_key,active,applied_by_user_id,applied_at,updated_at)
                select gen_random_uuid(),'ITEM:ACTIVITY_FOLLOW:MUSICIAN:'||n,(n%2=1),?,now(),now()
                from generate_series(0,509) n
                """, UUID.randomUUID());
        List<String> requested = java.util.stream.IntStream.range(0, 510)
                .mapToObj(index -> "ITEM:ACTIVITY_FOLLOW:MUSICIAN:" + index).toList();
        assertThat(repository.activeScopes(requested)).hasSize(255)
                .contains("ITEM:ACTIVITY_FOLLOW:MUSICIAN:509")
                .doesNotContain("ITEM:ACTIVITY_FOLLOW:MUSICIAN:508");
        assertThat(repository.activeScopes(List.of("TARGET:MEDIA:" + UUID.randomUUID()))).isEmpty();
    }

    private MusicianFeedModerationSubject subject(UUID reportId, UUID media) {
        return new MusicianFeedModerationSubject(reportId, "TRACK:" + UUID.randomUUID(), "TRACK", "MEDIA", media,
                mapper.createObjectNode());
    }
}

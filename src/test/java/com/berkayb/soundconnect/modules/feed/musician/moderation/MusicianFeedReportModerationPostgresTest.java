package com.berkayb.soundconnect.modules.feed.musician.moderation;

import com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedItemType;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Testcontainers(disabledWithoutDocker = true)
@Timeout(30)
class MusicianFeedReportModerationPostgresTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine")
            .withDatabaseName("musician_moderation").withUsername("soundconnect").withPassword("soundconnect");
    private static final Instant NOW = Instant.parse("2026-09-13T12:00:00Z");
    private final UUID reporter = UUID.randomUUID();
    private final UUID admin = UUID.randomUUID();
    private final UUID otherAdmin = UUID.randomUUID();
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private JdbcTemplate sql;
    private TransactionTemplate tx;
    private MusicianFeedReportModerationRepository reports;
    private MusicianFeedReportAuditRepository audits;
    private MusicianFeedModerationPolicy policy;
    private MusicianFeedReportModerationService service;

    @BeforeEach
    void schema() throws Exception {
        var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        sql = new JdbcTemplate(source);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        sql.execute("drop schema public cascade; create schema public");
        sql.execute("create table tbl_user(id uuid primary key)");
        sql.update("insert into tbl_user(id) values (?),(?),(?)", reporter, admin, otherAdmin);
        sql.execute(deliveryMigration());
        sql.execute(migration());
        var jdbc = new NamedParameterJdbcTemplate(source);
        reports = new MusicianFeedReportModerationRepository(jdbc, mapper);
        audits = new MusicianFeedReportAuditRepository(jdbc);
        policy = new MusicianFeedModerationPolicy(new MusicianFeedRestrictionRepository(jdbc),
                new MusicianFeedModerationScopeResolver(mapper));
        service = service(policy);
    }

    @Test
    void keysetSurvivesEarlierRowsLeavingQueueAndExcludesNewReportsAndDifferentTypes() {
        List<UUID> ids = new ArrayList<>();
        for (int index = 0; index < 5; index++) ids.add(report(UUID.randomUUID(), "TRACK", NOW.minusSeconds(10), "{}"));
        report(UUID.randomUUID(), "EVENT", NOW.minusSeconds(10), "{}");
        var first = service.list(admin, null, MusicianFeedItemType.TRACK, 2, null);
        assertThat(first.items()).hasSize(2);
        assertThat(first.hasMore()).isTrue();
        review(first.items().getFirst().id(), command(0, MusicianFeedReportDecision.DISMISS));
        sql.update("delete from tbl_musician_feed_content_report where id=?", first.items().getLast().id());
        UUID afterAnchor = report(UUID.randomUUID(), "TRACK", NOW.plusSeconds(1), "{}");

        var second = service.list(admin, MusicianFeedReportStatus.NEW, MusicianFeedItemType.TRACK, 2, first.nextCursor());
        var third = service.list(admin, MusicianFeedReportStatus.NEW, MusicianFeedItemType.TRACK, 2, second.nextCursor());
        Set<UUID> observed = new HashSet<>();
        for (var page : List.of(first, second, third)) {
            for (var row : page.items()) assertThat(observed.add(row.id())).isTrue();
        }
        assertThat(observed).containsExactlyInAnyOrderElementsOf(ids).doesNotContain(afterAnchor);
        assertThat(third.hasMore()).isFalse();
        assertThat(third.nextCursor()).isNull();
    }

    @Test
    void evidenceOmissionAndDeletedDeliveryRemainReadableWithBoundedSummaryProjection() throws Exception {
        String evidence = """
                {"author":{"displayName":"Rapor anındaki isim"},
                 "payload":{"omitted":true,"reason":"MAX_EVIDENCE_BYTES"}}
                """;
        UUID id = report(UUID.randomUUID(), "TRACK", NOW.minusSeconds(1), evidence);
        assertThat(sql.queryForObject("select delivery_id from tbl_musician_feed_content_report where id=?",
                UUID.class, id)).isNull();
        var detail = service.detail(admin, id);
        assertThat(detail.evidence().path("payload").path("omitted").asBoolean()).isTrue();
        assertThat(detail.report().authorDisplayName()).isEqualTo("Rapor anındaki isim");
        assertThat(detail.allowedDecisions()).contains(MusicianFeedReportDecision.REMOVE_FROM_FEED);
        var page = service.list(admin, null, null, null, null);
        String wire = mapper.writeValueAsString(page);
        assertThat(wire).doesNotContain("evidence", "payload", "reporterUserId", "history");

        UUID titled = report(UUID.randomUUID(), "TRACK", NOW, """
                {"payload":{"targetPayload":{"event":{"title":"%s"}}}}
                """.formatted("x".repeat(1000)));
        assertThat(service.detail(admin, titled).report().title()).hasSize(160);

        Map<UUID, String> expectedTitles = new HashMap<>();
        for (boolean activity : List.of(false, true)) {
            for (String field : List.of("title", "content", "description")) {
                String sourceText = field + " " + "kaydedilen içerik ".repeat(30);
                Map<String, Object> share = Map.of("source", Map.of(field, sourceText));
                Object payload = activity ? Map.of("targetPayload", share) : share;
                String type = activity ? "ACTIVITY_LIKE" : "description".equals(field)
                        ? "TABLEGROUP_PROFILE_SHARE" : "OVERTHINKING_PROFILE_SHARE";
                UUID shared = report(UUID.randomUUID(), type, NOW,
                        mapper.writeValueAsString(Map.of("payload", payload)));
                expectedTitles.put(shared, sourceText.substring(0, 160));
            }
        }
        var summaryPage = service.list(admin, null, null, null, null);
        assertThat(summaryPage.items()).filteredOn(row -> expectedTitles.containsKey(row.id()))
                .hasSize(6).allSatisfy(row -> assertThat(row.title()).isEqualTo(expectedTitles.get(row.id())));
        assertThat(mapper.writeValueAsString(summaryPage))
                .doesNotContain("evidence", "payload", "reporterUserId", "history");
    }

    @Test
    void allTransitionsAppendAuditAndRestoreKeepsOtherReportsRestrictionsActive() {
        UUID media = UUID.randomUUID();
        UUID first = report(media, "TRACK", NOW.minusSeconds(2), "{}");
        UUID second = report(media, "TRACK", NOW.minusSeconds(1), "{}");
        var started = review(first, command(0, MusicianFeedReportDecision.START_REVIEW));
        assertThat(started.report().status()).isEqualTo(MusicianFeedReportStatus.REVIEWING);
        assertThat(started.report().version()).isEqualTo(1);
        review(first, command(1, MusicianFeedReportDecision.REMOVE_FROM_FEED));
        review(second, command(0, MusicianFeedReportDecision.REMOVE_FROM_FEED));
        var restored = review(first, command(2, MusicianFeedReportDecision.RESTORE_TO_FEED));

        assertThat(restored.report().status()).isEqualTo(MusicianFeedReportStatus.RESTORED);
        assertThat(restored.report().version()).isEqualTo(3);
        assertThat(restored.activeRestriction()).isTrue();
        assertThat(restored.allowedDecisions()).isEmpty();
        assertThat(restored.history()).extracting(MusicianFeedReportHistoryEntry::decision).containsExactly(
                MusicianFeedReportDecision.START_REVIEW, MusicianFeedReportDecision.REMOVE_FROM_FEED,
                MusicianFeedReportDecision.RESTORE_TO_FEED);
        assertThat(restored.history()).allSatisfy(entry -> {
            assertThat(entry.actorUserId()).isEqualTo(admin);
            assertThat(entry.occurredAt()).isEqualTo(NOW);
            assertThat(entry.resolutionNote()).isEqualTo("İnceleme açıklaması.");
        });
        assertThat(policy.isRestricted(reports.find(first, false).orElseThrow().subject())).isFalse();
        assertThat(policy.isRestricted(reports.find(second, false).orElseThrow().subject())).isTrue();
    }

    @Test
    void requestIdentityReplaysExactlyButRejectsChangedPayloadActorOrStaleNewRequest() {
        UUID id = report(UUID.randomUUID(), "TRACK", NOW, "{}");
        var request = command(0, MusicianFeedReportDecision.DISMISS);
        var original = review(id, request);
        var retry = review(id, new MusicianFeedReportReviewRequest(request.clientRequestId(), 0L,
                request.decision(), "  İnceleme açıklaması.  "));
        assertThat(retry).isEqualTo(original);
        assertThat(count("tbl_musician_feed_report_audit")).isEqualTo(1);
        conflict(() -> review(id, new MusicianFeedReportReviewRequest(request.clientRequestId(), 0L,
                MusicianFeedReportDecision.START_REVIEW, request.resolutionNote())));
        conflict(() -> review(id, new MusicianFeedReportReviewRequest(request.clientRequestId(), 0L,
                request.decision(), "Farklı karar açıklaması.")));
        conflict(() -> tx.execute(status -> service.review(otherAdmin, id, request)));
        conflict(() -> review(id, command(0, MusicianFeedReportDecision.DISMISS)));
        conflict(() -> review(id, command(1, MusicianFeedReportDecision.START_REVIEW)));
        assertThat(count("tbl_musician_feed_report_audit")).isEqualTo(1);
    }

    @Test
    void unsupportedLegacyScopeCanBeDismissedWithoutInventingAnEnforcementTarget() {
        UUID id = report(UUID.randomUUID(), "PROFILE", NOW, "{\"payload\":{\"omitted\":true}}");
        sql.update("update tbl_musician_feed_content_report set target_type='PROFILE' where id=?", id);
        var before = service.detail(admin, id);
        assertThat(before.scopeDescription()).isNull();
        assertThat(before.allowedDecisions()).containsExactly(
                MusicianFeedReportDecision.START_REVIEW, MusicianFeedReportDecision.DISMISS);
        conflict(() -> review(id, command(0, MusicianFeedReportDecision.REMOVE_FROM_FEED)));
        var dismissed = review(id, command(0, MusicianFeedReportDecision.DISMISS));
        assertThat(dismissed.report().status()).isEqualTo(MusicianFeedReportStatus.DISMISSED);
        assertThat(count("tbl_musician_feed_restriction")).isZero();
    }

    @Test
    void concurrentAdminsWithSameExpectedVersionProduceOnlyOneTransition() throws Exception {
        UUID id = report(UUID.randomUUID(), "TRACK", NOW, "{}");
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> work = new ArrayList<>();
            for (UUID actor : List.of(admin, otherAdmin)) work.add(executor.submit(() -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Start timeout");
                try {
                    tx.execute(status -> service.review(actor, id, command(0, MusicianFeedReportDecision.START_REVIEW)));
                    return true;
                } catch (SoundConnectException failure) {
                    assertThat(failure.getErrorType()).isEqualTo(ErrorType.MUSICIAN_FEED_REPORT_CONFLICT);
                    return false;
                }
            }));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Boolean> accepted = List.of(work.get(0).get(10, TimeUnit.SECONDS), work.get(1).get(10, TimeUnit.SECONDS));
            assertThat(accepted).containsExactlyInAnyOrder(true, false);
        } finally { start.countDown(); executor.shutdownNow(); }
        assertThat(service.detail(admin, id).report().version()).isEqualTo(1);
        assertThat(count("tbl_musician_feed_report_audit")).isEqualTo(1);
    }

    @Test
    void policyFailureRollsBackRestrictionReportAndAuditTogether() {
        UUID id = report(UUID.randomUUID(), "TRACK", NOW, "{}");
        MusicianFeedModerationPolicy failing = spy(policy);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException("Simulated adapter failure after restriction write");
        }).when(failing).remove(any(), any(), any());
        var failedService = service(failing);
        assertThatThrownBy(() -> tx.execute(status -> failedService.review(admin, id,
                command(0, MusicianFeedReportDecision.REMOVE_FROM_FEED)))).isInstanceOf(IllegalStateException.class);
        assertThat(service.detail(admin, id).report().status()).isEqualTo(MusicianFeedReportStatus.NEW);
        assertThat(service.detail(admin, id).report().version()).isZero();
        assertThat(count("tbl_musician_feed_restriction")).isZero();
        assertThat(count("tbl_musician_feed_report_audit")).isZero();
    }

    @Test
    void reporterErasureKeepsRestrictionWithoutChangingExistingReportCascadePolicy() {
        UUID id = report(UUID.randomUUID(), "TRACK", NOW, "{}");
        review(id, command(0, MusicianFeedReportDecision.REMOVE_FROM_FEED));
        assertThat(sql.queryForObject("select orphaned from tbl_musician_feed_restriction where report_id=?",
                Boolean.class, id)).isFalse();
        sql.update("delete from tbl_user where id=?", reporter);
        assertThat(count("tbl_musician_feed_content_report")).isZero();
        assertThat(count("tbl_musician_feed_report_audit")).isZero();
        assertThat(sql.queryForObject("select active from tbl_musician_feed_restriction where report_id=?",
                Boolean.class, id)).isTrue();
        assertThat(sql.queryForObject("select orphaned from tbl_musician_feed_restriction where report_id=?",
                Boolean.class, id)).isTrue();
    }

    @Test
    void orphanMarkerMigrationBackfillsExistingRowsAndRemainsIdempotent() throws Exception {
        UUID absentReport = UUID.randomUUID();
        sql.update("""
                insert into tbl_musician_feed_restriction(report_id,scope_key,active,applied_by_user_id,applied_at,updated_at)
                values (?, ?, true, ?, ?, ?)
                """, absentReport, "MEDIA:" + UUID.randomUUID(), admin, Timestamp.from(NOW), Timestamp.from(NOW));
        assertThat(sql.queryForObject("select orphaned from tbl_musician_feed_restriction where report_id=?",
                Boolean.class, absentReport)).isFalse();
        sql.execute(migration());
        sql.execute(migration());
        assertThat(sql.queryForObject("select orphaned from tbl_musician_feed_restriction where report_id=?",
                Boolean.class, absentReport)).isTrue();
        assertThat(sql.queryForObject("""
                select count(*) from pg_trigger where tgrelid='tbl_musician_feed_content_report'::regclass
                    and tgname='trg_musician_feed_report_restriction_orphaned'
                """, Long.class)).isEqualTo(1);
        assertThat(sql.queryForObject("""
                select indexdef from pg_indexes where schemaname='public'
                    and indexname='idx_musician_feed_restriction_orphan_queue'
                """, String.class)).contains("WHERE orphaned");
    }

    @Test
    void orderedMigrationRerunPreservesRestoredRowsAndChecksReviewMetadata() throws Exception {
        UUID id = report(UUID.randomUUID(), "TRACK", NOW, "{}");
        review(id, command(0, MusicianFeedReportDecision.REMOVE_FROM_FEED));
        review(id, command(1, MusicianFeedReportDecision.RESTORE_TO_FEED));
        var original = service.detail(admin, id);
        sql.execute(deliveryMigration());
        sql.execute(migration());
        sql.execute(migration());
        assertThat(service.detail(admin, id)).isEqualTo(original);
        assertThat(sql.queryForObject("select count(*) from soundconnect_schema_migrations where migration_id=?",
                Long.class, "2026-09-13-musician-feed-moderation")).isEqualTo(1);
        assertThatThrownBy(() -> sql.update("update tbl_musician_feed_content_report set review_decision=null where id=?", id))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void legacyRowsBackfillVersionAndPermissionsGrantOnlyAdministratorsAndOwners() throws Exception {
        sql.execute("drop table tbl_musician_feed_report_audit");
        sql.execute("alter table tbl_musician_feed_content_report drop constraint ck_musician_feed_report_review_state");
        sql.execute("alter table tbl_musician_feed_content_report drop column version");
        UUID id = report(UUID.randomUUID(), "TRACK", NOW, "{\"payload\":{\"omitted\":true}}");
        sql.execute("create table tbl_permissions(id uuid primary key,name varchar(100) unique,created_at timestamp,updated_at timestamp)");
        sql.execute("create table tbl_role(id uuid primary key,name varchar(100) unique)");
        sql.execute("create table role_permissions(role_id uuid,permission_id uuid,primary key(role_id,permission_id))");
        for (String role : List.of("ROLE_ADMIN", "ROLE_OWNER", "ROLE_MUSICIAN")) {
            sql.update("insert into tbl_role(id,name) values (?,?)", UUID.randomUUID(), role);
        }
        sql.execute(migration());
        sql.execute(migration());
        var legacy = service.detail(admin, id);
        assertThat(legacy.report().version()).isZero();
        assertThat(legacy.report().status()).isEqualTo(MusicianFeedReportStatus.NEW);
        assertThat(legacy.evidence().path("payload").path("omitted").asBoolean()).isTrue();
        assertThat(sql.queryForList("select role.name from tbl_role role join role_permissions grants on grants.role_id=role.id",
                String.class)).containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_OWNER");
    }

    private MusicianFeedReportModerationService service(MusicianFeedModerationPolicy chosenPolicy) {
        return new MusicianFeedReportModerationService(reports, audits, chosenPolicy,
                new MusicianFeedReportCursorCodec(mapper), Clock.fixed(NOW, ZoneOffset.UTC));
    }
    private MusicianFeedReportDetail review(UUID id, MusicianFeedReportReviewRequest request) {
        return tx.execute(status -> service.review(admin, id, request));
    }
    private MusicianFeedReportReviewRequest command(long version, MusicianFeedReportDecision decision) {
        return new MusicianFeedReportReviewRequest(UUID.randomUUID(), version, decision, "İnceleme açıklaması.");
    }
    private UUID report(UUID target, String type, Instant time, String evidence) {
        UUID id = UUID.randomUUID();
        sql.update("""
                insert into tbl_musician_feed_content_report(id,viewer_user_id,item_id,item_type,target_type,
                    target_id,evidence_json,status,reported_at)
                values (?,?,?,?,?,?,cast(? as jsonb),'NEW',?)
                """, id, reporter, type + ":" + UUID.randomUUID(), type, "EVENT".equals(type) ? "EVENT" : "MEDIA",
                target, evidence, Timestamp.from(time));
        return id;
    }
    private long count(String table) { return Objects.requireNonNull(sql.queryForObject("select count(*) from " + table, Long.class)); }
    private String migration() throws Exception { return Files.readString(Path.of("scripts/db/2026-09-13-musician-feed-moderation.sql")); }
    private String deliveryMigration() throws Exception { return Files.readString(Path.of("scripts/db/2026-09-11-musician-feed-delivery.sql")); }
    private void conflict(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(SoundConnectException.class,
                failure -> assertThat(failure.getErrorType()).isEqualTo(ErrorType.MUSICIAN_FEED_REPORT_CONFLICT));
    }
}

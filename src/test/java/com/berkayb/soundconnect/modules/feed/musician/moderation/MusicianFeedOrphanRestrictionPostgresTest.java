package com.berkayb.soundconnect.modules.feed.musician.moderation;

import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.dao.DataIntegrityViolationException;
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
import static com.berkayb.soundconnect.modules.feed.musician.moderation.MusicianFeedOrphanRestrictionModels.*;

@Testcontainers(disabledWithoutDocker = true)
@Timeout(30)
class MusicianFeedOrphanRestrictionPostgresTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine")
            .withDatabaseName("musician_orphan_restrictions").withUsername("soundconnect").withPassword("soundconnect");
    private static final Instant NOW = Instant.parse("2026-09-13T15:30:00.123456Z");
    private static final Instant APPLIED = NOW.minusSeconds(60);
    private final UUID actor = UUID.randomUUID();
    private final UUID reporter = UUID.randomUUID();
    private final UUID otherReporter = UUID.randomUUID();
    private final String scope = "TARGET:MEDIA:" + UUID.randomUUID();
    private JdbcTemplate sql;
    private TransactionTemplate tx;
    private MusicianFeedRestrictionRepository restrictions;
    private MusicianFeedOrphanRestrictionService service;
    private MusicianFeedOrphanRestrictionCursor cursors;

    @BeforeEach
    void setUp() throws Exception {
        var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        sql = new JdbcTemplate(source);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        sql.execute("drop schema public cascade; create schema public");
        sql.execute("create table tbl_user(id uuid primary key)");
        sql.update("insert into tbl_user(id) values (?),(?),(?)", actor, reporter, otherReporter);
        sql.execute(Files.readString(Path.of("scripts/db/2026-09-11-musician-feed-delivery.sql")));
        sql.execute(Files.readString(Path.of("scripts/db/2026-09-13-musician-feed-moderation.sql")));
        var jdbc = new NamedParameterJdbcTemplate(source);
        var mapper = new ObjectMapper().findAndRegisterModules();
        restrictions = new MusicianFeedRestrictionRepository(jdbc);
        cursors = new MusicianFeedOrphanRestrictionCursor(mapper);
        service = new MusicianFeedOrphanRestrictionService(new MusicianFeedOrphanRestrictionRepository(jdbc),
                restrictions, new MusicianFeedModerationScopeResolver(mapper), cursors, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void erasedReporterRestrictionRemainsManageableAndOtherReportsStillApply() {
        UUID first = report(reporter);
        UUID other = report(otherReporter);
        restrictions.apply(first, scope, actor, APPLIED);
        restrictions.apply(other, scope, actor, APPLIED);
        sql.update("delete from tbl_user where id=?", reporter);
        assertThat(sql.queryForObject("select count(*) from tbl_musician_feed_content_report where id=?", Long.class, first)).isZero();

        var queue = service.list(actor, 20, null);
        assertThat(queue.items()).extracting(Item::reportId).containsExactly(first);
        assertThat(queue.items().getFirst().scopeKey()).isEqualTo(scope);
        assertThat(queue.items().getFirst().appliedByUserId()).isEqualTo(actor);
        assertConflict(() -> restore(other, command())); // A surviving report requires its versioned review path.
        var request = command();
        var result = restore(first, request);
        assertThat(result.active()).isFalse();
        assertThat(result.activeRestriction()).isTrue();
        assertThat(result.updatedAt()).isEqualTo(NOW);
        assertThat(restore(first, request)).isEqualTo(result);
        assertThat(auditCount()).isEqualTo(1);
        assertConflict(() -> restore(first, command()));
        assertThat(service.list(actor, 20, null).items()).isEmpty();

        sql.update("delete from tbl_user where id=?", otherReporter);
        assertThat(restore(other, command()).activeRestriction()).isFalse();
        assertThat(restrictions.activeScopes(List.of(scope))).isEmpty();
    }

    @Test
    void keysetKeepsTiedRowsWhenBoundaryIsRestoredAndExcludesNewRestrictions() {
        Set<UUID> ids = new HashSet<>();
        for (int index = 0; index < 5; index++) ids.add(orphan(APPLIED));
        UUID surviving = report(otherReporter);
        restrictions.apply(surviving, scope, actor, APPLIED);
        orphan(NOW.plusSeconds(1));
        var first = service.list(actor, 2, null);
        first.items().forEach(item -> restore(item.reportId(), command()));
        var second = service.list(actor, 2, first.nextCursor());
        var third = service.list(actor, 2, second.nextCursor());
        List<UUID> observed = new ArrayList<>();
        for (var page : List.of(first, second, third)) page.items().forEach(item -> observed.add(item.reportId()));
        assertThat(observed).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(ids);
        assertThat(third.hasMore()).isFalse();
        assertThat(third.nextCursor()).isNull();
    }

    @Test
    void staleTimestampAndChangedReplayPayloadDoNotModifyRestrictionOrAudit() {
        UUID id = orphan(APPLIED);
        assertConflict(() -> restore(id, new RestoreRequest(UUID.randomUUID(), APPLIED.minusSeconds(1), "Eski karar sürümü.")));
        assertThat(restrictions.byReport(id).orElseThrow().active()).isTrue();
        assertThat(auditCount()).isZero();
        var request = command();
        restore(id, request);
        assertConflict(() -> restore(id, new RestoreRequest(request.clientRequestId(), APPLIED, "Farklı bir açıklama.")));
        assertConflict(() -> tx.execute(status -> service.restore(UUID.randomUUID(), id, request)));
        assertThat(auditCount()).isEqualTo(1);
        assertThat(sql.queryForObject("select resolution_note from tbl_musician_feed_restriction_restore_audit", String.class))
                .isEqualTo(request.resolutionNote());
    }

    @Test
    void simultaneousRestorationsCommitOneAuditAndOneDecision() throws Exception {
        UUID id = orphan(APPLIED);
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Boolean> attempt = () -> {
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                try { restore(id, command()); return true; }
                catch (SoundConnectException error) {
                    assertThat(error.getErrorType()).isEqualTo(ErrorType.MUSICIAN_FEED_REPORT_CONFLICT);
                    return false;
                }
            };
            var first = pool.submit(attempt);
            var second = pool.submit(attempt);
            start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(auditCount()).isEqualTo(1);
        assertThat(restrictions.byReport(id).orElseThrow().active()).isFalse();
    }

    @Test
    void auditWriteFailureRollsBackRestrictionAndCanBeRetried() {
        UUID id = orphan(APPLIED);
        var request = command();
        sql.execute("alter table tbl_musician_feed_restriction_restore_audit add constraint test_restore_failure check (false)");
        assertThatThrownBy(() -> restore(id, request)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(restrictions.byReport(id).orElseThrow().active()).isTrue();
        assertThat(auditCount()).isZero();
        sql.execute("alter table tbl_musician_feed_restriction_restore_audit drop constraint test_restore_failure");
        assertThat(restore(id, request).active()).isFalse();
        assertThat(auditCount()).isEqualTo(1);
    }

    @Test
    void invalidInputsAndCrossAccountOrOutOfRangeCursorsAreRejected() {
        orphan(APPLIED);
        orphan(APPLIED);
        var first = service.list(actor, 1, null);
        assertThatThrownBy(() -> service.list(null, 1, null)).isInstanceOfSatisfying(SoundConnectException.class,
                error -> assertThat(error.getErrorType()).isEqualTo(ErrorType.UNAUTHORIZED));
        for (int limit : List.of(0, 51)) assertThatThrownBy(() -> service.list(actor, limit, null))
                .isInstanceOfSatisfying(SoundConnectException.class, error -> assertThat(error.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST));
        assertCursorInvalid(() -> service.list(UUID.randomUUID(), 1, first.nextCursor()));
        for (String cursor : List.of("", "x".repeat(1025), "invalid!"))
            assertCursorInvalid(() -> service.list(actor, 1, cursor));
        String outOfRange = cursors.encode(actor,
                new MusicianFeedOrphanRestrictionCursor.Position(NOW, Instant.MIN, UUID.randomUUID()));
        assertCursorInvalid(() -> service.list(actor, 1, outOfRange));
        String expired = cursors.encode(actor,
                new MusicianFeedOrphanRestrictionCursor.Position(NOW.minusSeconds(86401), NOW.minusSeconds(86402), UUID.randomUUID()));
        assertCursorInvalid(() -> service.list(actor, 1, expired));
    }

    private UUID orphan(Instant at) {
        UUID id = UUID.randomUUID();
        restrictions.apply(id, scope, actor, at);
        return id;
    }

    private UUID report(UUID owner) {
        UUID id = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        sql.update("""
                insert into tbl_musician_feed_content_report(id,viewer_user_id,item_id,item_type,target_type,
                    target_id,reason,evidence_json,status,reported_at)
                values (?,?,?,'TRACK','MEDIA',?,'Erased reporter note','{}'::jsonb,'NEW',?)
                """, id, owner, "TRACK:" + target, target, Timestamp.from(APPLIED));
        return id;
    }

    private RestoreRequest command() { return new RestoreRequest(UUID.randomUUID(), APPLIED, "Kısıtlama yeniden incelendi."); }
    private Restored restore(UUID id, RestoreRequest request) {
        return tx.execute(status -> {
            sql.execute("set local lock_timeout='5s'");
            return service.restore(actor, id, request);
        });
    }
    private long auditCount() { return sql.queryForObject("select count(*) from tbl_musician_feed_restriction_restore_audit", Long.class); }
    private void assertConflict(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(SoundConnectException.class,
                error -> assertThat(error.getErrorType()).isEqualTo(ErrorType.MUSICIAN_FEED_REPORT_CONFLICT));
    }
    private void assertCursorInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(SoundConnectException.class,
                error -> assertThat(error.getErrorType()).isEqualTo(ErrorType.MUSICIAN_FEED_REPORT_CURSOR_INVALID));
    }
}

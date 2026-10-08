package com.berkayb.soundconnect.modules.feed.musician.feedback;

import com.berkayb.soundconnect.modules.collab.service.CollabService;
import com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedFeedbackAction;
import com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedFeedbackRequest;
import com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedFeedbackResponse;
import com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedItemType;
import com.berkayb.soundconnect.modules.feed.musician.core.MusicianFeedViewerGuard;
import com.berkayb.soundconnect.modules.feed.musician.delivery.MusicianFeedDeliveredItem;
import com.berkayb.soundconnect.modules.feed.musician.delivery.MusicianFeedDeliveryService;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DataJpaTest(properties = {
        "spring.config.location=classpath:/application-test.yml", "spring.config.import=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
        "spring.jpa.properties.hibernate.hbm2ddl.halt_on_error=true"
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
// Commit fixtures and run every write in its own real transaction. An ambient
// test transaction would hide the fixture from concurrent writers and hold locks.
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MusicianFeedFeedbackCapacityPostgresTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine")
            .withDatabaseName("musician_feedback_capacity")
            .withUsername("soundconnect").withPassword("soundconnect");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    private static final Instant NOW = Instant.parse("2026-09-13T15:20:00.123456Z");
    private static final Instant LEGACY_AT = NOW.minusSeconds(86_400L * 365);
    private static final int LEGACY_PER_ACTION = 1_701;
    private static final int LEGACY_ROWS = LEGACY_PER_ACTION * 3;
    @Autowired DataSource dataSource;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired MusicianFeedFeedbackRepository feedbacks;
    private JdbcTemplate sql;
    private NamedParameterJdbcTemplate jdbc;
    private TransactionTemplate transactions;
    private MusicianFeedDeliveryService deliveries;
    private MusicianFeedFeedbackService service;
    private UUID viewer;
    private String legacyPreferenceFingerprint;
    private String legacyModerationFingerprint;

    @BeforeEach
    void setUp() {
        sql = new JdbcTemplate(dataSource);
        jdbc = new NamedParameterJdbcTemplate(dataSource);
        transactions = new TransactionTemplate(transactionManager);
        viewer = UUID.randomUUID();
        transactions.executeWithoutResult(status -> seedLegacyHistory());
        legacyPreferenceFingerprint = legacyPreferenceFingerprint();
        legacyModerationFingerprint = legacyModerationFingerprint();
        assertThat(feedbackCount()).isEqualTo(LEGACY_ROWS).isGreaterThan(5_000);

        var viewers = mock(MusicianFeedViewerGuard.class);
        when(viewers.requireMusicianProfile(viewer)).thenReturn(UUID.randomUUID());
        var authors = mock(MusicianFeedAuthorProfileGuard.class);
        when(authors.requireEligibleNotOwned(eq(viewer), eq("MUSICIAN"), any(UUID.class)))
                .thenReturn("MUSICIAN");
        deliveries = mock(MusicianFeedDeliveryService.class);
        service = new MusicianFeedFeedbackService(feedbacks, viewers, authors, deliveries,
                new MusicianFeedReportDispatcher(jdbc, mock(CollabService.class)),
                new MusicianFeedFeedbackLock(sql), new MusicianFeedFeedbackReader(jdbc),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void acceptsEveryActionAndItsReplayBeyondFiveThousandWithoutEvictingPermanentHistory() {
        for (var action : List.of(MusicianFeedFeedbackAction.HIDE,
                MusicianFeedFeedbackAction.SHOW_LESS, MusicianFeedFeedbackAction.REPORT)) {
            var delivery = deliveredTrack();
            var response = write(() -> record(delivery, action, "New feedback reason"));
            var replay = write(() -> record(delivery, action, "New feedback reason"));
            assertThat(response.action()).isEqualTo(action);
            assertThat(replay.id()).isEqualTo(response.id());
            assertThat(itemFeedbackCount(delivery.itemId(), action)).isEqualTo(1);
            if (action == MusicianFeedFeedbackAction.REPORT) assertModerationEvidence(delivery);
        }
        UUID author = UUID.randomUUID();
        var mute = write(() -> service.mute(viewer, "MUSICIAN", author));
        var muteReplay = write(() -> service.mute(viewer, "MUSICIAN", author));
        assertThat(muteReplay.id()).isEqualTo(mute.id());
        assertThat(mute.authorProfileId()).isEqualTo(author);
        assertThat(feedbackCount()).isEqualTo(LEGACY_ROWS + 4);
        assertThat(moderationCount()).isEqualTo(LEGACY_PER_ACTION + 1);
        assertLegacyHistoryUnchanged();
    }

    @Test
    void concurrentReportReplayCommitsOnePreferenceAndOneModerationRecordAboveTheFormerLimit() throws Exception {
        var delivery = deliveredTrack();
        var firstWritten = new CountDownLatch(1);
        var releaseFirstCommit = new CountDownLatch(1);
        var secondStarted = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> write(() -> {
                var response = record(delivery, MusicianFeedFeedbackAction.REPORT, "New feedback reason");
                feedbacks.flush();
                firstWritten.countDown();
                await(releaseFirstCommit);
                return response;
            }));
            await(firstWritten);
            var second = executor.submit(() -> write(() -> {
                secondStarted.countDown();
                return record(delivery, MusicianFeedFeedbackAction.REPORT, "New feedback reason");
            }));
            await(secondStarted);
            // The first transaction still owns the real PostgreSQL advisory lock.
            assertThatThrownBy(() -> second.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            releaseFirstCommit.countDown();
            assertThat(second.get(15, TimeUnit.SECONDS).id()).isEqualTo(first.get(15, TimeUnit.SECONDS).id());
        } finally {
            releaseFirstCommit.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(feedbackCount()).isEqualTo(LEGACY_ROWS + 1);
        assertThat(itemFeedbackCount(delivery.itemId(), MusicianFeedFeedbackAction.REPORT)).isEqualTo(1);
        assertThat(moderationCount()).isEqualTo(LEGACY_PER_ACTION + 1);
        assertModerationEvidence(delivery);

        // A conflicting replay cannot rewrite the evidence or the accepted reason.
        assertThatThrownBy(() -> write(() -> record(delivery, MusicianFeedFeedbackAction.REPORT, "Changed reason")))
                .isInstanceOfSatisfying(SoundConnectException.class,
                        failure -> assertThat(failure.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST));
        assertThat(sql.queryForObject("select reason from tbl_musician_feed_feedback where viewer_user_id=? and item_id=?",
                String.class, viewer, delivery.itemId())).isEqualTo("New feedback reason");
        assertModerationEvidence(delivery);
        assertLegacyHistoryUnchanged();
    }

    @Test
    void downstreamPreferenceFailureRollsBackTheRealModerationInsertAndReleasesTheViewerLock() {
        var delivery = deliveredTrack();
        // An actual database rejection after ReportDispatcher's insert exercises
        // the transaction boundary across JDBC moderation and the JPA preference.
        sql.execute("alter table tbl_musician_feed_feedback add constraint test_capacity_feedback_failure "
                + "check (item_id is distinct from '" + delivery.itemId() + "')");
        try {
            assertThatThrownBy(() -> write(() -> {
                var response = record(delivery, MusicianFeedFeedbackAction.REPORT, "New feedback reason");
                assertThat(moderationCount()).isEqualTo(LEGACY_PER_ACTION + 1);
                return response;
            })).isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            sql.execute("alter table tbl_musician_feed_feedback drop constraint test_capacity_feedback_failure");
        }
        assertThat(feedbackCount()).isEqualTo(LEGACY_ROWS);
        assertThat(moderationCount()).isEqualTo(LEGACY_PER_ACTION);
        assertThat(itemFeedbackCount(delivery.itemId(), MusicianFeedFeedbackAction.REPORT)).isZero();
        assertLegacyHistoryUnchanged();

        write(() -> record(delivery, MusicianFeedFeedbackAction.REPORT, "New feedback reason"));
        assertThat(feedbackCount()).isEqualTo(LEGACY_ROWS + 1);
        assertThat(moderationCount()).isEqualTo(LEGACY_PER_ACTION + 1);
        assertModerationEvidence(delivery);
    }

    private MusicianFeedFeedbackResponse record(MusicianFeedDeliveredItem delivery,
                                                MusicianFeedFeedbackAction action, String reason) {
        return service.recordItem(viewer, delivery.itemId(),
                new MusicianFeedFeedbackRequest(action, reason, token(delivery)));
    }

    private <T> T write(Supplier<T> action) {
        return transactions.execute(status -> {
            // Bound failures and lock contention so regressions cannot hang the suite.
            sql.execute("set local lock_timeout='10s'");
            T result = action.get();
            feedbacks.flush();
            return result;
        });
    }

    private MusicianFeedDeliveredItem deliveredTrack() {
        UUID target = UUID.randomUUID();
        String itemId = "TRACK:" + target;
        var delivery = new MusicianFeedDeliveredItem(UUID.randomUUID(), viewer, UUID.randomUUID(),
                itemId, MusicianFeedItemType.TRACK, "MEDIA", target, "MUSICIAN", UUID.randomUUID(),
                "FOLLOWED_MUSICIAN", Set.of(MusicianFeedFeedbackAction.HIDE,
                MusicianFeedFeedbackAction.SHOW_LESS, MusicianFeedFeedbackAction.REPORT),
                1, "musician-feed-v1", 0, null,
                "{\"itemId\":\"" + itemId + "\",\"payload\":{\"title\":\"Preserved delivered evidence\"}}",
                NOW.minusSeconds(60), NOW.plusSeconds(3_600), NOW.plusSeconds(86_400L * 30));
        // Persist the ledger too: the accepted DTO refers to a real viewer-owned
        // delivery, while cryptographic token validation remains a separate test concern.
        transactions.executeWithoutResult(status -> sql.update("""
                insert into tbl_musician_feed_delivery(id,viewer_user_id,feed_session_id,item_id,item_type,
                    feed_lane,target_type,target_id,author_profile_type,author_profile_id,reason_code,
                    feedback_capabilities,schema_version,algorithm_version,absolute_position,evidence_json,
                    delivered_at,expires_at,purge_after)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,cast(? as jsonb),?,?,?)
                """, delivery.deliveryId(), viewer, delivery.feedSessionId(), itemId, delivery.itemType().name(),
                delivery.lane().name(), delivery.targetType(), target, delivery.authorProfileType(), delivery.authorProfileId(),
                delivery.reasonCode(), "HIDE,SHOW_LESS,REPORT", delivery.schemaVersion(), delivery.algorithmVersion(),
                delivery.absolutePosition(), delivery.evidenceJson(), Timestamp.from(delivery.deliveredAt()),
                Timestamp.from(delivery.expiresAt()), Timestamp.from(delivery.purgeAfter())));
        when(deliveries.require(token(delivery), viewer, delivery.itemId(), NOW)).thenReturn(delivery);
        return delivery;
    }

    private String token(MusicianFeedDeliveredItem delivery) { return "test-delivery:" + delivery.deliveryId(); }

    private void seedLegacyHistory() {
        String username = "u" + viewer.toString().replace("-", "").substring(0, 20);
        sql.update("""
                insert into tbl_user(id,created_at,updated_at,public_code,user_name,password,email,status,provider,email_verified)
                values (?,?,?,?,?,?,?,?,?,?)
                """, viewer, Timestamp.from(LEGACY_AT), Timestamp.from(LEGACY_AT),
                "SC-" + viewer.toString().substring(0, 18).toUpperCase(Locale.ROOT),
                username, "unused", username + "@soundconnect.test", "ACTIVE", "LOCAL", true);
        var parameters = new MapSqlParameterSource("viewer", viewer).addValue("count", LEGACY_PER_ACTION)
                .addValue("at", Timestamp.from(LEGACY_AT));
        jdbc.update("""
                insert into tbl_musician_feed_feedback(id,viewer_user_id,action,scope_key,
                    author_profile_type,author_profile_id,created_at,updated_at)
                select gen_random_uuid(),:viewer,'MUTE_AUTHOR','AUTHOR:MUSICIAN:'||author_id,
                    'MUSICIAN',author_id,:at,:at
                from (select gen_random_uuid() author_id from generate_series(1,:count)) authors
                """, parameters);
        for (String action : List.of("HIDE", "REPORT")) {
            jdbc.update("""
                    insert into tbl_musician_feed_feedback(id,viewer_user_id,action,scope_key,
                        item_id,item_type,reason,created_at,updated_at)
                    select gen_random_uuid(),:viewer,:action,'ITEM:TRACK:legacy-'||sequence,
                        'TRACK:legacy-'||sequence,'TRACK','Legacy reason',:at,:at
                    from generate_series(1,:count) sequence
                    """, parameters.addValue("action", action));
        }
        // A cleared delivery_id models reports retained after delivery-ledger cleanup.
        jdbc.update("""
                insert into tbl_musician_feed_content_report(id,viewer_user_id,item_id,item_type,target_type,
                    target_id,reason,evidence_json,status,reported_at)
                select gen_random_uuid(),:viewer,item_id,item_type,'MEDIA',gen_random_uuid(),reason,
                    jsonb_build_object('itemId',item_id,'source','Legacy moderation snapshot'),'NEW',:at
                from tbl_musician_feed_feedback where viewer_user_id=:viewer and action='REPORT'
                """, parameters);
    }

    private long feedbackCount() {
        return sql.queryForObject("select count(*) from tbl_musician_feed_feedback where viewer_user_id=?", Long.class, viewer);
    }

    private long moderationCount() {
        return sql.queryForObject("select count(*) from tbl_musician_feed_content_report where viewer_user_id=?", Long.class, viewer);
    }

    private long itemFeedbackCount(String itemId, MusicianFeedFeedbackAction action) {
        return sql.queryForObject("select count(*) from tbl_musician_feed_feedback where viewer_user_id=? and item_id=? and action=?",
                Long.class, viewer, itemId, action.name());
    }

    private void assertModerationEvidence(MusicianFeedDeliveredItem delivery) {
        var records = sql.queryForList("""
                select item_id,target_id,reason,status,evidence_json->>'itemId' evidence_item,
                    evidence_json->'payload'->>'title' evidence_title
                from tbl_musician_feed_content_report where viewer_user_id=? and delivery_id=?
                """, viewer, delivery.deliveryId());
        assertThat(records).singleElement().satisfies(row -> {
            assertThat(row.get("item_id")).isEqualTo(delivery.itemId());
            assertThat(row.get("target_id")).isEqualTo(delivery.targetId());
            assertThat(row.get("reason")).isEqualTo("New feedback reason");
            assertThat(row.get("status")).isEqualTo("NEW");
            assertThat(row.get("evidence_item")).isEqualTo(delivery.itemId());
            assertThat(row.get("evidence_title")).isEqualTo("Preserved delivered evidence");
        });
    }

    private void assertLegacyHistoryUnchanged() {
        assertThat(sql.queryForObject("select count(*) from tbl_musician_feed_feedback where viewer_user_id=? and created_at=?",
                Long.class, viewer, Timestamp.from(LEGACY_AT))).isEqualTo(LEGACY_ROWS);
        assertThat(legacyPreferenceFingerprint()).isEqualTo(legacyPreferenceFingerprint);
        assertThat(sql.queryForObject("select count(*) from tbl_musician_feed_content_report where viewer_user_id=? and reported_at=?",
                Long.class, viewer, Timestamp.from(LEGACY_AT))).isEqualTo(LEGACY_PER_ACTION);
        assertThat(legacyModerationFingerprint()).isEqualTo(legacyModerationFingerprint);
    }

    private String legacyPreferenceFingerprint() {
        return sql.queryForObject("""
                select md5(coalesce(string_agg(to_jsonb(feedback)::text,'' order by id),''))
                from tbl_musician_feed_feedback feedback where viewer_user_id=? and created_at=?
                """, String.class, viewer, Timestamp.from(LEGACY_AT));
    }

    private String legacyModerationFingerprint() {
        return sql.queryForObject("""
                select md5(coalesce(string_agg(to_jsonb(report)::text,'' order by id),''))
                from tbl_musician_feed_content_report report where viewer_user_id=? and reported_at=?
                """, String.class, viewer, Timestamp.from(LEGACY_AT));
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(15, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while coordinating database writers", interrupted);
        }
    }
}

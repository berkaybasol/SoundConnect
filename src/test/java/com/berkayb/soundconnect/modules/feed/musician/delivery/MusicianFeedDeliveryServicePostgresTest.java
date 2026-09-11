package com.berkayb.soundconnect.modules.feed.musician.delivery;

import com.berkayb.soundconnect.modules.feed.musician.api.*;
import com.berkayb.soundconnect.modules.feed.musician.core.MusicianFeedProperties;
import com.berkayb.soundconnect.modules.feed.musician.core.MusicianFeedViewerGuard;
import com.berkayb.soundconnect.modules.feed.musician.candidate.MusicianFeedLane;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

@Testcontainers(disabledWithoutDocker = true)
class MusicianFeedDeliveryServicePostgresTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine")
            .withDatabaseName("musician_feed_delivery_service")
            .withUsername("soundconnect").withPassword("soundconnect");

    private final UUID viewer = UUID.randomUUID();
    private final UUID session = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-09-11T12:00:00Z");
    private MusicianFeedDeliveryService service;
    private NamedParameterJdbcTemplate jdbc;
    private TransactionTemplate transactions;
    private MusicianFeedProperties properties;

    @BeforeEach
    void setUp() throws Exception {
        execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public; CREATE TABLE tbl_user(id uuid primary key)");
        execute("INSERT INTO tbl_user(id) VALUES ('" + viewer + "')");
        execute(Files.readString(Path.of("scripts/db/2026-09-11-musician-feed-delivery.sql")));
        execute(Files.readString(Path.of("scripts/db/2026-09-11-musician-feed-replay.sql")));
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        properties = new MusicianFeedProperties();
        properties.setDeliverySecret("delivery-service-test-secret-at-least-32-bytes");
        jdbc = new NamedParameterJdbcTemplate(dataSource);
        service = new MusicianFeedDeliveryService(jdbc,
                new MusicianFeedDeliveryTokenCodec(new ObjectMapper().findAndRegisterModules(), properties),
                properties, new ObjectMapper().findAndRegisterModules());
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @Test
    void batchPersistsStablePositionsAndReturnsVerifiableTokens() {
        List<MusicianFeedItemResponse> result = transactions.execute(status -> service.recordPage(
                viewer, session, now, 1, "musician-v1", 0,
                List.of(item("TRACK:one"), item("TRACK:two")), now));

        assertThat(result).extracting(MusicianFeedItemResponse::position).containsExactly(0L, 1L);
        assertThat(result).allMatch(value -> value.impressionToken() != null && !value.impressionToken().isBlank());
        assertThat(number("select count(*) from tbl_musician_feed_delivery")).isEqualTo(2);
        assertThat(service.require(result.getFirst().impressionToken(), viewer, "TRACK:one", now).absolutePosition())
                .isZero();
        assertThat(service.snapshot(viewer, session, now).lastItemLane())
                .isEqualTo(MusicianFeedLane.FOLLOWING);
    }

    @Test
    void snapshotUsesMinimalProjectionAndPreservesAllMixerState() {
        String projection = MusicianFeedDeliveryService.SNAPSHOT_SQL.substring(0,
                MusicianFeedDeliveryService.SNAPSHOT_SQL.toLowerCase(Locale.ROOT).indexOf("from"))
                .toLowerCase(Locale.ROOT);
        assertThat(projection)
                .contains("item_id", "item_type", "feed_lane", "target_type", "target_id",
                        "absolute_position", "campaign_id")
                .doesNotContain("*", "evidence_json", "feedback_capabilities", "author_profile",
                        "reason_code", "schema_version", "algorithm_version", "delivered_at",
                        "expires_at", "purge_after");

        UUID firstMedia = UUID.randomUUID();
        UUID commentMedia = UUID.randomUUID();
        UUID collab = UUID.randomUUID();
        UUID profile = UUID.randomUUID();
        UUID campaign = UUID.randomUUID();
        var promotion = new MusicianFeedItemResponse.Promotion(
                campaign, "Sponsored", "Başvur", "/collab");
        List<MusicianFeedItemResponse> items = List.of(
                item("TRACK:one", MusicianFeedItemType.TRACK, "MEDIA", firstMedia, null),
                item("ACTIVITY_COMMENT:one", MusicianFeedItemType.ACTIVITY_COMMENT,
                        "MEDIA", commentMedia, null),
                item("COLLAB:one", MusicianFeedItemType.COLLAB, "COLLAB", collab, promotion),
                item("PROFILE:one", MusicianFeedItemType.PROFILE, "PROFILE", profile, null));
        transactions.executeWithoutResult(status -> service.recordPage(viewer, session, now, 1,
                "musician-v1", 0, items, List.of(MusicianFeedLane.FOLLOWING,
                        MusicianFeedLane.FOLLOWING, MusicianFeedLane.RELEVANT_OPPORTUNITY,
                        MusicianFeedLane.GENERAL_DISCOVERY), now));

        MusicianFeedDeliverySnapshot snapshot = service.snapshot(viewer, session, now);

        assertThat(snapshot.itemIds()).containsExactlyInAnyOrder(
                "TRACK:one", "ACTIVITY_COMMENT:one", "COLLAB:one", "PROFILE:one");
        assertThat(snapshot.targetKeys()).containsExactlyInAnyOrder(
                MusicianFeedDeliverySnapshot.targetKey("MEDIA", firstMedia),
                MusicianFeedDeliverySnapshot.targetKey("MEDIA", commentMedia),
                MusicianFeedDeliverySnapshot.targetKey("COLLAB", collab),
                MusicianFeedDeliverySnapshot.targetKey("PROFILE", profile));
        assertThat(snapshot.organicTargetKeys())
                .contains(MusicianFeedDeliverySnapshot.targetKey("MEDIA", firstMedia),
                        MusicianFeedDeliverySnapshot.targetKey("COLLAB", collab),
                        MusicianFeedDeliverySnapshot.targetKey("PROFILE", profile))
                .doesNotContain(MusicianFeedDeliverySnapshot.targetKey("MEDIA", commentMedia));
        assertThat(snapshot.promotedTargetKeys())
                .containsExactly(MusicianFeedDeliverySnapshot.targetKey("COLLAB", collab));
        assertThat(snapshot.campaignIds()).contains(campaign);
        assertThat(snapshot.nextAbsolutePosition()).isEqualTo(4);
        assertThat(snapshot.deliveredPromotionCount()).isEqualTo(1);
        assertThat(snapshot.organicCountAtLastPromotion()).isEqualTo(2);
        assertThat(snapshot.lastItemPromoted()).isFalse();
        assertThat(snapshot.lastItemType()).isEqualTo(MusicianFeedItemType.PROFILE);
        assertThat(snapshot.lastItemLane()).isEqualTo(MusicianFeedLane.GENERAL_DISCOVERY);
    }

    @Test
    void concurrentReplayOfTheSameSessionPositionHasOneWinnerAndOneControlledRejection() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Object> load = () -> {
            ready.countDown();
            start.await(5, TimeUnit.SECONDS);
            try {
                return transactions.execute(status -> service.recordPage(viewer, session, now, 1,
                        "musician-v1", 0, List.of(item("TRACK:" + UUID.randomUUID())), now));
            } catch (RuntimeException failure) {
                return failure;
            }
        };
        try {
            Future<Object> first = executor.submit(load);
            Future<Object> second = executor.submit(load);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Object> results = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertThat(results.stream().filter(List.class::isInstance)).hasSize(1);
            assertThat(results.stream().filter(SoundConnectException.class::isInstance)).hasSize(1);
            assertThat(number("select count(*) from tbl_musician_feed_delivery")).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void sessionCapacityFailsClosedBeforeWritingBeyondTheBound() {
        properties.setDefaultPageSize(1);
        properties.setMaxPageSize(1);
        properties.setMaxSessionDeliveries(1);
        transactions.executeWithoutResult(status -> service.recordPage(viewer, session, now, 1,
                "musician-v1", 0, List.of(item("TRACK:one")), now));

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> service.recordPage(
                viewer, session, now, 1, "musician-v1", 1, List.of(item("TRACK:two")), now)))
                .isInstanceOf(SoundConnectException.class);
        assertThat(number("select count(*) from tbl_musician_feed_delivery")).isEqualTo(1);
    }

    @Test
    void telemetryReplayMustMatchBothEventAndDeliveryIdentity() {
        List<MusicianFeedItemResponse> delivered = transactions.execute(status -> service.recordPage(
                viewer, session, now, 1, "musician-v1", 0,
                List.of(item("TRACK:one"), item("TRACK:two")), now));
        MusicianFeedTelemetryService telemetry = new MusicianFeedTelemetryService(jdbc, service,
                mock(MusicianFeedViewerGuard.class), properties, Clock.fixed(now, ZoneOffset.UTC));
        UUID eventId = UUID.randomUUID();
        var first = new MusicianFeedTelemetryRequest(eventId, delivered.get(0).impressionToken(),
                MusicianFeedTelemetryEventType.IMPRESSION, now);

        assertThat(telemetry.record(viewer, first).duplicate()).isFalse();
        assertThat(telemetry.record(viewer, first).duplicate()).isTrue();
        var sameDeliveryAndType = new MusicianFeedTelemetryRequest(UUID.randomUUID(),
                delivered.get(0).impressionToken(), MusicianFeedTelemetryEventType.IMPRESSION, now);
        MusicianFeedTelemetryResponse coalesced = telemetry.record(viewer, sameDeliveryAndType);
        assertThat(coalesced.duplicate()).isTrue();
        assertThat(coalesced.clientEventId()).isEqualTo(sameDeliveryAndType.clientEventId());
        assertThat(number("select count(*) from tbl_musician_feed_telemetry_event "
                + "where event_type='IMPRESSION'")).isEqualTo(1);

        var differentType = new MusicianFeedTelemetryRequest(UUID.randomUUID(),
                delivered.get(0).impressionToken(), MusicianFeedTelemetryEventType.OPEN, now);
        assertThat(telemetry.record(viewer, differentType).duplicate()).isFalse();
        var spoofedReplay = new MusicianFeedTelemetryRequest(eventId, delivered.get(1).impressionToken(),
                MusicianFeedTelemetryEventType.IMPRESSION, now);
        assertThatThrownBy(() -> telemetry.record(viewer, spoofedReplay))
                .isInstanceOf(SoundConnectException.class);
    }

    @Test
    void concurrentRotatingTelemetryIdsConvergeOnOneDeliveryEvent() throws Exception {
        MusicianFeedItemResponse delivered = transactions.execute(status -> service.recordPage(
                viewer, session, now, 1, "musician-v1", 0, List.of(item("TRACK:one")), now)).getFirst();
        MusicianFeedTelemetryService telemetry = new MusicianFeedTelemetryService(jdbc, service,
                mock(MusicianFeedViewerGuard.class), properties, Clock.fixed(now, ZoneOffset.UTC));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<MusicianFeedTelemetryResponse> record = () -> {
            ready.countDown();
            start.await(5, TimeUnit.SECONDS);
            return telemetry.record(viewer, new MusicianFeedTelemetryRequest(UUID.randomUUID(),
                    delivered.impressionToken(), MusicianFeedTelemetryEventType.IMPRESSION, now));
        };
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<MusicianFeedTelemetryResponse> first = executor.submit(record);
            Future<MusicianFeedTelemetryResponse> second = executor.submit(record);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<MusicianFeedTelemetryResponse> responses = List.of(
                    first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertThat(responses).extracting(MusicianFeedTelemetryResponse::duplicate)
                    .containsExactlyInAnyOrder(false, true);
            assertThat(responses).extracting(MusicianFeedTelemetryResponse::eventId)
                    .containsOnly(responses.getFirst().eventId());
            assertThat(number("select count(*) from tbl_musician_feed_telemetry_event "
                    + "where event_type='IMPRESSION'")).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void continuationPageReplayPreservesTheExactResponseAndValidDeliveryTokens() {
        String fingerprint = "a".repeat(43);
        List<MusicianFeedItemResponse> items = List.of(item("TRACK:one"), item("TRACK:two"));
        MusicianFeedPageResponse committed = transactions.execute(status -> service.recordPageAndReplay(
                viewer, session, now, 1, "musician-v1", 0, fingerprint, 2,
                Set.of(MusicianFeedItemType.TRACK), items,
                List.of(MusicianFeedLane.FOLLOWING, MusicianFeedLane.FOLLOWING),
                "signed.next.cursor", true, now));

        MusicianFeedPageResponse replayed = service.requireReplay(viewer, session, 0,
                fingerprint, 2, Set.of(MusicianFeedItemType.TRACK), now.plusSeconds(1));

        ObjectMapper wireMapper = new ObjectMapper().findAndRegisterModules();
        assertThat(wireMapper.<com.fasterxml.jackson.databind.JsonNode>valueToTree(replayed))
                .isEqualTo(wireMapper.valueToTree(committed));
        assertThat(replayed.generatedAt()).isEqualTo(now);
        assertThat(replayed.nextCursor()).isEqualTo("signed.next.cursor");
        assertThat(replayed.items()).extracting(MusicianFeedItemResponse::position)
                .containsExactly(0L, 1L);
        replayed.items().forEach(value -> assertThat(service.require(value.impressionToken(), viewer,
                value.id(), now.plusSeconds(1)).itemId()).isEqualTo(value.id()));
        assertThatThrownBy(() -> service.requireReplay(viewer, session, 0,
                fingerprint, 1, Set.of(MusicianFeedItemType.TRACK), now.plusSeconds(1)))
                .isInstanceOfSatisfying(SoundConnectException.class, failure ->
                        assertThat(failure.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST));
        assertThatThrownBy(() -> service.requireReplay(viewer, session, 0,
                fingerprint, 2, Set.of(MusicianFeedItemType.PROFILE_MEDIA), now.plusSeconds(1)))
                .isInstanceOf(SoundConnectException.class);
        assertThatThrownBy(() -> service.requireReplay(viewer, session, 0,
                "z".repeat(43), 2, Set.of(MusicianFeedItemType.TRACK), now.plusSeconds(1)))
                .isInstanceOf(SoundConnectException.class);
    }

    @Test
    void concurrentExactContinuationCallsConvergeOnOneCommittedPage() throws Exception {
        String fingerprint = "b".repeat(43);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<MusicianFeedPageResponse> firstCall = () -> {
            ready.countDown();
            start.await(5, TimeUnit.SECONDS);
            return transactions.execute(status -> service.recordPageAndReplay(
                    viewer, session, now, 1, "musician-v1", 0, fingerprint, 1,
                    Set.of(MusicianFeedItemType.TRACK), List.of(item("TRACK:first-draft")),
                    List.of(MusicianFeedLane.FOLLOWING), null, false, now));
        };
        Callable<MusicianFeedPageResponse> secondCall = () -> {
            ready.countDown();
            start.await(5, TimeUnit.SECONDS);
            return transactions.execute(status -> service.recordPageAndReplay(
                    viewer, session, now, 1, "musician-v1", 0, fingerprint, 1,
                    Set.of(MusicianFeedItemType.TRACK), List.of(item("TRACK:second-draft")),
                    List.of(MusicianFeedLane.FOLLOWING), null, false, now.plusMillis(1)));
        };
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<MusicianFeedPageResponse> first = executor.submit(firstCall);
            Future<MusicianFeedPageResponse> second = executor.submit(secondCall);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            MusicianFeedPageResponse left = first.get(10, TimeUnit.SECONDS);
            MusicianFeedPageResponse right = second.get(10, TimeUnit.SECONDS);

            ObjectMapper wireMapper = new ObjectMapper().findAndRegisterModules();
            assertThat(wireMapper.<com.fasterxml.jackson.databind.JsonNode>valueToTree(right))
                    .isEqualTo(wireMapper.valueToTree(left));
            assertThat(left.items()).extracting(MusicianFeedItemResponse::id)
                    .allMatch(id -> id.equals("TRACK:first-draft") || id.equals("TRACK:second-draft"));
            assertThat(number("select count(*) from tbl_musician_feed_delivery")).isEqualTo(1);
            assertThat(number("select count(*) from tbl_musician_feed_page_replay")).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void replayAndDeliveryCommitRollBackTogetherAndEmptyTerminalReplayIsBounded() {
        properties.setCursorTtl(java.time.Duration.ZERO);
        assertThatThrownBy(() -> transactions.execute(status -> service.recordPageAndReplay(
                viewer, session, now, 1, "musician-v1", 0, "c".repeat(43), 1,
                Set.of(MusicianFeedItemType.TRACK), List.of(item("TRACK:rollback")),
                List.of(MusicianFeedLane.FOLLOWING), null, false, now)))
                .isInstanceOf(SoundConnectException.class);
        assertThat(number("select count(*) from tbl_musician_feed_delivery")).isZero();
        assertThat(number("select count(*) from tbl_musician_feed_page_replay")).isZero();

        properties.setCursorTtl(java.time.Duration.ofHours(24));
        MusicianFeedPageResponse terminal = transactions.execute(status -> service.recordPageAndReplay(
                viewer, session, now, 1, "musician-v1", 0, "d".repeat(43), 1,
                Set.of(MusicianFeedItemType.TRACK), List.of(), List.of(), null, false, now));
        MusicianFeedPageResponse replayed = service.requireReplay(viewer, session, 0,
                "d".repeat(43), 1, Set.of(MusicianFeedItemType.TRACK), now.plusSeconds(1));
        ObjectMapper wireMapper = new ObjectMapper().findAndRegisterModules();
        assertThat(wireMapper.<com.fasterxml.jackson.databind.JsonNode>valueToTree(replayed))
                .isEqualTo(wireMapper.valueToTree(terminal));
        assertThatThrownBy(() -> service.requireReplay(viewer, session, 0, "d".repeat(43), 1,
                Set.of(MusicianFeedItemType.TRACK), now.plus(java.time.Duration.ofHours(24))))
                .isInstanceOf(SoundConnectException.class);
    }

    @Test
    void staleEmptyTerminalRequestCannotJournalAfterTheLedgerAdvanced() {
        transactions.executeWithoutResult(status -> service.recordPage(viewer, session, now, 1,
                "musician-v1", 0, List.of(item("TRACK:already-delivered")), now));

        assertThatThrownBy(() -> transactions.execute(status -> service.recordPageAndReplay(
                viewer, session, now, 1, "musician-v1", 0, "f".repeat(43), 1,
                Set.of(MusicianFeedItemType.TRACK), List.of(), List.of(), null, false,
                now.plusMillis(1))))
                .isInstanceOf(SoundConnectException.class);
        assertThat(number("select count(*) from tbl_musician_feed_delivery")).isEqualTo(1);
        assertThat(number("select count(*) from tbl_musician_feed_page_replay")).isZero();
    }

    private MusicianFeedItemResponse item(String id) {
        UUID profileId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        var author = new MusicianFeedItemResponse.Author(UUID.randomUUID(), profileId,
                "MUSICIAN", "artist", "Artist", null, true);
        return new MusicianFeedItemResponse(id, MusicianFeedItemType.TRACK, 1, now,
                new MusicianFeedItemResponse.Reason(MusicianFeedReasonCode.FOLLOWING_PUBLICATION,
                        List.of(author), 0), author, new MusicianFeedItemResponse.Target("MEDIA", targetId),
                null, null, List.of(MusicianFeedFeedbackAction.HIDE, MusicianFeedFeedbackAction.REPORT),
                Map.of("trackId", targetId));
    }

    private MusicianFeedItemResponse item(String id, MusicianFeedItemType type, String targetType,
                                          UUID targetId, MusicianFeedItemResponse.Promotion promotion) {
        UUID profileId = UUID.randomUUID();
        var author = new MusicianFeedItemResponse.Author(UUID.randomUUID(), profileId,
                "MUSICIAN", "artist", "Artist", null, true);
        MusicianFeedReasonCode reason = promotion == null
                ? MusicianFeedReasonCode.FOLLOWING_PUBLICATION : MusicianFeedReasonCode.SPONSORED;
        return new MusicianFeedItemResponse(id, type, 1, now,
                new MusicianFeedItemResponse.Reason(reason, List.of(author), 0), author,
                new MusicianFeedItemResponse.Target(targetType, targetId), null, promotion,
                List.of(MusicianFeedFeedbackAction.HIDE), Map.of("targetId", targetId));
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private long number(String sql) {
        try (Connection connection = connection(); Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getLong(1);
        } catch (SQLException failure) {
            throw new AssertionError(failure);
        }
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}

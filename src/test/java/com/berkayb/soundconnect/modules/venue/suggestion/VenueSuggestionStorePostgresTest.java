package com.berkayb.soundconnect.modules.venue.suggestion;

import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.IntConsumer;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class VenueSuggestionStorePostgresTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine")
            .withDatabaseName("venue_suggestion_test").withUsername("suggestion_test")
            .withPassword("suggestion_test").withReuse(false);
    static DriverManagerDataSource dataSource;
    static NamedParameterJdbcTemplate jdbc;
    static VenueSuggestionStore store;
    UUID city, district;
    String hash = "a".repeat(64), dedupe = "b".repeat(64);
    List<String> recipients = List.of("one@example.test", "two@example.test");

    @BeforeAll static void createDisposableSchema() throws Exception {
        assertThat(POSTGRES.isRunning()).isTrue();
        dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        assertDisposable();
        jdbc = new NamedParameterJdbcTemplate(dataSource);
        jdbc.getJdbcTemplate().execute("CREATE TABLE tbl_city(id uuid primary key,name varchar(255) not null)");
        jdbc.getJdbcTemplate().execute("CREATE TABLE tbl_district(id uuid primary key,city_id uuid not null references tbl_city(id),name varchar(255) not null)");
        migrate();
        store = new VenueSuggestionStore(jdbc, new DataSourceTransactionManager(dataSource));
    }
    static void migrate() {
        new ResourceDatabasePopulator(new FileSystemResource("scripts/db/2026-09-08-venue-suggestions.sql")).execute(dataSource);
    }
    static void assertDisposable() throws Exception {
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getURL()).isEqualTo(POSTGRES.getJdbcUrl());
            assertThat(connection.getCatalog()).isEqualTo("venue_suggestion_test");
        }
    }
    @BeforeEach void resetOnlyDisposableFixtures() throws Exception {
        assertDisposable();
        jdbc.getJdbcTemplate().execute("TRUNCATE tbl_venue_suggestion_mail,tbl_venue_suggestion_request,tbl_venue_suggestion_dedupe,tbl_venue_suggestion,tbl_district,tbl_city");
        city = UUID.randomUUID(); district = UUID.randomUUID();
        jdbc.update("INSERT INTO tbl_city VALUES (:id,'Ankara')", Map.of("id", city));
        jdbc.update("INSERT INTO tbl_district VALUES (:id,:city,'Çankaya')", Map.of("id", district, "city", city));
    }
    @Test void sameRequestRaceCommitsOneSuggestionAndExactlyOneJobPerRecipient() throws Exception {
        var request = request(UUID.randomUUID());
        parallel(8, ignored -> store.accept(request, "Mekan", hash, dedupe, recipients));
        assertThat(count("tbl_venue_suggestion_request")).isEqualTo(1);
        assertThat(count("tbl_venue_suggestion")).isEqualTo(1);
        assertThat(count("tbl_venue_suggestion_mail")).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT recipient FROM tbl_venue_suggestion_mail", Map.of(), String.class))
                .containsExactlyInAnyOrderElementsOf(recipients);
    }
    @Test void differentRequestIdsCannotRaceAroundDailyVenueDeduplication() throws Exception {
        parallel(8, ignored -> store.accept(request(UUID.randomUUID()), "Mekan", hash, dedupe, recipients));
        assertThat(count("tbl_venue_suggestion_request")).isEqualTo(8);
        assertThat(count("tbl_venue_suggestion")).isEqualTo(1);
        assertThat(count("tbl_venue_suggestion_mail")).isEqualTo(2);
    }
    @Test void sameIdentityWithChangedPayloadConflictsWithoutMutation() {
        var request = request(UUID.randomUUID()); accept(request);
        assertThatThrownBy(() -> store.accept(request, "Other", "c".repeat(64), dedupe, recipients))
                .isInstanceOf(SoundConnectException.class);
        assertThat(count("tbl_venue_suggestion")).isEqualTo(1);
        assertThat(count("tbl_venue_suggestion_mail")).isEqualTo(2);
    }
    @Test void invalidParentOrUnknownLocationRollsBackReceiptAndSuggestion() {
        var request = new VenueSuggestionRequest(UUID.randomUUID(), "Mekan", UUID.randomUUID(), district, VenueSuggestionRequest.LiveMusic.NO);
        assertThatThrownBy(() -> accept(request)).isInstanceOf(SoundConnectException.class);
        assertThat(count("tbl_venue_suggestion_request")).isZero();
        assertThat(count("tbl_venue_suggestion")).isZero();
        assertThat(count("tbl_venue_suggestion_mail")).isZero();
    }
    @Test void recipientFailureRollsBackEverythingNotPartialDelivery() {
        assertThatThrownBy(() -> store.accept(request(UUID.randomUUID()), "Mekan", hash, dedupe,
                List.of("same@example.test", "same@example.test"))).isInstanceOf(DataAccessException.class);
        for (String table : List.of("tbl_venue_suggestion_request", "tbl_venue_suggestion_dedupe", "tbl_venue_suggestion", "tbl_venue_suggestion_mail"))
            assertThat(count(table)).isZero();
    }
    @Test void suppressionExpiresAfterTwentyFourHoursButOriginalRequestRemainsIdempotent() {
        var original = request(UUID.randomUUID()); accept(original);
        jdbc.update("UPDATE tbl_venue_suggestion_dedupe SET expires_at=CURRENT_TIMESTAMP-INTERVAL '1 second'", Map.of());
        accept(request(UUID.randomUUID())); accept(original);
        assertThat(count("tbl_venue_suggestion")).isEqualTo(2);
        assertThat(count("tbl_venue_suggestion_mail")).isEqualTo(4);
    }
    @Test void consumerCanFinishBeforePublisherConfirmWithoutBeingResetOrSentAgain() {
        accept(request(UUID.randomUUID()));
        var publication = store.claimPublishBatch(1).getFirst();
        var delivery = store.claimSend(publication.id()).orElseThrow();
        store.sent(delivery); store.published(publication); store.publishFailed(publication);
        assertThat(state(publication.id())).isEqualTo("SENT");
        assertThat(store.claimSend(publication.id())).isEmpty();
    }
    @Test void concurrentConsumersAcquireExactlyOneDurableSendFence() throws Exception {
        accept(request(UUID.randomUUID()));
        var publication = store.claimPublishBatch(1).getFirst(); store.published(publication);
        var claims = new ConcurrentLinkedQueue<VenueSuggestionStore.Delivery>();
        parallel(8, ignored -> store.claimSend(publication.id()).ifPresent(claims::add));
        assertThat(claims).hasSize(1);
        assertThat(state(publication.id())).isEqualTo("SENDING");
    }
    @Test void concurrentPublishersDoNotLeaseSameRecipient() throws Exception {
        accept(request(UUID.randomUUID()));
        var claims = new ConcurrentLinkedQueue<VenueSuggestionStore.Delivery>();
        parallel(4, ignored -> claims.addAll(store.claimPublishBatch(1)));
        assertThat(claims).hasSize(2);
        assertThat(claims).extracting(VenueSuggestionStore.Delivery::id).doesNotHaveDuplicates();
    }
    @Test void expiredPublisherLeaseIsRecoverableButOldPublisherCannotOverwriteNewState() {
        accept(request(UUID.randomUUID()));
        var old = store.claimPublishBatch(1).getFirst();
        jdbc.update("UPDATE tbl_venue_suggestion_mail SET lease_until=CURRENT_TIMESTAMP-INTERVAL '1 second' WHERE id=:id", Map.of("id", old.id()));
        var recovered = store.claimPublishBatch(20).stream().filter(job -> job.id().equals(old.id())).findFirst().orElseThrow();
        assertThat(recovered.token()).isNotEqualTo(old.token());
        store.published(old);
        assertThat(state(old.id())).isEqualTo("PUBLISHING");
        store.published(recovered);
        assertThat(state(old.id())).isEqualTo("QUEUED");
    }
    @Test void expiredSendingParksForReviewAndNeverAutomaticallyResendsButDefinitiveLateSuccessSettles() {
        accept(request(UUID.randomUUID()));
        var publication = store.claimPublishBatch(1).getFirst();
        var sending = store.claimSend(publication.id()).orElseThrow();
        jdbc.update("UPDATE tbl_venue_suggestion_mail SET lease_until=CURRENT_TIMESTAMP-INTERVAL '1 second' WHERE id=:id", Map.of("id", sending.id()));
        store.claimPublishBatch(20);
        assertThat(state(sending.id())).isEqualTo("NEEDS_REVIEW");
        assertThat(store.claimSend(sending.id())).isEmpty();
        store.sent(sending);
        assertThat(state(sending.id())).isEqualTo("SENT");
    }
    @Test void offlineConsumerRecoveryIsBoundedEvenWithSuccessfulBrokerConfirms() {
        store.accept(request(UUID.randomUUID()), "Mekan", hash, dedupe, List.of(recipients.getFirst()));
        UUID id = null;
        for (int i = 0; i < 20; i++) {
            var publication = store.claimPublishBatch(1).getFirst(); id = publication.id(); store.published(publication);
            jdbc.update("UPDATE tbl_venue_suggestion_mail SET next_attempt_at=CURRENT_TIMESTAMP-INTERVAL '1 second' WHERE id=:id", Map.of("id", id));
        }
        assertThat(store.claimPublishBatch(1)).isEmpty();
        assertThat(state(id)).isEqualTo("NEEDS_REVIEW");
    }
    @Test void knownProviderRateLimitRetriesOnlyAfterDelay() {
        accept(request(UUID.randomUUID()));
        var publication = store.claimPublishBatch(1).getFirst();
        var sending = store.claimSend(publication.id()).orElseThrow();
        store.retryRateLimited(sending, 120);
        assertThat(state(sending.id())).isEqualTo("PENDING");
        assertThat(store.claimSend(sending.id())).isEmpty();
        assertThat(jdbc.queryForObject("SELECT next_attempt_at>CURRENT_TIMESTAMP+INTERVAL '100 seconds' FROM tbl_venue_suggestion_mail WHERE id=:id",
                Map.of("id", sending.id()), Boolean.class)).isTrue();
    }
    @Test void migrationCanBeRerunWithoutDroppingSuggestionOrMail() {
        accept(request(UUID.randomUUID())); migrate();
        assertThat(count("tbl_venue_suggestion")).isEqualTo(1);
        assertThat(count("tbl_venue_suggestion_mail")).isEqualTo(2);
    }
    @Test void healthOnlyReportsTechnicalCountsAndCompletedMailDoesNotAccumulatePending() {
        accept(request(UUID.randomUUID()));
        for (var publication : store.claimPublishBatch(20)) store.sent(store.claimSend(publication.id()).orElseThrow());
        assertThat(store.healthCounts()).containsOnlyKeys("review", "pending", "stale");
        assertThat(store.healthCounts().values()).allSatisfy(value -> assertThat(((Number) value).longValue()).isZero());
    }
    private void accept(VenueSuggestionRequest request) { store.accept(request, "Mekan", hash, dedupe, recipients); }
    private VenueSuggestionRequest request(UUID id) { return new VenueSuggestionRequest(id, "Mekan", city, district, VenueSuggestionRequest.LiveMusic.YES); }
    private long count(String table) { return jdbc.getJdbcTemplate().queryForObject("SELECT count(*) FROM " + table, Long.class); }
    private String state(UUID id) { return jdbc.queryForObject("SELECT status FROM tbl_venue_suggestion_mail WHERE id=:id", Map.of("id", id), String.class); }
    private static void parallel(int count, IntConsumer work) throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(count)) {
            var futures = new ArrayList<Future<?>>();
            for (int i = 0; i < count; i++) {
                final int index = i;
                futures.add(executor.submit(() -> { start.await(); work.accept(index); return null; }));
            }
            start.countDown();
            for (var future : futures) future.get(20, TimeUnit.SECONDS);
        }
    }
}

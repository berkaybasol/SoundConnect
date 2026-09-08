package com.berkayb.soundconnect.modules.analytics;

import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.IntConsumer;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class AnalyticsStorePostgresTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine")
            .withDatabaseName("venue_analytics_test").withUsername("analytics_test").withPassword("analytics_test").withReuse(false);
    static DriverManagerDataSource datasource;
    static NamedParameterJdbcTemplate jdbc;
    static AnalyticsStore store;
    static AnalyticsIdentity identity;
    final Instant now = Instant.parse("2026-09-08T12:00:00Z");
    UUID owner, venue, event, otherVenue, otherEvent, client;

    @BeforeAll static void schema() throws Exception {
        assertThat(POSTGRES.isRunning()).isTrue();
        datasource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        assertDisposable(); jdbc = new NamedParameterJdbcTemplate(datasource);
        jdbc.getJdbcTemplate().execute("CREATE TABLE tbl_user(id uuid PRIMARY KEY,status varchar(30),email_verified boolean)");
        jdbc.getJdbcTemplate().execute("CREATE TABLE tbl_role(id uuid PRIMARY KEY,name varchar(30)); CREATE TABLE user_roles(user_id uuid,role_id uuid)");
        jdbc.getJdbcTemplate().execute("CREATE TABLE tbl_venues(id uuid PRIMARY KEY,owner_id uuid REFERENCES tbl_user(id),status varchar(30))");
        jdbc.getJdbcTemplate().execute("CREATE TABLE tbl_event(id uuid PRIMARY KEY,venue_id uuid REFERENCES tbl_venues(id),title varchar(255),event_date date,start_time time,event_origin varchar(30),venue_calendar_approved boolean)");
        migrate();
        var properties = new AnalyticsProperties(); properties.setEnabled(true); properties.setReportingEnabled(true); properties.setHmacSecret("test-only-analytics-secret-32-bytes-long");
        identity = new AnalyticsIdentity(properties);
        store = new AnalyticsStore(jdbc, new DataSourceTransactionManager(datasource), identity);
    }
    static void assertDisposable() throws Exception {
        try (var connection = datasource.getConnection()) {
            assertThat(connection.getMetaData().getURL()).isEqualTo(POSTGRES.getJdbcUrl());
            assertThat(connection.getCatalog()).isEqualTo("venue_analytics_test");
        }
    }
    static void migrate() { new ResourceDatabasePopulator(new FileSystemResource("scripts/db/2026-09-08-venue-analytics.sql")).execute(datasource); }
    @BeforeEach void fixtures() throws Exception {
        assertDisposable();
        jdbc.getJdbcTemplate().execute("TRUNCATE tbl_venue_analytics_receipt,tbl_venue_analytics_presence,tbl_venue_analytics_recent_detail,tbl_event,tbl_venues,tbl_user,tbl_role,user_roles");
        jdbc.update("UPDATE tbl_venue_analytics_state SET tracking_started_at=NULL", Map.of());
        owner = UUID.randomUUID(); venue = UUID.randomUUID(); event = UUID.randomUUID(); client = UUID.randomUUID();
        otherVenue = UUID.randomUUID(); otherEvent = UUID.randomUUID();
        jdbc.update("INSERT INTO tbl_user VALUES (:id,'ACTIVE',true)", Map.of("id", owner));
        jdbc.update("INSERT INTO tbl_venues VALUES (:id,:owner,'APPROVED'),(:other,:owner,'APPROVED')", Map.of("id", venue, "other", otherVenue, "owner", owner));
        jdbc.update("INSERT INTO tbl_event VALUES (:id,:venue,'Event','2026-09-09','20:00','VENUE',true),(:other,:otherVenue,'Other','2026-09-10','20:00','VENUE',true)",
                Map.of("id", event, "venue", venue, "other", otherEvent, "otherVenue", otherVenue));
    }
    @Test void migrationIsAdditiveIdempotentAndTrackingStartsOnlyAfterCollection() {
        assertThat(summary().trackingStartedAt()).isNull(); migrate();
        observe(client, impression(event, now));
        assertThat(summary().trackingStartedAt()).isEqualTo(now);
        assertThat(count("tbl_event")).isEqualTo(2);
    }
    @Test void reloadsNewIdsAndMultipleDaysAreOnePeriodUniqueNotDailySum() {
        observe(client, impression(event, now)); observe(client, impression(event, now));
        observe(client, impression(event, now.minusSeconds(3600 * 20)));
        observe(UUID.randomUUID(), impression(event, now));
        assertThat(count("tbl_venue_analytics_receipt")).isEqualTo(4);
        assertThat(count("tbl_venue_analytics_presence")).isEqualTo(3);
        assertThat(summary().metrics().impressions()).isEqualTo(2);
    }
    @Test void accountsDeduplicateAcrossInstallationsButGuestAndAccountRemainDifferentIdentities() {
        UUID viewer = UUID.randomUUID();
        jdbc.update("INSERT INTO tbl_user VALUES (:id,'ACTIVE',true)", Map.of("id",viewer));
        store.observe(viewer, request(client, impression(event, now)), now);
        store.observe(viewer, request(UUID.randomUUID(), impression(event, now)), now);
        observe(client, impression(event, now));
        assertThat(summary().metrics().impressions()).isEqualTo(2);
    }
    @Test void sameObservationRacePersistsOneReceiptAndOnePresence() throws Exception {
        var request = request(client, impression(event, now));
        parallel(8, ignored -> store.observe(null, request, now));
        assertThat(count("tbl_venue_analytics_receipt")).isEqualTo(1);
        assertThat(count("tbl_venue_analytics_presence")).isEqualTo(1);
    }
    @Test void differentObservationRaceCannotInflateSameActorMetric() throws Exception {
        parallel(8, ignored -> observe(client, impression(event, now)));
        assertThat(count("tbl_venue_analytics_receipt")).isEqualTo(8);
        assertThat(summary().metrics().impressions()).isEqualTo(1);
    }
    @Test void overlappingReversedBatchesKeepProofAndCountsWithoutPresenceDeadlocks() throws Exception {
        parallel(8, index -> {
            var observations=new ArrayList<>(List.of(detail(event,now),profile(venue,event,now),impression(event,now),detail(otherEvent,now),profile(otherVenue,otherEvent,now)));
            if (index%2==0) Collections.reverse(observations);
            store.observe(null,new AnalyticsRequest(client,observations),now);
        });
        assertThat(count("tbl_venue_analytics_receipt")).isEqualTo(40);
        assertThat(summary().metrics()).isEqualTo(new AnalyticsResponse.Metrics(1,1,1));
        assertThat(eventSummary(event).metrics().profileVisits()).isEqualTo(1);
    }
    @Test void persistedRetryAcknowledgesAfterTimestampAgeButChangedActorPayloadConflicts() {
        var observation = impression(event, now); var request = request(client, observation);
        store.observe(null, request, now); store.observe(null, request, now.plus(Duration.ofHours(25)));
        assertThatThrownBy(() -> store.observe(UUID.randomUUID(), request, now)).isInstanceOf(SoundConnectException.class);
        var changed = new AnalyticsRequest.Observation(observation.id(), observation.type(), otherEvent, null, null, now);
        assertThatThrownBy(() -> store.observe(null, request(client, changed), now)).isInstanceOf(SoundConnectException.class);
        assertThat(count("tbl_venue_analytics_receipt")).isEqualTo(1);
    }
    @Test void freshTooOldTooFutureOrMixedInvalidBatchRollsBackEverything() {
        for (Instant invalid : List.of(now.minusSeconds(86401), now.plusSeconds(121))) {
            assertThatThrownBy(() -> observe(client, impression(event, now), impression(event, invalid))).isInstanceOf(SoundConnectException.class);
            assertThat(count("tbl_venue_analytics_receipt")).isZero(); assertThat(count("tbl_venue_analytics_presence")).isZero();
        }
        observe(client, impression(event, now.minusSeconds(86400)), impression(event, now.plusSeconds(120)));
        assertThat(count("tbl_venue_analytics_receipt")).isEqualTo(2);
    }
    @Test void ownUnknownInactiveUnverifiedUnapprovedAndNonVenueObservationsAreAcknowledgedWithoutCounts() {
        store.observe(owner, request(client, impression(event, now)), now);
        observe(client, impression(UUID.randomUUID(), now));
        for (String statement : List.of("UPDATE tbl_user SET status='PASSIVE'", "UPDATE tbl_user SET status='ACTIVE',email_verified=false",
                "UPDATE tbl_user SET email_verified=true", "UPDATE tbl_venues SET status='PENDING'")) {
            jdbc.getJdbcTemplate().execute(statement);
            if (!statement.endsWith("email_verified=true")) observe(client, impression(event, now), profile(venue, null, now));
        }
        jdbc.update("UPDATE tbl_venues SET status='APPROVED'", Map.of());
        jdbc.update("UPDATE tbl_event SET event_origin='ARTIST'", Map.of()); observe(client, impression(event, now));
        jdbc.update("UPDATE tbl_event SET event_origin='VENUE',venue_calendar_approved=false", Map.of()); observe(client, impression(event, now));
        assertThat(count("tbl_venue_analytics_presence")).isZero();
        assertThat(count("tbl_venue_analytics_receipt")).isEqualTo(10);
    }
    @Test void sameBatchDetailBeforeProfileProvesAttributionRegardlessOfPayloadOrder() {
        observe(client, profile(venue, event, now), detail(event, now));
        assertThat(summary().metrics()).isEqualTo(new AnalyticsResponse.Metrics(0, 1, 1));
        assertThat(eventSummary(event).metrics().profileVisits()).isEqualTo(1);
    }
    @Test void forgedWrongVenueExpiredAndWrongActorSourcesNeverInflateAttributionOrTotal() {
        observe(client, detail(otherEvent, now), profile(venue, otherEvent, now), profile(venue, event, now));
        observe(UUID.randomUUID(), detail(event, now));
        observe(client, profile(venue, event, now));
        observe(client, detail(event, now.minusSeconds(1801)), profile(venue, event, now));
        assertThat(eventSummary(event).metrics().profileVisits()).isZero();
        assertThat(summary().metrics().profileVisits()).isEqualTo(1);
    }
    @Test void repeatedDailyDetailRefreshesProofAndGlobalProfileUniquesDoNotSumSources() {
        observe(client, detail(event, now.minusSeconds(4000)));
        observe(client, detail(event, now), profile(venue, event, now));
        var second = UUID.randomUUID();
        jdbc.update("INSERT INTO tbl_event VALUES (:id,:venue,'Second','2026-09-10','20:00','VENUE',true)", Map.of("id", second, "venue", venue));
        observe(client, detail(second, now), profile(venue, second, now), profile(venue, null, now));
        assertThat(summary().metrics().profileVisits()).isEqualTo(1);
        assertThat(eventSummary(event).metrics().profileVisits()).isEqualTo(1);
        assertThat(eventSummary(second).metrics().profileVisits()).isEqualTo(1);
    }
    @Test void delayedSameBatchDetailAndProfileKeepAttributionDespiteNewerStoredProof() {
        observe(client,detail(event,now));
        observe(client,detail(event,now.minusSeconds(360)),profile(venue,event,now.minusSeconds(300)));
        assertThat(eventSummary(event).metrics().profileVisits()).isEqualTo(1);
    }
    @Test void delayedCrossBatchProfileUsesEarlierValidProofWithoutFillingUnobservedGaps() {
        Instant first=now.minusSeconds(3600);
        observe(client,detail(event,first)); observe(client,detail(event,first.plusSeconds(2400)));
        observe(client,profile(venue,event,first.plusSeconds(300)));
        assertThat(eventSummary(event).metrics().profileVisits()).isEqualTo(1);
        UUID gapClient=UUID.randomUUID();
        observe(gapClient,detail(event,first)); observe(gapClient,detail(event,first.plusSeconds(2400)));
        observe(gapClient,profile(venue,event,first.plusSeconds(2100)));
        assertThat(eventSummary(event).metrics().profileVisits()).isEqualTo(1);
        assertThat(summary().metrics().profileVisits()).isEqualTo(2);
    }
    @Test void proofWindowsKeepInclusiveThirtyMinuteBoundaryAndRejectEarlierOrJustLaterVisits() {
        Instant first=now.minusSeconds(3600);
        for(long offset:List.of(-1L,1800L,1801L)) {
            UUID actor=UUID.randomUUID(); observe(actor,detail(event,first)); observe(actor,profile(venue,event,first.plusSeconds(offset)));
        }
        assertThat(summary().metrics().profileVisits()).isEqualTo(3);
        assertThat(eventSummary(event).metrics().profileVisits()).isEqualTo(1);
    }
    @Test void normalizedProofWindowsHaveBoundedSizeAndPruneOldWindowsOnRefresh() {
        Instant oldest=now.minusSeconds(86400);
        for(int batch=0;batch<3;batch++) {
            var observations=new ArrayList<AnalyticsRequest.Observation>();
            for(int index=batch*16;index<Math.min(47,(batch+1)*16);index++) observations.add(detail(event,oldest.plusSeconds(index*1860L)));
            observe(client,observations.toArray(AnalyticsRequest.Observation[]::new));
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tbl_venue_analytics_recent_detail, LATERAL unnest(valid_windows)",Map.of(),Long.class)).isEqualTo(47);
        Instant later=now.plusSeconds(90000);
        store.observe(null,request(client,detail(event,later)),later);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tbl_venue_analytics_recent_detail, LATERAL unnest(valid_windows)",Map.of(),Long.class)).isEqualTo(1);
    }
    @Test void deletedEventsKeepNumericVenueHistoryButDisappearFromOwnerEventReads() {
        observe(client, impression(event, now), detail(event, now), profile(venue, event, now));
        jdbc.update("DELETE FROM tbl_event WHERE id=:id", Map.of("id", event));
        assertThat(summary().metrics()).isEqualTo(new AnalyticsResponse.Metrics(1, 1, 1));
        assertThat(store.events(owner, venue, 30, 0, 20, now).content()).isEmpty();
        assertThatThrownBy(() -> eventSummary(event)).isInstanceOf(SoundConnectException.class);
    }
    @Test void currentOwnerRequiredOnEveryReadAndEventCannotEscapeItsVenue() {
        UUID stranger = UUID.randomUUID();
        assertThatThrownBy(() -> store.summary(stranger, venue, null, 30, now)).isInstanceOf(SoundConnectException.class);
        assertThatThrownBy(() -> store.events(stranger, venue, 30, 0, 20, now)).isInstanceOf(SoundConnectException.class);
        assertThatThrownBy(() -> eventSummary(otherEvent)).isInstanceOf(SoundConnectException.class);
        jdbc.update("INSERT INTO tbl_user VALUES (:id,'ACTIVE',true)", Map.of("id", stranger));
        jdbc.update("UPDATE tbl_venues SET owner_id=:owner WHERE id=:id", Map.of("id", venue, "owner", stranger));
        assertThatThrownBy(this::summary).isInstanceOf(SoundConnectException.class);
        assertThat(store.summary(stranger, venue, null, 30, now).metrics()).isEqualTo(AnalyticsResponse.Metrics.ZERO);
    }
    @Test void inactiveUnverifiedMissingAndAdminActorsCannotContributeAndStaleOwnerReadsAreRejected() {
        UUID actor=UUID.randomUUID(), role=UUID.randomUUID();
        jdbc.update("INSERT INTO tbl_user VALUES (:id,'INACTIVE',true)",Map.of("id",actor));
        store.observe(actor,request(client,impression(event,now)),now);
        jdbc.update("UPDATE tbl_user SET status='ACTIVE',email_verified=false WHERE id=:id",Map.of("id",actor));
        store.observe(actor,request(client,impression(event,now)),now);
        jdbc.update("UPDATE tbl_user SET email_verified=true WHERE id=:id",Map.of("id",actor));
        jdbc.update("INSERT INTO tbl_role VALUES (:id,'ROLE_ADMIN')",Map.of("id",role));
        jdbc.update("INSERT INTO user_roles VALUES (:actor,:role)",Map.of("actor",actor,"role",role));
        store.observe(actor,request(client,impression(event,now)),now);
        store.observe(UUID.randomUUID(),request(client,impression(event,now)),now);
        assertThat(summary().metrics()).isEqualTo(AnalyticsResponse.Metrics.ZERO);
        jdbc.update("UPDATE tbl_user SET status='INACTIVE' WHERE id=:id",Map.of("id",owner));
        assertThatThrownBy(this::summary).isInstanceOf(SoundConnectException.class);
        jdbc.update("UPDATE tbl_user SET status='ACTIVE',email_verified=false WHERE id=:id",Map.of("id",owner));
        assertThatThrownBy(this::summary).isInstanceOf(SoundConnectException.class);
    }
    @Test void istanbulDayBoundariesAndSevenThirtyNinetyDayWindowsAreInclusive() {
        Instant midnight = Instant.parse("2026-09-08T21:00:00Z");
        store.observe(null, request(client, impression(event, midnight)), midnight);
        var result = store.summary(owner, venue, null, 7, midnight);
        assertThat(result.toDate()).isEqualTo(LocalDate.of(2026,9,9));
        assertThat(result.fromDate()).isEqualTo(LocalDate.of(2026,9,3));
        assertThat(result.timeZone()).isEqualTo("Europe/Istanbul");
        assertThat(result.metrics().impressions()).isEqualTo(1);
    }
    @Test void dailyCoverageHasFixedCalendarDatesAndNoInventedZerosBeforeCollection() {
        for (int days : List.of(7,30,90)) {
            var result=store.summary(owner,venue,null,days,now);
            assertThat(result.daily()).hasSize(days);
            assertThat(result.daily()).extracting(AnalyticsResponse.DailyPoint::date)
                    .containsExactlyElementsOf(result.fromDate().datesUntil(result.toDate().plusDays(1)).toList());
            assertThat(result.daily()).allSatisfy(point -> assertThat(point.metrics()).isNull());
            assertThat(result.daily().stream().filter(AnalyticsResponse.DailyPoint::partial).map(AnalyticsResponse.DailyPoint::date))
                    .containsExactly(result.toDate());
            assertThat(result.comparison().status()).isEqualTo(AnalyticsResponse.ComparisonStatus.NOT_STARTED);
            assertThat(result.comparison().currentMetrics()).isNull(); assertThat(result.comparison().previousMetrics()).isNull();
        }
        tracking(Instant.parse("2026-09-05T12:00:00Z"));
        var points=store.summary(owner,venue,null,7,now).daily();
        assertThat(points.get(2).metrics()).isNull();
        assertThat(points.get(3)).isEqualTo(new AnalyticsResponse.DailyPoint(LocalDate.of(2026,9,5),AnalyticsResponse.Metrics.ZERO,true));
        assertThat(points.get(4)).isEqualTo(new AnalyticsResponse.DailyPoint(LocalDate.of(2026,9,6),AnalyticsResponse.Metrics.ZERO,false));
        assertThat(points.getLast().partial()).isTrue();
    }
    @Test void firstDelayedBatchRetainsRealPreviousDayFactsAsPartialWithoutInventingEarlierCoverage() {
        observe(client,impression(event,Instant.parse("2026-09-07T20:59:59Z")),impression(event,now));
        var result=store.summary(owner,venue,null,7,now);
        assertThat(result.trackingStartedAt()).isEqualTo(now);
        assertThat(result.daily().get(4).metrics()).isNull();
        assertThat(result.daily().get(5)).isEqualTo(new AnalyticsResponse.DailyPoint(LocalDate.of(2026,9,7),new AnalyticsResponse.Metrics(1,0,0),true));
        assertThat(result.daily().getLast().metrics().impressions()).isEqualTo(1);
        assertThat(result.metrics().impressions()).isEqualTo(1);
        assertThat(result.comparison().status()).isEqualTo(AnalyticsResponse.ComparisonStatus.INSUFFICIENT_HISTORY);
    }
    @Test void exactIstanbulMidnightStartIsCompleteButTodayRemainsPartialAcrossUtcBoundary() {
        tracking(Instant.parse("2026-09-04T21:00:00Z"));
        var result=store.summary(owner,venue,null,7,Instant.parse("2026-09-08T21:00:00Z"));
        assertThat(result.toDate()).isEqualTo(LocalDate.of(2026,9,9));
        assertThat(result.daily().get(2)).isEqualTo(new AnalyticsResponse.DailyPoint(LocalDate.of(2026,9,5),AnalyticsResponse.Metrics.ZERO,false));
        assertThat(result.daily().getLast()).isEqualTo(new AnalyticsResponse.DailyPoint(LocalDate.of(2026,9,9),AnalyticsResponse.Metrics.ZERO,true));
    }
    @Test void comparisonUsesCompletedDaysDistinctViewersAndIsolatesEventsAndVenues() {
        tracking(Instant.parse("2026-08-24T21:00:00Z"));
        UUID second=UUID.randomUUID();
        jdbc.update("INSERT INTO tbl_event VALUES (:id,:venue,'Second','2026-09-09','20:00','VENUE',true)",Map.of("id",second,"venue",venue));
        for (String day : List.of("2026-08-25","2026-08-31","2026-09-01","2026-09-07")) {
            presence(venue,event,AnalyticsIdentity.NONE,day,AnalyticsRequest.Type.EVENT_IMPRESSION,"overlap");
            presence(venue,second,AnalyticsIdentity.NONE,day,AnalyticsRequest.Type.EVENT_IMPRESSION,"overlap");
            presence(venue,event,AnalyticsIdentity.NONE,day,AnalyticsRequest.Type.EVENT_DETAIL_VIEW,"overlap");
            presence(venue,AnalyticsIdentity.NONE,event,day,AnalyticsRequest.Type.VENUE_PROFILE_VIEW,"overlap");
            presence(otherVenue,otherEvent,AnalyticsIdentity.NONE,day,AnalyticsRequest.Type.EVENT_IMPRESSION,"foreign");
        }
        presence(venue,second,AnalyticsIdentity.NONE,"2026-09-07",AnalyticsRequest.Type.EVENT_IMPRESSION,"second-only");
        presence(venue,AnalyticsIdentity.NONE,second,"2026-09-07",AnalyticsRequest.Type.VENUE_PROFILE_VIEW,"second-only");
        presence(venue,event,AnalyticsIdentity.NONE,"2026-09-08",AnalyticsRequest.Type.EVENT_IMPRESSION,"today-only");
        presence(venue,event,AnalyticsIdentity.NONE,"2026-08-24",AnalyticsRequest.Type.EVENT_IMPRESSION,"before-window");
        var result=store.summary(owner,venue,null,7,now);
        assertThat(result.metrics()).isEqualTo(new AnalyticsResponse.Metrics(3,1,2));
        assertThat(result.daily().get(5).metrics()).isEqualTo(new AnalyticsResponse.Metrics(2,1,2));
        assertThat(result.comparison()).isEqualTo(new AnalyticsResponse.PeriodComparison(AnalyticsResponse.ComparisonStatus.AVAILABLE,
                LocalDate.of(2026,9,1),LocalDate.of(2026,9,7),LocalDate.of(2026,8,25),LocalDate.of(2026,8,31),
                new AnalyticsResponse.Metrics(2,1,2),new AnalyticsResponse.Metrics(1,1,1)));
        var eventResult=store.summary(owner,venue,event,7,now);
        assertThat(eventResult.metrics()).isEqualTo(new AnalyticsResponse.Metrics(2,1,1));
        assertThat(eventResult.comparison().currentMetrics()).isEqualTo(new AnalyticsResponse.Metrics(1,1,1));
        assertThat(eventResult.comparison().previousMetrics()).isEqualTo(new AnalyticsResponse.Metrics(1,1,1));
        assertThat(eventResult.daily().get(5).metrics()).isEqualTo(new AnalyticsResponse.Metrics(1,1,1));
    }
    @Test void previousZeroRemainsAvailableRawZeroAndTodayCannotInflateCompletedPeriod() {
        tracking(Instant.parse("2026-08-24T21:00:00Z"));
        presence(venue,event,AnalyticsIdentity.NONE,"2026-09-07",AnalyticsRequest.Type.EVENT_DETAIL_VIEW,"yesterday");
        presence(venue,event,AnalyticsIdentity.NONE,"2026-09-08",AnalyticsRequest.Type.EVENT_DETAIL_VIEW,"today");
        var result=store.summary(owner,venue,null,7,now);
        assertThat(result.metrics().detailViews()).isEqualTo(2);
        assertThat(result.comparison().status()).isEqualTo(AnalyticsResponse.ComparisonStatus.AVAILABLE);
        assertThat(result.comparison().currentMetrics()).isEqualTo(new AnalyticsResponse.Metrics(0,1,0));
        assertThat(result.comparison().previousMetrics()).isEqualTo(AnalyticsResponse.Metrics.ZERO);
    }
    @Test void comparisonStartMustCoverPreviousMidnightForBothSevenAndThirtyDays() {
        for (int days : List.of(7,30)) {
            Instant boundary=now.atZone(AnalyticsStore.ZONE).toLocalDate().minusDays(days*2L).atStartOfDay(AnalyticsStore.ZONE).toInstant();
            tracking(boundary.plusNanos(1000));
            var partial=store.summary(owner,venue,null,days,now).comparison();
            assertThat(partial.status()).isEqualTo(AnalyticsResponse.ComparisonStatus.INSUFFICIENT_HISTORY);
            assertThat(partial.currentMetrics()).isNull(); assertThat(partial.previousMetrics()).isNull();
            tracking(boundary);
            var complete=store.summary(owner,venue,null,days,now).comparison();
            assertThat(complete.status()).isEqualTo(AnalyticsResponse.ComparisonStatus.AVAILABLE);
            assertThat(complete.currentMetrics()).isEqualTo(AnalyticsResponse.Metrics.ZERO);
            assertThat(complete.previousMetrics()).isEqualTo(AnalyticsResponse.Metrics.ZERO);
        }
    }
    @Test void ninetyDayComparisonStaysUnavailableEvenWhenOldRowsAwaitRetentionCleanup() {
        tracking(Instant.parse("2026-01-01T00:00:00Z"));
        presence(venue,event,AnalyticsIdentity.NONE,"2026-06-10",AnalyticsRequest.Type.EVENT_IMPRESSION,"too-old");
        presence(venue,event,AnalyticsIdentity.NONE,"2026-06-11",AnalyticsRequest.Type.EVENT_IMPRESSION,"oldest-retained");
        var result=store.summary(owner,venue,null,90,now);
        assertThat(result.fromDate()).isEqualTo(LocalDate.of(2026,6,11));
        assertThat(result.metrics().impressions()).isEqualTo(1);
        assertThat(result.daily().getFirst().metrics().impressions()).isEqualTo(1);
        assertThat(result.comparison().status()).isEqualTo(AnalyticsResponse.ComparisonStatus.RETENTION_LIMIT);
        assertThat(result.comparison().currentMetrics()).isNull(); assertThat(result.comparison().previousMetrics()).isNull();
        assertThat(count("tbl_venue_analytics_presence")).isEqualTo(2);
    }
    @Test void summaryDailyComparisonAndStartMetadataShareOneRepeatableReadSnapshot() {
        Instant originalStart=Instant.parse("2026-08-24T21:00:00Z"); tracking(originalStart);
        presence(venue,event,AnalyticsIdentity.NONE,"2026-09-07",AnalyticsRequest.Type.EVENT_IMPRESSION,"before-read");
        var concurrentWrite=new java.util.concurrent.atomic.AtomicBoolean();
        var intercepted=new NamedParameterJdbcTemplate(datasource) {
            @Override public List<Map<String,Object>> queryForList(String sql, Map<String,?> params) {
                var result=super.queryForList(sql,params);
                if (sql.contains("GROUPING SETS") && concurrentWrite.compareAndSet(false,true)) {
                    assertThat(getJdbcTemplate().queryForObject("SHOW transaction_isolation",String.class)).isEqualTo("repeatable read");
                    assertThat(getJdbcTemplate().queryForObject("SHOW transaction_read_only",String.class)).isEqualTo("on");
                    try (var executor=Executors.newSingleThreadExecutor()) {
                        executor.submit(() -> {
                            presence(venue,event,AnalyticsIdentity.NONE,"2026-09-07",AnalyticsRequest.Type.EVENT_IMPRESSION,"during-read");
                            tracking(now);
                        }).get(10,TimeUnit.SECONDS);
                    } catch (Exception failure) { throw new AssertionError(failure); }
                }
                return result;
            }
        };
        var snapshotStore=new AnalyticsStore(intercepted,new DataSourceTransactionManager(datasource),identity);
        var result=snapshotStore.summary(owner,venue,null,7,now);
        assertThat(concurrentWrite).isTrue();
        assertThat(result.metrics().impressions()).isEqualTo(1);
        assertThat(result.daily().get(5).metrics().impressions()).isEqualTo(1);
        assertThat(result.trackingStartedAt()).isEqualTo(originalStart);
        assertThat(result.comparison().status()).isEqualTo(AnalyticsResponse.ComparisonStatus.AVAILABLE);
        assertThat(result.comparison().currentMetrics().impressions()).isEqualTo(1);
        assertThat(store.summary(owner,venue,null,7,now).metrics().impressions()).isEqualTo(2);
        assertThat(store.summary(owner,venue,null,7,now).trackingStartedAt()).isEqualTo(now);
    }
    @Test void metricSortingRanksAllEligibleEventsBeforeStablePaginationIncludingZerosAndLastPage() {
        jdbc.update("DELETE FROM tbl_event WHERE venue_id=:venue",Map.of("venue",venue));
        var ids=new ArrayList<UUID>();
        for (int i=1;i<=7;i++) {
            UUID id=UUID.fromString("10000000-0000-4000-8000-"+String.format("%012d",i)); ids.add(id);
            jdbc.update("INSERT INTO tbl_event VALUES (:id,:venue,:title,:day,:time,'VENUE',true)",
                    Map.of("id",id,"venue",venue,"title","Sorted "+i,"day",LocalDate.of(2026,9,i==2?11:10),"time",LocalTime.of(i==3?21:20,0)));
            int reaches=i<=3?3:i==4?2:0, details=i==5?4:i<=3?1:0, profiles=i==6?5:i<=3?1:0;
            for (int n=0;n<reaches;n++) {
                presence(venue,id,AnalyticsIdentity.NONE,"2026-09-07",AnalyticsRequest.Type.EVENT_IMPRESSION,"reach-"+n);
                presence(venue,id,AnalyticsIdentity.NONE,"2026-09-08",AnalyticsRequest.Type.EVENT_IMPRESSION,"reach-"+n);
            }
            for (int n=0;n<details;n++) presence(venue,id,AnalyticsIdentity.NONE,"2026-09-08",AnalyticsRequest.Type.EVENT_DETAIL_VIEW,"detail-"+n);
            for (int n=0;n<profiles;n++) presence(venue,AnalyticsIdentity.NONE,id,"2026-09-08",AnalyticsRequest.Type.VENUE_PROFILE_VIEW,"profile-"+n);
        }
        // Out-of-window, foreign-venue and unattributed facts cannot enter the ranking.
        presence(venue,ids.get(6),AnalyticsIdentity.NONE,"2026-08-01",AnalyticsRequest.Type.EVENT_IMPRESSION,"outside");
        presence(otherVenue,otherEvent,AnalyticsIdentity.NONE,"2026-09-08",AnalyticsRequest.Type.EVENT_IMPRESSION,"foreign");
        presence(venue,AnalyticsIdentity.NONE,AnalyticsIdentity.NONE,"2026-09-08",AnalyticsRequest.Type.VENUE_PROFILE_VIEW,"unattributed");
        var expected=Map.of(AnalyticsResponse.EventSort.DATE,List.of(2,3,1,4,5,6,7),
                AnalyticsResponse.EventSort.REACH,List.of(2,3,1,4,5,6,7),
                AnalyticsResponse.EventSort.DETAIL_VIEWS,List.of(5,2,3,1,4,6,7),
                AnalyticsResponse.EventSort.PROFILE_VISITS,List.of(6,2,3,1,4,5,7));
        for (var sort : AnalyticsResponse.EventSort.values()) {
            var ordered=expected.get(sort).stream().map(i -> ids.get(i-1)).toList();
            for (int page=0;page<=4;page++) {
                var result=store.events(owner,venue,7,page,2,sort,now);
                assertThat(result.sort()).isEqualTo(sort);
                assertThat(result.totalElements()).isEqualTo(7); assertThat(result.totalPages()).isEqualTo(4);
                assertThat(result.content()).extracting(AnalyticsResponse.EventItem::eventId)
                        .containsExactlyElementsOf(ordered.subList(Math.min(page*2,7),Math.min(page*2+2,7)));
                assertThat(result.hasNext()).isEqualTo(page<3);
                for (var item : result.content()) assertThat(item.metrics()).isEqualTo(store.summary(owner,venue,item.eventId(),7,now).metrics());
            }
        }
        jdbc.update("UPDATE tbl_event SET venue_calendar_approved=false WHERE id=:id",Map.of("id",ids.get(5)));
        assertThat(store.events(owner,venue,7,0,20,AnalyticsResponse.EventSort.PROFILE_VISITS,now).content())
                .extracting(AnalyticsResponse.EventItem::eventId).doesNotContain(ids.get(5),otherEvent);
        for (var sort : AnalyticsResponse.EventSort.values())
            assertThatThrownBy(() -> store.events(UUID.randomUUID(),venue,7,0,20,sort,now)).isInstanceOf(SoundConnectException.class);
    }
    @Test void tenThousandEventsAndHundredThousandPresenceRowsHaveStableBoundedPagesAndExactDistinctCounts() {
        jdbc.update("DELETE FROM tbl_event WHERE venue_id=:venue", Map.of("venue", venue));
        jdbc.update("""
                INSERT INTO tbl_event SELECT md5('event-'||n)::uuid,:venue,'Event '||n,'2026-09-09'::date,'20:00'::time,'VENUE',true
                FROM generate_series(1,10000) n
                """, Map.of("venue", venue));
        jdbc.update("""
                INSERT INTO tbl_venue_analytics_presence SELECT :venue,'2026-09-08'::date,'EVENT_IMPRESSION',md5('event-'||e)::uuid,
                '00000000-0000-0000-0000-000000000000'::uuid,decode(md5('viewer-'||v)||md5('viewer-'||v),'hex')
                FROM generate_series(1,10000) e CROSS JOIN generate_series(1,10) v
                """, Map.of("venue", venue));
        var all = jdbc.queryForList("SELECT id FROM tbl_event WHERE venue_id=:venue ORDER BY event_date DESC,start_time DESC,id", Map.of("venue",venue), UUID.class);
        for (int page : List.of(0,250,499,500)) {
            var result = store.events(owner, venue, 30, page, 20, now);
            assertThat(result.totalElements()).isEqualTo(10000); assertThat(result.totalPages()).isEqualTo(500);
            assertThat(result.content()).extracting(AnalyticsResponse.EventItem::eventId).containsExactlyElementsOf(all.subList(Math.min(page*20,10000),Math.min(page*20+20,10000)));
            assertThat(result.hasNext()).isEqualTo(page<499);
            assertThat(result.content()).allSatisfy(item -> assertThat(item.metrics().impressions()).isEqualTo(10));
        }
        assertThat(summary().metrics().impressions()).isEqualTo(10);
    }
    @Test void expiryCleanupIsBoundedAndDoesNotRemoveWithinHorizonPresence() {
        observe(client, impression(event, now));
        jdbc.update("UPDATE tbl_venue_analytics_receipt SET expires_at=:expiry", Map.of("expiry", Timestamp.from(now.minusSeconds(1))));
        jdbc.update("INSERT INTO tbl_venue_analytics_presence SELECT venue_id,'2026-06-10'::date,metric_type,event_id,source_event_id,viewer_key FROM tbl_venue_analytics_presence", Map.of());
        store.cleanup(now);
        assertThat(count("tbl_venue_analytics_receipt")).isZero();
        assertThat(count("tbl_venue_analytics_presence")).isEqualTo(1);
    }
    @Test void cleanupProcessesOnlyOneThousandRowsPerTableAndHealthDetectsOverdueBacklog() {
        jdbc.update("""
                INSERT INTO tbl_venue_analytics_receipt SELECT md5(n::text)::uuid,decode(repeat('ab',32),'hex'),
                    decode(repeat('cd',32),'hex'),:expiry FROM generate_series(1,2500) n
                """,Map.of("expiry",Timestamp.from(now.minusSeconds(7200))));
        assertThat(store.retentionHealthy(now)).isFalse();
        assertThat(store.cleanup(now)).isEqualTo(1000); assertThat(count("tbl_venue_analytics_receipt")).isEqualTo(1500);
        assertThat(store.cleanup(now)).isEqualTo(1000); assertThat(store.cleanup(now)).isEqualTo(500);
        assertThat(store.retentionHealthy(now)).isTrue();
    }
    @Test void schemaDetectionIsSafeWithoutInstalledAnalyticsTables() {
        assertThat(store.schemaInstalled()).isTrue();
        jdbc.getJdbcTemplate().execute("ALTER TABLE tbl_venue_analytics_state RENAME TO test_temporarily_hidden_analytics_state");
        try { assertThat(store.schemaInstalled()).isFalse(); }
        finally { jdbc.getJdbcTemplate().execute("ALTER TABLE test_temporarily_hidden_analytics_state RENAME TO tbl_venue_analytics_state"); }
    }
    AnalyticsResponse.Summary summary() { return store.summary(owner, venue, null, 30, now); }
    void tracking(Instant at) { jdbc.update("UPDATE tbl_venue_analytics_state SET tracking_started_at=:at",Map.of("at",Timestamp.from(at))); }
    void presence(UUID targetVenue, UUID targetEvent, UUID source, String day, AnalyticsRequest.Type type, String viewer) {
        jdbc.update("""
                INSERT INTO tbl_venue_analytics_presence(venue_id,metric_day,metric_type,event_id,source_event_id,viewer_key)
                VALUES (:venue,:day,:type,:event,:source,:viewer)
                """,Map.of("venue",targetVenue,"day",LocalDate.parse(day),"type",type.name(),"event",targetEvent,"source",source,
                "viewer",identity.viewer(targetVenue,viewer)));
    }
    AnalyticsResponse.Summary eventSummary(UUID id) { return store.summary(owner, venue, id, 30, now); }
    void observe(UUID install, AnalyticsRequest.Observation... observations) { store.observe(null, request(install, observations), now); }
    AnalyticsRequest request(UUID install, AnalyticsRequest.Observation... observations) { return new AnalyticsRequest(install,List.of(observations)); }
    AnalyticsRequest.Observation impression(UUID id, Instant at) { return new AnalyticsRequest.Observation(UUID.randomUUID(), AnalyticsRequest.Type.EVENT_IMPRESSION,id,null,null,at); }
    AnalyticsRequest.Observation detail(UUID id, Instant at) { return new AnalyticsRequest.Observation(UUID.randomUUID(), AnalyticsRequest.Type.EVENT_DETAIL_VIEW,id,null,null,at); }
    AnalyticsRequest.Observation profile(UUID id, UUID source, Instant at) { return new AnalyticsRequest.Observation(UUID.randomUUID(), AnalyticsRequest.Type.VENUE_PROFILE_VIEW,null,id,source,at); }
    long count(String table) { return jdbc.getJdbcTemplate().queryForObject("SELECT count(*) FROM "+table,Long.class); }
    static void parallel(int count, IntConsumer operation) throws Exception {
        try (var executor = Executors.newFixedThreadPool(count)) {
            var start = new CountDownLatch(1); var futures = new ArrayList<Future<?>>();
            for (int index=0;index<count;index++) { int value=index; futures.add(executor.submit(() -> { start.await(); operation.accept(value); return null; })); }
            start.countDown(); for (var future:futures) future.get(30,TimeUnit.SECONDS);
        }
    }
}

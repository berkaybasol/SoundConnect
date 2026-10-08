package com.berkayb.soundconnect.modules.analytics;

import com.berkayb.soundconnect.modules.feed.musician.delivery.MusicianFeedDeliveredItem;
import com.berkayb.soundconnect.modules.feed.musician.delivery.MusicianFeedDeliveryLookup;
import com.berkayb.soundconnect.modules.profile.shared.media.enums.ProfileType;
import com.berkayb.soundconnect.modules.promotion.announcement.AnnouncementAccess;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real PostgreSQL migrations, receipt races and reporting; no normal application database access. */
@Testcontainers
class AnnouncementAnalyticsPostgresTest {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:16.4-alpine")
            .withDatabaseName("announcement_analytics_test").withUsername("announcement_test").withPassword("announcement_test").withReuse(false);
    static DriverManagerDataSource datasource;
    static NamedParameterJdbcTemplate jdbc;
    AnalyticsStore collector;
    AnnouncementAnalyticsStore store;
    AnnouncementAccess access;
    MusicianFeedDeliveryLookup deliveries;
    AnalyticsProperties properties;
    final Instant now=Instant.parse("2026-09-13T12:00:00Z");
    UUID user,otherUser,announcement,media,admin,client;

    @BeforeAll static void schema() throws Exception {
        datasource=new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());
        assertDisposable(); jdbc=new NamedParameterJdbcTemplate(datasource);
        jdbc.getJdbcTemplate().execute("""
                CREATE TABLE tbl_user(id uuid PRIMARY KEY,status varchar(30),email_verified boolean);
                CREATE TABLE tbl_role(id uuid PRIMARY KEY,name varchar(30));
                CREATE TABLE user_roles(user_id uuid,role_id uuid);
                CREATE TABLE tbl_venues(id uuid PRIMARY KEY,owner_id uuid REFERENCES tbl_user(id),status varchar(30));
                CREATE TABLE tbl_event(id uuid PRIMARY KEY,venue_id uuid REFERENCES tbl_venues(id),title varchar(255),
                    event_date date,start_time time,event_origin varchar(30),venue_calendar_approved boolean);
                CREATE TABLE tbl_media_asset(id uuid PRIMARY KEY,kind varchar(24),status varchar(24));
                CREATE TABLE tlb_promotion(id uuid PRIMARY KEY,media_asset_id uuid REFERENCES tbl_media_asset(id));
                CREATE TABLE tbl_like(id uuid PRIMARY KEY,target_type varchar(30),target_id uuid);
                CREATE TABLE tbl_comment(id uuid PRIMARY KEY,target_type varchar(30),target_id uuid,is_deleted boolean);
                CREATE TABLE tbl_musician_feed_feedback(id uuid PRIMARY KEY,action varchar(30),item_type varchar(30));
                CREATE TABLE tbl_musician_feed_delivery(id uuid PRIMARY KEY,viewer_user_id uuid,item_type varchar(30),
                    target_type varchar(30),target_id uuid,delivered_at timestamptz,expires_at timestamptz);
                """);
        new ResourceDatabasePopulator(new FileSystemResource("scripts/db/2026-09-08-venue-analytics.sql")).execute(datasource);
        migrate();
    }

    static void assertDisposable() throws Exception {
        assertThat(POSTGRES.isRunning()).isTrue();
        try(var connection=datasource.getConnection()) {
            assertThat(connection.getMetaData().getURL()).isEqualTo(POSTGRES.getJdbcUrl());
            assertThat(connection.getCatalog()).isEqualTo("announcement_analytics_test");
        }
    }
    static void migrate() { new ResourceDatabasePopulator(new FileSystemResource("scripts/db/2026-09-13-announcement-analytics.sql")).execute(datasource); }

    @BeforeEach void setup() throws Exception {
        assertDisposable();
        jdbc.getJdbcTemplate().execute("""
                TRUNCATE tbl_promotion_analytics_event,tbl_promotion_analytics_engagement,
                    tbl_venue_analytics_receipt,tbl_venue_analytics_presence,tbl_venue_analytics_recent_detail,
                    tbl_musician_feed_feedback,tbl_musician_feed_delivery,tbl_like,tbl_comment,
                    tlb_promotion,tbl_media_asset,tbl_event,tbl_venues,user_roles,tbl_role,tbl_user;
                UPDATE tbl_promotion_analytics_state SET tracking_started_at=NULL;
                UPDATE tbl_venue_analytics_state SET tracking_started_at=NULL;
                """);
        user=UUID.randomUUID();otherUser=UUID.randomUUID();admin=UUID.randomUUID();
        announcement=UUID.randomUUID();media=UUID.randomUUID();client=UUID.randomUUID();
        jdbc.update("INSERT INTO tbl_user VALUES(:user,'ACTIVE',true),(:other,'ACTIVE',true)",Map.of("user",user,"other",otherUser));
        jdbc.update("INSERT INTO tbl_media_asset VALUES(:id,'VIDEO','READY')",Map.of("id",media));
        jdbc.update("INSERT INTO tlb_promotion VALUES(:id,:media)",Map.of("id",announcement,"media",media));
        properties=AnalyticsServiceTest.properties();
        var identity=new AnalyticsIdentity(properties); access=mock(AnnouncementAccess.class); deliveries=mock(MusicianFeedDeliveryLookup.class);
        when(access.visibleProfile(any(),any())).thenReturn(Optional.of(ProfileType.MUSICIAN));
        when(access.viewerProfile(any())).thenReturn(ProfileType.MUSICIAN);
        store=new AnnouncementAnalyticsStore(jdbc,identity,properties,access,deliveries);
        collector=new AnalyticsStore(jdbc,new DataSourceTransactionManager(datasource),identity,store);
    }
    @AfterEach void clearRequest() { RequestContextHolder.resetRequestAttributes(); }

    @Test void migrationIsRepeatableAndAnnouncementCollectionDoesNotStartVenueTracking() {
        migrate();
        assertThat(summary(null,null).trackingStartedAt()).isNull();
        observe(user,impression(AnalyticsRequest.Source.DIRECTORY,null,now));
        assertThat(summary(null,null).trackingStartedAt()).isEqualTo(now);
        assertThat(jdbc.queryForObject("select tracking_started_at from tbl_venue_analytics_state",Map.of(),Timestamp.class)).isNull();
    }

    @Test void authorizedAdminAnnouncementReportingDoesNotEnableOrDependOnVenueOwnerLaunchFlag() {
        properties.setReportingEnabled(false);
        observe(user,impression(AnalyticsRequest.Source.DIRECTORY,null,now));
        assertThat(summary(null,null).metrics().impressions()).isEqualTo(1);
        verify(access).requireManage(admin,announcement);
        assertThatThrownBy(()->new AnalyticsIdentity(properties).requireReportingEnabled())
                .isInstanceOf(com.berkayb.soundconnect.shared.exception.ServiceUnavailableRetryException.class);
    }

    @Test void repeatedImpressionsAreOccurrencesButPeriodReachNeverSumsDailyUniquesOrInstallations() {
        observe(user,impression(AnalyticsRequest.Source.DIRECTORY,null,now.minusSeconds(72000)),
                impression(AnalyticsRequest.Source.DIRECTORY,null,now));
        client=UUID.randomUUID(); observe(user,impression(AnalyticsRequest.Source.DIRECTORY,null,now));
        when(access.visibleProfile(eq(otherUser),any())).thenReturn(Optional.of(ProfileType.LISTENER));
        observe(otherUser,impression(AnalyticsRequest.Source.DIRECTORY,null,now));
        var result=summary(null,null);
        assertThat(result.metrics().impressions()).isEqualTo(4); assertThat(result.metrics().uniqueReach()).isEqualTo(2);
        assertThat(result.daily().stream().mapToLong(point->point.metrics().uniqueReach()).sum()).isEqualTo(3);
        assertThat(summary(ProfileType.MUSICIAN,AnalyticsRequest.Source.DIRECTORY).metrics().impressions()).isEqualTo(3);
        assertThat(summary(ProfileType.LISTENER,null).metrics().uniqueReach()).isEqualTo(1);
        assertThat(summary(null,AnalyticsRequest.Source.FEED).metrics().impressions()).isZero();
    }

    @Test void sameReceiptConcurrentRetriesPersistOneOccurrenceAndOneReceipt() throws Exception {
        var request=new AnalyticsRequest(client,List.of(impression(AnalyticsRequest.Source.DIRECTORY,null,now)));
        try(var pool=Executors.newFixedThreadPool(6)) {
            var ready=new CountDownLatch(6);var start=new CountDownLatch(1);var futures=new ArrayList<Future<?>>();
            for(int i=0;i<6;i++) futures.add(pool.submit(()->{ ready.countDown(); start.await(); collector.observe(user,request,now); return null; }));
            assertThat(ready.await(5,TimeUnit.SECONDS)).isTrue();start.countDown();
            for(var future:futures) future.get(15,TimeUnit.SECONDS);
        }
        assertThat(count("tbl_promotion_analytics_event")).isEqualTo(1);
        assertThat(count("tbl_venue_analytics_receipt")).isEqualTo(1);
    }

    @Test void canonicalFeedDeliveryDeduplicatesNewObservationIdsButNewSessionCanContributeAgain() {
        String first=delivery(user,announcement,now.minusSeconds(60));
        observe(user,impression(AnalyticsRequest.Source.FEED,first,now),impression(AnalyticsRequest.Source.FEED,first,now));
        String next=delivery(user,announcement,now.minusSeconds(1));
        observe(user,impression(AnalyticsRequest.Source.FEED,next,now));
        assertThat(summary(null,null).metrics().impressions()).isEqualTo(2);
        assertThat(summary(null,null).metrics().uniqueReach()).isEqualTo(1);
        assertThat(count("tbl_venue_analytics_receipt")).isEqualTo(3);
    }

    @Test void deliveryForAnotherActorAnotherTargetOrFuturePresentationCannotContribute() {
        for(String token:List.of(delivery(otherUser,announcement,now),delivery(user,UUID.randomUUID(),now),
                delivery(user,announcement,now.plusSeconds(121)))) {
            observe(user,impression(AnalyticsRequest.Source.FEED,token,now));
        }
        assertThat(count("tbl_promotion_analytics_event")).isZero();
    }

    @Test void videoCompleteNeedsSamePlaybackActorSourceAndPreviousStartAndIsRetrySafe() {
        UUID playback=UUID.randomUUID();
        observe(user,video(AnalyticsRequest.Type.ANNOUNCEMENT_VIDEO_COMPLETE,playback,now));
        assertThat(summary(null,null).metrics().videoCompletions()).isZero();
        // Same instant, deliberately reversed request order: collector must establish start first.
        observe(user,video(AnalyticsRequest.Type.ANNOUNCEMENT_VIDEO_COMPLETE,playback,now),
                video(AnalyticsRequest.Type.ANNOUNCEMENT_VIDEO_START,playback,now));
        observe(user,video(AnalyticsRequest.Type.ANNOUNCEMENT_VIDEO_COMPLETE,playback,now));
        observe(otherUser,video(AnalyticsRequest.Type.ANNOUNCEMENT_VIDEO_COMPLETE,playback,now));
        assertThat(summary(null,null).metrics().videoStarts()).isEqualTo(1);
        assertThat(summary(null,null).metrics().videoCompletions()).isEqualTo(1);
        jdbc.update("UPDATE tbl_media_asset SET status='PROCESSING'",Map.of());
        observe(user,video(AnalyticsRequest.Type.ANNOUNCEMENT_VIDEO_START,UUID.randomUUID(),now));
        assertThat(summary(null,null).metrics().videoStarts()).isEqualTo(1);
    }

    @Test void stalePublicationAndManagerPreviewsAreAcknowledgedWithoutCountingOrBreakingMixedBatch() {
        when(access.visibleProfile(eq(user),eq(announcement))).thenReturn(Optional.empty());
        observe(user,impression(AnalyticsRequest.Source.DIRECTORY,null,now));
        when(access.canManage(otherUser)).thenReturn(true);
        observe(otherUser,impression(AnalyticsRequest.Source.DIRECTORY,null,now));
        assertThat(count("tbl_promotion_analytics_event")).isZero();
        assertThat(count("tbl_venue_analytics_receipt")).isEqualTo(2);
        verifyNoInteractions(deliveries);
    }

    @Test void historyUsesQualifiedImpressionsForThisAccountAsOfPlanningTime() {
        observe(user,impression(AnalyticsRequest.Source.DIRECTORY,null,now));
        observe(otherUser,impression(AnalyticsRequest.Source.DIRECTORY,null,now));
        assertThat(store.qualifiedImpressionCounts(user,List.of(announcement),now.minusNanos(1))).isEmpty();
        assertThat(store.qualifiedImpressionCounts(user,List.of(announcement),now)).containsExactlyEntriesOf(Map.of(announcement,1L));
        assertThat(store.qualifiedImpressionCounts(UUID.randomUUID(),List.of(announcement),now)).isEmpty();
    }

    @Test void planningHistoryCountsOnlyQualifiedAccountImpressionsInsideTheRollingRecordedWindow() {
        for (Instant recorded : List.of(now.minusSeconds(86_400), now.minusSeconds(25_200), now.minusSeconds(21_600))) {
            collector.observe(user, new AnalyticsRequest(client, List.of(
                    impression(AnalyticsRequest.Source.DIRECTORY, null, recorded))), recorded);
        }
        observe(otherUser, impression(AnalyticsRequest.Source.DIRECTORY, null, now));
        collector.observe(user, new AnalyticsRequest(client, List.of(new AnalyticsRequest.Observation(
                UUID.randomUUID(), AnalyticsRequest.Type.ANNOUNCEMENT_DETAIL_VIEW, null, null, null,
                now, announcement, AnalyticsRequest.Source.DIRECTORY, null, null))), now);
        // A queued older observation recorded after the anchor must not rewrite that plan's frequency state.
        collector.observe(user, new AnalyticsRequest(client, List.of(
                impression(AnalyticsRequest.Source.DIRECTORY, null, now.minusSeconds(3600)))), now.plusNanos(1_000));

        var history = store.qualifiedImpressionHistory(user, List.of(announcement), now).get(announcement);
        assertThat(history.totalCount()).isEqualTo(3);
        assertThat(history.last24HoursCount()).isEqualTo(2);
        assertThat(history.lastRecordedAt()).isEqualTo(now.minusSeconds(21_600));
        assertThat(history.eligibleAt(now)).isFalse();
        assertThat(store.qualifiedImpressionCounts(user, List.of(announcement), now)).containsEntry(announcement, 3L);
        assertThat(store.qualifiedImpressionHistory(user, List.of(UUID.randomUUID()), now)).isEmpty();
        assertThat(store.qualifiedImpressionHistory(UUID.randomUUID(), List.of(announcement), now)).isEmpty();
        assertThat(store.qualifiedImpressionHistory(user, List.of(announcement), now.plusNanos(1_000))
                .get(announcement).last24HoursCount()).isEqualTo(3);
    }

    @Test void announcementRepeatCooldownAllowsExactlySixHoursAndRollingCapDropsAtExactlyTwentyFourHours() {
        Instant first = now.minusSeconds(86_400), second = now.minusSeconds(21_600);
        for (Instant recorded : List.of(first, second)) {
            collector.observe(user, new AnalyticsRequest(client, List.of(
                    impression(AnalyticsRequest.Source.DIRECTORY, null, recorded))), recorded);
        }
        var justBefore = store.qualifiedImpressionHistory(user, List.of(announcement), now.minusNanos(1_000)).get(announcement);
        assertThat(justBefore.last24HoursCount()).isEqualTo(2);
        assertThat(justBefore.eligibleAt(now.minusNanos(1_000))).isFalse();
        var boundary = store.qualifiedImpressionHistory(user, List.of(announcement), now).get(announcement);
        assertThat(boundary.totalCount()).isEqualTo(2);
        assertThat(boundary.last24HoursCount()).isEqualTo(1);
        assertThat(boundary.eligibleAt(now)).isTrue();
        assertThat(store.qualifiedImpressionHistory(user, List.of(announcement), now.plusSeconds(86_400))
                .get(announcement).last24HoursCount()).isZero();
    }

    @Test void serverEngagementAttributionCountsSurvivingEntitiesAndDeduplicatesHideAccounts() {
        UUID like=UUID.randomUUID(),comment=UUID.randomUUID(),hide=UUID.randomUUID(),secondHide=UUID.randomUUID();
        jdbc.update("INSERT INTO tbl_like VALUES(:id,'ANNOUNCEMENT',:target)",Map.of("id",like,"target",announcement));
        jdbc.update("INSERT INTO tbl_comment VALUES(:id,'ANNOUNCEMENT',:target,false)",Map.of("id",comment,"target",announcement));
        jdbc.update("INSERT INTO tbl_musician_feed_feedback VALUES(:id,'HIDE','ANNOUNCEMENT'),(:second,'HIDE','ANNOUNCEMENT')",Map.of("id",hide,"second",secondHide));
        source("DIRECTORY");
        store.recordEngagement(user,announcement,like,AnnouncementAnalyticsStore.EngagementMetric.LIKE,now);
        store.recordEngagement(user,announcement,comment,AnnouncementAnalyticsStore.EngagementMetric.COMMENT,now);
        source("FEED"); // Retry cannot rewrite the original source.
        store.recordEngagement(user,announcement,like,AnnouncementAnalyticsStore.EngagementMetric.LIKE,now);
        store.recordEngagement(user,announcement,hide,AnnouncementAnalyticsStore.EngagementMetric.HIDE,now);
        store.recordEngagement(user,announcement,secondHide,AnnouncementAnalyticsStore.EngagementMetric.HIDE,now);
        assertThat(summary(null,null).metrics()).isEqualTo(new AnnouncementAnalyticsResponse.Metrics(0,0,0,0,0,1,1,1));
        assertThat(summary(null,AnalyticsRequest.Source.DIRECTORY).metrics().likes()).isEqualTo(1);
        assertThat(summary(null,AnalyticsRequest.Source.FEED).metrics().likes()).isZero();
        assertThat(summary(null,AnalyticsRequest.Source.FEED).metrics().hiders()).isEqualTo(1);
        jdbc.update("DELETE FROM tbl_like",Map.of());jdbc.update("UPDATE tbl_comment SET is_deleted=true",Map.of());
        assertThat(summary(null,null).metrics()).isEqualTo(new AnnouncementAnalyticsResponse.Metrics(0,0,0,0,0,0,0,1));
    }

    @Test void historySurvivesPublicationVisibilityChangesAndPermanentDeleteRemovesOnlyItsData() {
        observe(user,impression(AnalyticsRequest.Source.DIRECTORY,null,now));
        when(access.visibleProfile(any(),any())).thenReturn(Optional.empty());
        assertThat(summary(null,null).metrics().impressions()).isEqualTo(1);
        verify(access).requireManage(admin,announcement);
        jdbc.update("DELETE FROM tlb_promotion WHERE id=:id",Map.of("id",announcement));
        assertThat(count("tbl_promotion_analytics_event")).isZero();
        assertThat(count("tbl_venue_analytics_receipt")).isEqualTo(1);
    }

    @Test void reportDateBoundsAndIstanbulMidnightAreExplicit() {
        Instant midnight=Instant.parse("2026-09-12T21:00:00Z");
        observe(user,impression(AnalyticsRequest.Source.DIRECTORY,null,midnight.minusSeconds(1)),impression(AnalyticsRequest.Source.DIRECTORY,null,midnight));
        var result=store.summary(admin,announcement,LocalDate.of(2026,9,13),LocalDate.of(2026,9,13),null,null,now);
        assertThat(result.metrics().impressions()).isEqualTo(1);assertThat(result.daily()).hasSize(1);
        assertThat(result.timeZone()).isEqualTo("Europe/Istanbul");
        assertThatThrownBy(()->store.summary(admin,announcement,LocalDate.of(2025,9,12),LocalDate.of(2026,9,13),null,null,now)).isInstanceOf(SoundConnectException.class);
        assertThatThrownBy(()->store.summary(admin,announcement,null,LocalDate.of(2026,9,14),null,null,now)).isInstanceOf(SoundConnectException.class);
        assertThatThrownBy(()->store.summary(admin,announcement,null,LocalDate.MIN,null,null,now)).isInstanceOf(SoundConnectException.class);
        assertThatThrownBy(()->store.summary(admin,announcement,LocalDate.MIN,LocalDate.MIN,null,null,now)).isInstanceOf(SoundConnectException.class);
    }

    private void source(String source) { var request=new MockHttpServletRequest();request.addHeader("X-Announcement-Source",source);RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request)); }
    private long count(String table) { return jdbc.queryForObject("select count(*) from "+table,Map.of(),Long.class); }
    private AnnouncementAnalyticsResponse.Summary summary(ProfileType profile,AnalyticsRequest.Source source) {
        return store.summary(admin,announcement,null,null,profile,source,now);
    }
    private void observe(UUID actor,AnalyticsRequest.Observation... observations) { collector.observe(actor,new AnalyticsRequest(client,List.of(observations)),now); }
    private AnalyticsRequest.Observation impression(AnalyticsRequest.Source source,String token,Instant at) {
        return new AnalyticsRequest.Observation(UUID.randomUUID(),AnalyticsRequest.Type.ANNOUNCEMENT_IMPRESSION,null,null,null,at,announcement,source,null,token);
    }
    private AnalyticsRequest.Observation video(AnalyticsRequest.Type type,UUID playback,Instant at) {
        return new AnalyticsRequest.Observation(UUID.randomUUID(),type,null,null,null,at,announcement,AnalyticsRequest.Source.DIRECTORY,playback,null);
    }
    private String delivery(UUID actor,UUID target,Instant at) {
        UUID id=UUID.randomUUID();String token=id.toString();
        jdbc.update("INSERT INTO tbl_musician_feed_delivery VALUES(:id,:actor,'ANNOUNCEMENT','ANNOUNCEMENT',:target,:at,:expires)",
                Map.of("id",id,"actor",actor,"target",target,"at",Timestamp.from(at),"expires",Timestamp.from(now.plusSeconds(3600))));
        var delivered=mock(MusicianFeedDeliveredItem.class);when(delivered.deliveryId()).thenReturn(id);
        when(deliveries.requireForObservation(eq(token),any(),any(),any(),any())).thenReturn(delivered);
        return token;
    }
}

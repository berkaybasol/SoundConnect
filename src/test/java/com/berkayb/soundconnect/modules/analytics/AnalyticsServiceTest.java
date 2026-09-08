package com.berkayb.soundconnect.modules.analytics;

import com.berkayb.soundconnect.modules.event.support.EventScheduleClock;
import com.berkayb.soundconnect.shared.exception.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.CannotCreateTransactionException;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AnalyticsServiceTest {
    AnalyticsStore store; AnalyticsRateGuard guard; AnalyticsService service; AnalyticsProperties properties;
    final Instant now=Instant.parse("2026-09-08T12:00:00Z");
    final UUID client=UUID.randomUUID(),event=UUID.randomUUID(),venue=UUID.randomUUID(),owner=UUID.randomUUID();
    @BeforeEach void setup() {
        store=mock(AnalyticsStore.class); guard=mock(AnalyticsRateGuard.class);
        var clock=mock(EventScheduleClock.class); when(clock.instant()).thenReturn(now);
        properties=properties(); service=new AnalyticsService(store,new AnalyticsIdentity(properties),guard,clock);
    }
    static AnalyticsProperties properties() { var props=new AnalyticsProperties(); props.setEnabled(true); props.setReportingEnabled(true); props.setHmacSecret("test-only-secret-for-analytics-32-bytes"); return props; }
    AnalyticsRequest.Observation observation() { return new AnalyticsRequest.Observation(UUID.randomUUID(),AnalyticsRequest.Type.EVENT_IMPRESSION,event,null,null,now); }
    AnalyticsRequest request() { return new AnalyticsRequest(client,List.of(observation())); }
    @Test void acknowledgementsPreserveIdsAndActorIdentityOnlyAfterDurableStoreReturns() {
        var request=request(); assertThat(service.observe(owner,request).acknowledgedIds()).containsExactly(request.observations().getFirst().id());
        var order=inOrder(guard,store); order.verify(guard).observations(owner,client,1); order.verify(store).observe(owner,request,now);
    }
    @Test void disabledFlagFailsClosedBeforeQuotaOrDatabase() {
        properties.setEnabled(false);
        assertThatThrownBy(() -> service.observe(null,request())).isInstanceOf(ServiceUnavailableRetryException.class);
        assertThatThrownBy(() -> service.summary(owner,venue,null,30)).isInstanceOf(ServiceUnavailableRetryException.class);
        verifyNoInteractions(store,guard);
    }
    @Test void reportingDefaultsOffAndBlocksAllReadMethodsWithoutTouchingStorageOrQuota() {
        assertThat(new AnalyticsProperties().isReportingEnabled()).isFalse();
        properties.setReportingEnabled(false);
        for (Runnable read : List.<Runnable>of(() -> service.requireReportingEnabled(),
                () -> service.summary(owner,venue,null,30), () -> service.summary(owner,venue,event,30),
                () -> service.events(owner,venue,30,0,20), () -> service.events(owner,venue,30,0,20,AnalyticsResponse.EventSort.REACH))) {
            var failure=catchThrowableOfType(read::run,ServiceUnavailableRetryException.class);
            assertThat(failure.getErrorType()).isEqualTo(ErrorType.ANALYTICS_UNAVAILABLE);
            assertThat(failure.getRetryAfterSeconds()).isEqualTo(30);
        }
        verifyNoInteractions(store,guard);
    }
    @Test void disablingReportingPreservesCollectionAcknowledgementAndCleanup() {
        properties.setReportingEnabled(false);
        var batch=request();
        assertThat(service.observe(null,batch).acknowledgedIds()).containsExactly(batch.observations().getFirst().id());
        verify(guard).observations(null,client,1); verify(store).observe(null,batch,now);
        var clock=mock(EventScheduleClock.class); when(clock.instant()).thenReturn(now);
        new AnalyticsCleanup(properties,store,clock).cleanup();
        verify(store).cleanup(now);
        verifyNoMoreInteractions(store,guard);
    }
    @Test void reportingRequiresExistingCollectionEnablementAndCanBeReopenedWithoutDataMutation() {
        properties.setReportingEnabled(false);
        assertThatThrownBy(() -> service.summary(owner,venue,null,30)).isInstanceOf(ServiceUnavailableRetryException.class);
        properties.setReportingEnabled(true);
        service.summary(owner,venue,null,30); verify(store).summary(owner,venue,null,30,now);
        reset(store); properties.setEnabled(false);
        assertThatThrownBy(() -> service.events(owner,venue,30,0,20,AnalyticsResponse.EventSort.DATE)).isInstanceOf(ServiceUnavailableRetryException.class);
        verifyNoInteractions(store);
    }
    @Test void storageAndTransactionFailuresHaveRetryable503WithoutAck() {
        for (RuntimeException failure:List.of(new DataAccessResourceFailureException("private database info"),new CannotCreateTransactionException("private connection"))) {
            doThrow(failure).when(store).observe(any(),any(),any());
            var result=catchThrowableOfType(() -> service.observe(owner,request()),ServiceUnavailableRetryException.class);
            assertThat(result.getErrorType()).isEqualTo(ErrorType.ANALYTICS_UNAVAILABLE); assertThat(result.getRetryAfterSeconds()).isEqualTo(30);
        }
    }
    @Test void conflictIsPermanentAndQuotaFailureNeverStartsStorage() {
        doThrow(new SoundConnectException(ErrorType.ANALYTICS_CONFLICT)).when(store).observe(any(),any(),any());
        var result=catchThrowableOfType(() -> service.observe(owner,request()),SoundConnectException.class);
        assertThat(result.getErrorType()).isEqualTo(ErrorType.ANALYTICS_CONFLICT);
        reset(store); doThrow(new RateLimitedException(ErrorType.ANALYTICS_RATE_LIMITED,15)).when(guard).observations(any(),any(),anyInt());
        assertThatThrownBy(() -> service.observe(owner,request())).isInstanceOf(RateLimitedException.class); verifyNoInteractions(store);
    }
    @ParameterizedTest @ValueSource(ints={-1,0,1,6,8,29,31,89,91,Integer.MAX_VALUE})
    void daysMustBeAllowedWindows(int days) { assertThatThrownBy(() -> service.summary(owner,venue,null,days)).isInstanceOf(SoundConnectException.class); verifyNoInteractions(store); }
    @ParameterizedTest @CsvSource({"-1,20","1001,20","0,0","0,51","2147483647,2147483647"})
    void pagesBoundedBeforeStorage(int page,int size) { assertThatThrownBy(() -> service.events(owner,venue,30,page,size)).isInstanceOf(SoundConnectException.class); verifyNoInteractions(store); }
    @ParameterizedTest @ValueSource(ints={7,30,90})
    void allowedWindowsAndMaximumPageReachStore(int days) { service.events(owner,venue,days,1000,50); verify(store).events(owner,venue,days,1000,50,now); }
    @ParameterizedTest @EnumSource(AnalyticsResponse.EventSort.class)
    void everySupportedSortReachesStoreWithItsOwnerAndBounds(AnalyticsResponse.EventSort sort) {
        service.events(owner,venue,7,1000,50,sort);
        verify(store).events(owner,venue,7,1000,50,sort,now);
    }
    @Test void sortedReadsValidateNullSortOwnerDaysAndPageBeforeStorage() {
        assertThatThrownBy(() -> service.events(owner,venue,30,0,20,null)).isInstanceOf(SoundConnectException.class);
        assertThatThrownBy(() -> service.events(null,venue,30,0,20,AnalyticsResponse.EventSort.REACH)).isInstanceOf(SoundConnectException.class);
        assertThatThrownBy(() -> service.events(owner,venue,31,0,20,AnalyticsResponse.EventSort.REACH)).isInstanceOf(SoundConnectException.class);
        assertThatThrownBy(() -> service.events(owner,venue,30,1001,20,AnalyticsResponse.EventSort.REACH)).isInstanceOf(SoundConnectException.class);
        assertThatThrownBy(() -> service.events(owner,venue,30,0,51,AnalyticsResponse.EventSort.REACH)).isInstanceOf(SoundConnectException.class);
        verifyNoInteractions(store);
    }
    @Test void sortedReadStorageFailuresRemainRetryableAndPrivateSummaryMetadataIsUnchanged() {
        when(store.events(owner,venue,30,0,20,AnalyticsResponse.EventSort.REACH,now))
                .thenThrow(new DataAccessResourceFailureException("private database info"));
        assertThatThrownBy(() -> service.events(owner,venue,30,0,20,AnalyticsResponse.EventSort.REACH))
                .isInstanceOf(ServiceUnavailableRetryException.class);
        var today=now.atZone(AnalyticsStore.ZONE).toLocalDate();
        var comparison=new AnalyticsResponse.PeriodComparison(AnalyticsResponse.ComparisonStatus.NOT_STARTED,
                today.minusDays(30),today.minusDays(1),today.minusDays(60),today.minusDays(31),null,null);
        var result=new AnalyticsResponse.Summary(venue,null,today.minusDays(29),today,30,AnalyticsStore.ZONE.getId(),null,now,
                AnalyticsResponse.Metrics.ZERO,List.of(new AnalyticsResponse.DailyPoint(today,null,true)),comparison);
        when(store.summary(owner,venue,null,30,now)).thenReturn(result);
        assertThat(service.summary(owner,venue,null,30)).isSameAs(result);
    }
    @Test void invalidBatchShapesDuplicateIdsAndZeroTargetsFailBeforeQuota() {
        var observation=observation();
        var requests=new ArrayList<AnalyticsRequest>();
        requests.add(null); requests.add(new AnalyticsRequest(null,List.of(observation))); requests.add(new AnalyticsRequest(AnalyticsIdentity.NONE,List.of(observation)));
        requests.add(new AnalyticsRequest(client,null)); requests.add(new AnalyticsRequest(client,List.of()));
        requests.add(new AnalyticsRequest(client,Collections.nCopies(21,observation))); requests.add(new AnalyticsRequest(client,List.of(observation,observation)));
        requests.add(new AnalyticsRequest(client,Arrays.asList((AnalyticsRequest.Observation)null)));
        requests.add(new AnalyticsRequest(client,List.of(new AnalyticsRequest.Observation(UUID.randomUUID(),AnalyticsRequest.Type.EVENT_IMPRESSION,AnalyticsIdentity.NONE,null,null,now))));
        requests.add(new AnalyticsRequest(client,List.of(new AnalyticsRequest.Observation(UUID.randomUUID(),AnalyticsRequest.Type.VENUE_PROFILE_VIEW,event,venue,null,now))));
        for(var request:requests) assertThatThrownBy(() -> service.observe(null,request)).isInstanceOf(SoundConnectException.class);
        verifyNoInteractions(store,guard);
    }
    @Test void missingOwnerNeverQueriesPrivateCounts() { assertThatThrownBy(() -> service.summary(null,venue,null,30)).isInstanceOf(SoundConnectException.class); verifyNoInteractions(store); }
    @Test void hmacScopesAreStableButVenueAccountInstallationAndQuotaAreUnlinkable() {
        var identity=new AnalyticsIdentity(properties);
        var anonymous=identity.actor(null,client); var account=identity.actor(owner,client);
        assertThat(identity.viewer(venue,anonymous)).hasSize(32).isEqualTo(identity.viewer(venue,anonymous))
                .isNotEqualTo(identity.viewer(UUID.randomUUID(),anonymous)).isNotEqualTo(identity.viewer(venue,account));
        assertThat(identity.actor(owner,UUID.randomUUID())).isEqualTo(account);
        assertThat(identity.quotaKey("ip","203.0.113.12")).hasSize(64).doesNotContain("203.0.113.12");
        assertThat(identity.receiptActor(anonymous)).isNotEqualTo(identity.viewer(venue,anonymous));
    }
    @ParameterizedTest @ValueSource(strings={"","short","                                "})
    void missingWeakOrWhitespaceSecretCannotEnableCollection(String secret) { properties.setHmacSecret(secret); assertThatThrownBy(() -> new AnalyticsIdentity(properties)).isInstanceOf(IllegalStateException.class); }
    @Test void disabledConfigurationDoesNotRequireSecretOrTouchInfrastructure() { var props=new AnalyticsProperties(); var identity=new AnalyticsIdentity(props); assertThatThrownBy(identity::requireEnabled).isInstanceOf(ServiceUnavailableRetryException.class); }
    @Test void cleanupCatchesUpWithBoundedTransactionsAndStopsWhenCaughtUp() {
        var clock=mock(EventScheduleClock.class); when(clock.instant()).thenReturn(now);
        var cleanup=new AnalyticsCleanup(properties,store,clock);
        when(store.cleanup(now)).thenReturn(1000); cleanup.cleanup(); verify(store,times(20)).cleanup(now);
        reset(store); when(store.cleanup(now)).thenReturn(1000,4); cleanup.cleanup(); verify(store,times(2)).cleanup(now);
        reset(store); properties.setEnabled(false); cleanup.cleanup(); verify(store).schemaInstalled(); verifyNoMoreInteractions(store);
        reset(store); when(store.schemaInstalled()).thenReturn(true); cleanup.cleanup(); verify(store).cleanup(now);
    }
    @Test void healthIsPayloadFreeAndDisabledDoesNotQueryUninstalledSchema() {
        var clock=mock(EventScheduleClock.class); when(clock.instant()).thenReturn(now);
        var health=new AnalyticsHealthIndicator(properties,store,clock);
        when(store.retentionHealthy(now)).thenReturn(true); assertThat(health.health().getStatus().getCode()).isEqualTo("UP");
        when(store.retentionHealthy(now)).thenReturn(false); assertThat(health.health().getStatus().getCode()).isEqualTo("DEGRADED");
        when(store.retentionHealthy(now)).thenThrow(new DataAccessResourceFailureException("private info"));
        assertThat(health.health().getDetails()).containsOnly(entry("reason","schema_or_storage_unavailable"));
        reset(store); properties.setEnabled(false); assertThat(health.health().getStatus().getCode()).isEqualTo("UP"); verifyNoInteractions(store);
    }
}

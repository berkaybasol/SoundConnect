package com.berkayb.soundconnect.modules.notification.push;

import com.berkayb.soundconnect.modules.notification.entity.Notification;
import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.modules.notification.repository.NotificationRepository;
import com.berkayb.soundconnect.modules.notification.service.NotificationDeliveryPolicy;
import com.berkayb.soundconnect.modules.notification.push.transport.PushSendResult;
import com.berkayb.soundconnect.modules.user.support.AccountDeliveryFence;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.berkayb.soundconnect.modules.notification.push.transport.PushTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Opt-in isolated PostgreSQL schema; never reads application configuration or connects to its datasource. */
@EnabledIfSystemProperty(named="push.test.jdbc-url",matches="jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/soundconnect_push_test")
class PushFoundationPostgresTest {
    JdbcTemplate jdbc,admin;
    NamedParameterJdbcTemplate sql;
    TransactionTemplate tx;
    PushProperties properties;
    PushTokenCipher cipher;
    PushDeviceService devices;
    PushDeliveryPlanner planner;
    PushDeliveryStore store;
    PushOperations operations;
    NotificationRepository notifications;
    NotificationDeliveryPolicy policy;
    Map<UUID,Notification> inbox;
    Clock clock=Clock.fixed(Instant.parse("2026-09-22T12:00:00Z"),ZoneOffset.UTC);
    UUID user,other,installation;
    String schema;
    HikariDataSource datasource;
    DataSourceTransactionManager transactionManager;
    final Map<UUID,Long> revisions=new ConcurrentHashMap<>();

    @BeforeEach void setup() throws Exception {
        setup(System.getProperty("push.test.jdbc-url"),
                System.getProperty("push.test.jdbc-user","push_test"),
                System.getProperty("push.test.jdbc-password",""));
    }
    void setup(String url,String username,String password) throws Exception {
        if(url==null || !url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/soundconnect_push_test")) {
            throw new IllegalArgumentException("Push fixture requires a loopback soundconnect_push_test database");
        }
        admin=new JdbcTemplate(new DriverManagerDataSource(url,username,password));
        schema="push_test_"+UUID.randomUUID().toString().replace("-","");
        admin.execute("create schema "+schema);
        datasource=new HikariDataSource(); datasource.setJdbcUrl(url+"?currentSchema="+schema);
        datasource.setUsername(username); datasource.setPassword(password);
        datasource.setMaximumPoolSize(16); datasource.setMinimumIdle(0);
        jdbc=new JdbcTemplate(datasource); sql=new NamedParameterJdbcTemplate(jdbc);
        transactionManager=new DataSourceTransactionManager(datasource);
        tx=new TransactionTemplate(transactionManager);
        jdbc.execute("create table tbl_user(id uuid primary key, erased_at timestamptz, status varchar(20),email_verified boolean)");
        jdbc.execute("create table tbl_notification(id uuid primary key, recipient_id uuid not null references tbl_user(id), is_read boolean not null default false)");
        String migration=Files.readString(Path.of("scripts/db/2026-09-22-push-delivery-foundation.sql"));
        jdbc.execute(migration);
        jdbc.execute(migration); // Safe replay is part of the deployment contract.
        String revisionsMigration=Files.readString(Path.of("scripts/db/2026-09-23-push-device-registration-revision.sql"));
        jdbc.execute(revisionsMigration); jdbc.execute(revisionsMigration);
        jdbc.execute(Files.readString(Path.of("scripts/db/2026-09-24-push-native-venue-capability.sql")));
        jdbc.execute("create table tbl_venues(id uuid primary key,owner_id uuid,status text)");
        jdbc.execute("create table tbl_venue_applications(id uuid primary key,user_id uuid,status text,application_date timestamp,decision_date timestamp,created_at timestamp)");
        jdbc.execute("alter table tbl_notification add column type varchar(64) not null default 'DM_NEW_MESSAGE'");
        jdbc.execute("alter table tbl_notification add constraint legacy_notification_type check(type in ('DM_NEW_MESSAGE','VENUE_APPLICATION_REJECTED','ARTIST_VENUE_LINK_APPLICATION_REQUEST'))");
        jdbc.execute(Files.readString(Path.of("scripts/db/2026-09-24-venue-application-notifications.sql")));
        jdbc.execute(Files.readString(Path.of("scripts/db/2026-09-24-push-native-studio-capability.sql")));
        jdbc.execute(Files.readString(Path.of("scripts/db/2026-09-28-push-native-follow-capability.sql")));
        jdbc.execute(Files.readString(Path.of("scripts/db/2026-09-29-push-native-media-capability.sql")));
        jdbc.execute(Files.readString(Path.of("scripts/db/2026-09-29-push-native-band-capability.sql")));
        user=UUID.randomUUID(); other=UUID.randomUUID(); installation=UUID.randomUUID();
        for(UUID id:List.of(user,other)) jdbc.update("insert into tbl_user values(?,null,'ACTIVE',true)",id);
        properties=new PushProperties(); properties.setEnabled(true);
        properties.setTokenEncryptionKey(Base64.getEncoder().encodeToString(new byte[32]));
        cipher=new PushTokenCipher(properties);
        devices=new PushDeviceService(sql,new AccountDeliveryFence(sql),cipher,properties,clock);
        planner=new PushDeliveryPlanner(sql,properties,clock);
        notifications=mock(NotificationRepository.class); policy=mock(NotificationDeliveryPolicy.class);
        when(policy.eligible(any())).thenReturn(true);
        inbox=new ConcurrentHashMap<>();
        when(notifications.findById(any())).thenAnswer(call->Optional.ofNullable(inbox.get(call.getArgument(0))));
        var storeProxy=new ProxyFactory(new PushDeliveryStore(sql,notifications,policy,cipher,properties,clock));
        storeProxy.setProxyTargetClass(true);
        storeProxy.addAdvice(new TransactionInterceptor(transactionManager,new AnnotationTransactionAttributeSource()));
        store=(PushDeliveryStore)storeProxy.getProxy();
        operations=new PushOperations(sql,properties,clock);
    }
    @AfterEach void cleanup() {
        if(datasource!=null) datasource.close();
        if(admin!=null && schema!=null) admin.execute("drop schema "+schema+" cascade");
    }
    static List<String> jdbcOptInSettings() {
        return Arrays.asList(System.getProperty("push.test.jdbc-url"),
                System.getProperty("push.test.jdbc-user"),System.getProperty("push.test.jdbc-password"));
    }
    private void register(UUID owner,UUID device,String token) {
        tx.executeWithoutResult(status->devices.register(owner,device,new PushDeviceService.Registration(
                token,PushDeviceService.Platform.ANDROID,PushDeviceService.Permission.AUTHORIZED,"test",revisions.merge(device,1L,Long::sum),null)));
    }
    private Notification notification() {
        var notification=Notification.builder().sourceEventId(UUID.randomUUID()).recipientId(user)
                .type(NotificationType.DM_NEW_MESSAGE).title("Private sender").message("Private message")
                .payload(Map.of("conversationId",UUID.randomUUID().toString())).occurredAt(clock.instant()).read(false).build();
        ReflectionTestUtils.setField(notification,"id",UUID.randomUUID());
        jdbc.update("insert into tbl_notification(id,recipient_id) values(?,?)",notification.getId(),user);
        inbox.put(notification.getId(),notification); return notification;
    }
    private void plan(Notification notification) { tx.executeWithoutResult(status->planner.plan(new NotificationPersisted(notification))); }
    private PushDeliveryStore.Claim claim() { return tx.execute(status->store.claimNext().orElseThrow()); }
    private String state(UUID id) { return jdbc.queryForObject("select status from tbl_push_delivery where id=?",String.class,id); }

    @Test void inboxAndDeliveryRollbackTogetherAndPlanIsIdempotent() {
        register(user,installation,"device-token");
        assertThatThrownBy(()->tx.executeWithoutResult(status->{
            var notification=notification(); planner.plan(new NotificationPersisted(notification));
            throw new IllegalStateException("domain rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("select count(*) from tbl_notification",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from tbl_push_delivery",Integer.class)).isZero();
        var notification=notification(); plan(notification); plan(notification);
        assertThat(jdbc.queryForObject("select count(*) from tbl_push_delivery",Integer.class)).isEqualTo(1);
    }
    @Test void plansAllDevicesButNotDeniedStaleOrCategoryDisabledDevices() {
        register(user,installation,"one"); register(user,UUID.randomUUID(),"two");
        var denied=UUID.randomUUID(); register(user,denied,"denied");
        jdbc.update("update tbl_push_device set permission='DENIED' where installation_id=?",denied);
        var notification=notification(); plan(notification);
        assertThat(jdbc.queryForObject("select count(*) from tbl_push_delivery",Integer.class)).isEqualTo(2);
        tx.executeWithoutResult(s->devices.updatePreferences(user,new PushDeviceService.Preferences(true,Set.of("DM"))));
        plan(notification());
        assertThat(jdbc.queryForObject("select count(*) from tbl_push_delivery",Integer.class)).isEqualTo(2);
    }
    @Test void encryptedRegistrationAndAccountSwitchPreventOldDeliveryAndOldLogoutCannotRevokeNewOwner() {
        register(user,installation,"secret-device-token");
        String encrypted=jdbc.queryForObject("select token_ciphertext from tbl_push_device",String.class);
        assertThat(encrypted).doesNotContain("secret-device-token"); assertThat(cipher.decrypt(encrypted)).isEqualTo("secret-device-token");
        plan(notification()); var oldClaim=claim();
        register(other,installation,"secret-device-token");
        tx.executeWithoutResult(s->devices.revoke(user,installation,revisions.merge(installation,1L,Long::sum)));
        assertThat(tx.<Optional<com.berkayb.soundconnect.modules.notification.push.transport.PushEnvelope>>execute(s->store.prepare(oldClaim))).isEmpty();
        assertThat(state(oldClaim.id())).isEqualTo("SUPPRESSED");
        assertThat(jdbc.queryForObject("select revoked_at is null from tbl_push_device",Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("select user_id from tbl_push_device",UUID.class)).isEqualTo(other);
    }
    @Test void readRevokedAndPreferencesAreRecheckedAtSendTime() {
        register(user,installation,"one"); var n=notification(); plan(n); var claim=claim();
        jdbc.update("update tbl_notification set is_read=true where id=?",n.getId());
        assertThat(tx.<Optional<com.berkayb.soundconnect.modules.notification.push.transport.PushEnvelope>>execute(s->store.prepare(claim))).isEmpty();
        assertThat(state(claim.id())).isEqualTo("SUPPRESSED");
        var next=notification(); plan(next); var nextClaim=claim();
        tx.executeWithoutResult(s->devices.updatePreferences(user,new PushDeviceService.Preferences(false,Set.of())));
        assertThat(tx.<Optional<com.berkayb.soundconnect.modules.notification.push.transport.PushEnvelope>>execute(s->store.prepare(nextClaim))).isEmpty();
    }
    @Test void genericPayloadContainsNoPrivateSenderOrMessageAndAcceptanceIsSeparateFromRead() {
        register(user,installation,"one"); var n=notification(); plan(n); var claim=claim();
        var envelope=tx.execute(s->store.prepare(claim)).orElseThrow();
        assertThat(envelope.title()).isEqualTo("Soundconnect");
        assertThat(envelope.body()).doesNotContain(n.getMessage()).doesNotContain(n.getTitle());
        assertThat(envelope.data()).containsEntry("recipientId",user.toString()).containsEntry("notificationId",n.getId().toString());
        tx.executeWithoutResult(s->store.complete(claim,PushSendResult.accepted("projects/test/messages/one"),null));
        assertThat(state(claim.id())).isEqualTo("ACCEPTED");
        assertThat(jdbc.queryForObject("select is_read from tbl_notification where id=?",Boolean.class,n.getId())).isFalse();
    }
    @Test void providerRetryAfterIsRespectedAndExpiredJobsAreNeverSent() {
        register(user,installation,"one"); plan(notification()); var claim=claim();
        tx.executeWithoutResult(s->store.complete(claim,PushSendResult.failed(PushSendResult.Outcome.RETRYABLE_FAILURE,"QUOTA_EXCEEDED",Duration.ofMinutes(2)),null));
        assertThat(state(claim.id())).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("select next_attempt_at from tbl_push_delivery where id=?",java.sql.Timestamp.class,claim.id()).toInstant())
                .isEqualTo(clock.instant().plusSeconds(120));
        jdbc.update("update tbl_push_delivery set next_attempt_at=?,expires_at=? where id=?",java.sql.Timestamp.from(clock.instant()),java.sql.Timestamp.from(clock.instant().minusSeconds(1)),claim.id());
        var expired=claim(); assertThat(tx.<Optional<com.berkayb.soundconnect.modules.notification.push.transport.PushEnvelope>>execute(s->store.prepare(expired))).isEmpty();
        assertThat(state(claim.id())).isEqualTo("SUPPRESSED");
    }
    @Test void abandonedLeaseIsRecoveredAndStaleWorkerCannotCompleteNewLease() {
        register(user,installation,"one"); plan(notification()); var old=claim();
        assertThat(tx.<Optional<PushDeliveryStore.Claim>>execute(s->store.claimNext())).isEmpty();
        jdbc.update("update tbl_push_delivery set lease_until=? where id=?",java.sql.Timestamp.from(clock.instant().minusSeconds(1)),old.id());
        var fresh=claim(); assertThat(fresh.leaseOwner()).isNotEqualTo(old.leaseOwner());
        tx.executeWithoutResult(s->store.complete(old,PushSendResult.accepted("old"),null));
        assertThat(state(old.id())).isEqualTo("IN_FLIGHT");
        tx.executeWithoutResult(s->store.complete(fresh,PushSendResult.accepted("fresh"),null));
        assertThat(jdbc.queryForObject("select provider_message_id from tbl_push_delivery where id=?",String.class,old.id())).isEqualTo("fresh");
    }
    @Test void invalidOldTokenResultDoesNotRevokeRefreshedToken() {
        register(user,installation,"old-token"); plan(notification()); var old=claim();
        register(user,installation,"new-token");
        tx.executeWithoutResult(s->store.complete(old,PushSendResult.failed(PushSendResult.Outcome.INVALID_DEVICE,"UNREGISTERED",null),PushTokenCipher.hash("old-token")));
        assertThat(jdbc.queryForObject("select revoked_at is null from tbl_push_device",Boolean.class)).isTrue();
        assertThat(cipher.decrypt(jdbc.queryForObject("select token_ciphertext from tbl_push_device",String.class))).isEqualTo("new-token");
        assertThat(state(old.id())).isEqualTo("PENDING");
        jdbc.update("update tbl_push_delivery set next_attempt_at=? where id=?",java.sql.Timestamp.from(clock.instant()),old.id());
        var retry=claim();
        assertThat(tx.execute(s->store.prepare(retry)).orElseThrow().token()).isEqualTo("new-token");
    }
    @Test void erasurePurgesDevicesPreferencesAndAssociatedJobs() {
        register(user,installation,"one"); plan(notification());
        tx.executeWithoutResult(s->devices.updatePreferences(user,new PushDeviceService.Preferences(true,Set.of())));
        jdbc.update("update tbl_user set erased_at=now() where id=?",user);
        assertThat(jdbc.queryForObject("select count(*) from tbl_push_device",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from tbl_push_preference",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from tbl_push_delivery",Integer.class)).isZero();
    }
    @Test void exhaustedJobsAreManageableButAcceptedOrExpiredJobsCannotBeReplayed() {
        properties.setMaxAttempts(1); register(user,installation,"one"); plan(notification()); var claim=claim();
        tx.executeWithoutResult(s->store.complete(claim,PushSendResult.failed(PushSendResult.Outcome.RETRYABLE_FAILURE,"UNAVAILABLE",null),null));
        assertThat(operations.summary().degraded()).isTrue(); assertThat(operations.failed(10)).hasSize(1);
        assertThat(tx.<Boolean>execute(s->operations.retry(claim.id(),other))).isTrue();
        var fresh=claim(); tx.executeWithoutResult(s->store.complete(fresh,PushSendResult.accepted("accepted"),null));
        assertThat(tx.<Boolean>execute(s->operations.retry(claim.id(),other))).isFalse();
    }
    @Test void parallelWorkersClaimEveryJobOnlyOnce() throws Exception {
        register(user,installation,"one");
        tx.executeWithoutResult(s->{ for(int i=0;i<100;i++) planner.plan(new NotificationPersisted(notification())); });
        Set<UUID> claimed=ConcurrentHashMap.newKeySet();
        try(var pool=Executors.newFixedThreadPool(4)) {
            var futures=new ArrayList<Future<?>>();
            for(int i=0;i<4;i++) futures.add(pool.submit(()->{
                while(true) {
                    var next=tx.execute(s->store.claimNext()); if(next.isEmpty()) return;
                    assertThat(claimed.add(next.get().id())).isTrue();
                    tx.executeWithoutResult(s->store.complete(next.get(),PushSendResult.accepted("test"),null));
                }
            }));
            for(var future:futures) future.get(30,TimeUnit.SECONDS);
        }
        assertThat(claimed).hasSize(100);
        assertThat(operations.summary().counts()).containsEntry("ACCEPTED",100L);
    }
    @Test void tokenRefreshKeepsPendingJobsAndOnlyUsesNewToken() {
        register(user,installation,"old-token"); plan(notification());
        register(user,installation,"new-token"); var claim=claim();
        assertThat(tx.<Optional<com.berkayb.soundconnect.modules.notification.push.transport.PushEnvelope>>execute(s->store.prepare(claim)).orElseThrow().token()).isEqualTo("new-token");
    }
    @Test void staleInvalidDeviceResultCannotRevokeRegistrationAfterLeaseRecovery() {
        register(user,installation,"one"); plan(notification()); var old=claim();
        jdbc.update("update tbl_push_delivery set lease_until=? where id=?",java.sql.Timestamp.from(clock.instant().minusSeconds(1)),old.id());
        var current=claim(); tx.executeWithoutResult(s->store.complete(current,PushSendResult.accepted("new"),null));
        tx.executeWithoutResult(s->store.complete(old,PushSendResult.failed(PushSendResult.Outcome.INVALID_DEVICE,"UNREGISTERED",null),PushTokenCipher.hash("one")));
        assertThat(jdbc.queryForObject("select revoked_at is null from tbl_push_device",Boolean.class)).isTrue();
        assertThat(state(old.id())).isEqualTo("ACCEPTED");
    }

    private void registerRevision(UUID owner,UUID device,String token,long revision) {
        tx.executeWithoutResult(s->devices.register(owner,device,new PushDeviceService.Registration(token,
                PushDeviceService.Platform.ANDROID,PushDeviceService.Permission.AUTHORIZED,"test",revision,null)));
    }
    @Test void lateRegistrationLogoutAndTokenRefreshCannotOverwriteNewerMutation() {
        registerRevision(user,installation,"first-token",1);
        tx.executeWithoutResult(s->devices.revoke(user,installation,2L));
        registerRevision(other,installation,"current-token",3);
        registerRevision(user,installation,"late-first-token",1);
        tx.executeWithoutResult(s->devices.revoke(user,installation,2L));
        // A foreign owner's later logout still cannot revoke the current binding.
        tx.executeWithoutResult(s->devices.revoke(user,installation,4L));
        assertThat(jdbc.queryForObject("select user_id from tbl_push_device",UUID.class)).isEqualTo(other);
        assertThat(jdbc.queryForObject("select revoked_at is null from tbl_push_device",Boolean.class)).isTrue();
        assertThat(cipher.decrypt(jdbc.queryForObject("select token_ciphertext from tbl_push_device",String.class))).isEqualTo("current-token");
        registerRevision(other,installation,"rotated-token",6);
        registerRevision(other,installation,"late-token",5);
        assertThat(cipher.decrypt(jdbc.queryForObject("select token_ciphertext from tbl_push_device",String.class))).isEqualTo("rotated-token");
        tx.executeWithoutResult(s->devices.revoke(other,installation,7L));
        registerRevision(other,installation,"new-session",8);
        tx.executeWithoutResult(s->devices.revoke(other,installation,7L));
        assertThat(jdbc.queryForObject("select revoked_at is null from tbl_push_device",Boolean.class)).isTrue();
    }
    @Test void logoutBeforeFirstRegistrationLeavesTombstoneAndEqualRevisionIsNoOp() {
        tx.executeWithoutResult(s->devices.revoke(user,installation,2L));
        registerRevision(user,installation,"late-first-registration",1);
        registerRevision(user,installation,"conflicting-equal-revision",2);
        assertThat(jdbc.queryForObject("select token_ciphertext from tbl_push_device",String.class)).isNull();
        assertThat(jdbc.queryForObject("select revoked_at is not null from tbl_push_device",Boolean.class)).isTrue();
        registerRevision(user,installation,"new-session",3);
        var snapshot=jdbc.queryForMap("select * from tbl_push_device");
        registerRevision(other,installation,"same-revision-different-owner",3);
        tx.executeWithoutResult(s->devices.revoke(user,installation,3L));
        assertThat(jdbc.queryForMap("select * from tbl_push_device")).isEqualTo(snapshot);
    }
    @Test void concurrentFirstRegistrationsAndLogoutAlwaysKeepHighestRevision() throws Exception {
        try(var pool=Executors.newFixedThreadPool(8)) {
            var start=new CountDownLatch(1); var tasks=new ArrayList<Future<?>>();
            for(int i=1;i<=32;i++) {
                final int revision=i;
                tasks.add(pool.submit(()->{
                    start.await();
                    if(revision==32) tx.executeWithoutResult(s->devices.revoke(user,installation,32L));
                    else registerRevision(user,installation,"fixture-token-"+revision,revision);
                    return null;
                }));
            }
            start.countDown(); for(var task:tasks) task.get(20,TimeUnit.SECONDS);
        }
        assertThat(jdbc.queryForObject("select client_revision from tbl_push_device",Long.class)).isEqualTo(32);
        assertThat(jdbc.queryForObject("select token_hash is null and revoked_at is not null from tbl_push_device",Boolean.class)).isTrue();
    }
    @Test void invalidRevisionAndOverflowAreRejectedBeforeMutationAndStoredTombstonesAreBounded() {
        for(Long invalid:Arrays.asList(null,0L,-1L,PushDeviceService.MAX_CLIENT_REVISION+1,Long.MAX_VALUE)) {
            assertThatThrownBy(()->tx.executeWithoutResult(s->devices.revoke(user,installation,invalid)))
                    .isInstanceOf(com.berkayb.soundconnect.shared.exception.SoundConnectException.class);
        }
        assertThat(jdbc.queryForObject("select count(*) from tbl_push_device",Integer.class)).isZero();
        registerRevision(user,installation,"last-safe-integer",PushDeviceService.MAX_CLIENT_REVISION);
        assertThat(jdbc.queryForObject("select client_revision from tbl_push_device",Long.class)).isEqualTo(PushDeviceService.MAX_CLIENT_REVISION);
        properties.setMaxStoredDevicesPerUser(2);
        tx.executeWithoutResult(s->devices.revoke(user,UUID.randomUUID(),1L));
        assertThatThrownBy(()->tx.executeWithoutResult(s->devices.revoke(user,UUID.randomUUID(),1L)))
                .isInstanceOf(com.berkayb.soundconnect.shared.exception.SoundConnectException.class);
        assertThat(jdbc.queryForObject("select count(*) from tbl_push_device",Integer.class)).isEqualTo(2);
    }
    @Test void ownershipTransferCannotBypassStoredDeviceCapacityButOldOwnerCanAdvanceRevokeFence() {
        properties.setMaxStoredDevicesPerUser(1);
        UUID otherInstallation=UUID.randomUUID();
        registerRevision(user,installation,"current-owner-token",1);
        registerRevision(other,otherInstallation,"other-owner-token",1);
        assertThatThrownBy(()->registerRevision(user,otherInstallation,"other-owner-token",2))
                .isInstanceOf(com.berkayb.soundconnect.shared.exception.SoundConnectException.class);
        assertThat(jdbc.queryForObject("select user_id from tbl_push_device where installation_id=?",UUID.class,otherInstallation)).isEqualTo(other);
        tx.executeWithoutResult(s->devices.revoke(user,otherInstallation,3L));
        assertThat(jdbc.queryForObject("select client_revision from tbl_push_device where installation_id=?",Long.class,otherInstallation)).isEqualTo(3);
        assertThat(jdbc.queryForObject("select token_hash from tbl_push_device where installation_id=?",String.class,otherInstallation))
                .isEqualTo(PushTokenCipher.hash("other-owner-token"));
        registerRevision(user,installation,"refreshed-own-token",2);
        assertThat(jdbc.queryForObject("select count(*) from tbl_push_device where user_id=?",Integer.class,user)).isEqualTo(1);
    }
    @Test void presentationCapabilityIsValidatedAndClearedOnRevocation() {
        tx.executeWithoutResult(s->devices.register(user,installation,new PushDeviceService.Registration("rich-fixture",
                PushDeviceService.Platform.ANDROID,PushDeviceService.Permission.AUTHORIZED,"test",1L,"ANDROID_DM_V1")));
        assertThat(jdbc.queryForObject("select presentation_version from tbl_push_device",String.class)).isEqualTo("ANDROID_DM_V1");
        assertThatThrownBy(()->tx.executeWithoutResult(s->devices.register(user,installation,new PushDeviceService.Registration("invalid-ios",
                PushDeviceService.Platform.IOS,PushDeviceService.Permission.AUTHORIZED,"test",2L,"ANDROID_DM_V1"))))
                .isInstanceOf(com.berkayb.soundconnect.shared.exception.SoundConnectException.class);
        tx.executeWithoutResult(s->devices.revoke(user,installation,3L));
        assertThat(jdbc.queryForObject("select presentation_version from tbl_push_device",String.class)).isNull();
    }
    @Test void revisionMigrationReplaysWithoutChangingExistingDevicesAndEnablementRequiresMarker() throws Exception {
        register(user,installation,"legacy-fixture-token");
        jdbc.update("update tbl_push_device set client_revision=0");
        var before=jdbc.queryForMap("select * from tbl_push_device");
        String migration=Files.readString(Path.of("scripts/db/2026-09-23-push-device-registration-revision.sql"));
        jdbc.execute(migration); jdbc.execute(migration);
        jdbc.execute(Files.readString(Path.of("scripts/db/2026-09-24-push-native-venue-capability.sql")));
        assertThat(jdbc.queryForMap("select * from tbl_push_device")).isEqualTo(before);
        assertThatThrownBy(()->operations.run(new org.springframework.boot.DefaultApplicationArguments()))
                .hasMessageContaining("venue-application-notifications");
        jdbc.execute(Files.readString(Path.of("scripts/db/2026-09-24-venue-application-notifications.sql")));
        jdbc.execute(Files.readString(Path.of("scripts/db/2026-09-24-push-native-studio-capability.sql")));
        jdbc.execute(Files.readString(Path.of("scripts/db/2026-09-28-push-native-follow-capability.sql")));
        jdbc.execute(Files.readString(Path.of("scripts/db/2026-09-29-push-native-media-capability.sql")));
        jdbc.execute(Files.readString(Path.of("scripts/db/2026-09-29-push-native-band-capability.sql")));
        assertThatThrownBy(() -> operations.run(new org.springframework.boot.DefaultApplicationArguments())).hasMessageContaining("push-native-table-capability");
        jdbc.update("delete from soundconnect_schema_migrations where migration_id='2026-09-23-push-device-registration-revision'");
        assertThatThrownBy(()->operations.run(new org.springframework.boot.DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("push-device-registration-revision");
    }
    @Test void deviceCleanupCannotAbortInboxTransactionBetweenSelectionAndJobInsert() throws Exception {
        register(user,installation,"fixture-token");
        var selected=new CountDownLatch(1); var allowInsert=new CountDownLatch(1);
        var hookedSql=new NamedParameterJdbcTemplate(jdbc) {
            @Override public <T> List<T> query(String query,Map<String,?> params,RowMapper<T> mapper) {
                var result=super.query(query,params,mapper);
                if(query.contains("select d.installation_id")) {
                    selected.countDown();
                    try { if(!allowInsert.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("Fixture barrier timeout"); }
                    catch(InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
                }
                return result;
            }
        };
        var hookedPlanner=new PushDeliveryPlanner(hookedSql,properties,clock);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var planned=pool.submit(()->tx.executeWithoutResult(s->hookedPlanner.plan(new NotificationPersisted(notification()))));
            assertThat(selected.await(5,TimeUnit.SECONDS)).isTrue();
            var deleted=pool.submit(()->tx.executeWithoutResult(s->jdbc.update("delete from tbl_push_device where installation_id=?",installation)));
            try {
                assertThatThrownBy(()->deleted.get(150,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            } finally { allowInsert.countDown(); }
            planned.get(5,TimeUnit.SECONDS); deleted.get(5,TimeUnit.SECONDS);
        } finally { allowInsert.countDown(); }
        assertThat(jdbc.queryForObject("select count(*) from tbl_notification",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from tbl_push_delivery",Integer.class)).isZero();
    }
    private ThreadPoolTaskExecutor workers() {
        var executor=new PushConfiguration().pushDeliveryExecutor(properties); executor.initialize(); return executor;
    }
    private void awaitIdle(ThreadPoolTaskExecutor workers) throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(workers.getActiveCount()>0 && System.nanoTime()<until) Thread.sleep(10);
        assertThat(workers.getActiveCount()).isZero();
    }
    private void drainDue(PushDispatcher dispatcher,ThreadPoolTaskExecutor workers) throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        boolean pending;
        do {
            dispatcher.poll(); Thread.sleep(20);
            pending=Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from tbl_push_delivery where status='IN_FLIGHT' or (status='PENDING' and next_attempt_at<=?))",
                    Boolean.class,java.sql.Timestamp.from(clock.instant())));
        } while(pending && System.nanoTime()<until);
        assertThat(pending).isFalse(); awaitIdle(workers);
    }
    @Test void twoDeviceWorkerRetryDoesNotDuplicateAcceptedPeerAndThrowingProviderRecovers() throws Exception {
        properties.setMaxSendsPerSecond(1000); properties.setBatchSize(25);
        register(user,installation,"one"); register(user,UUID.randomUUID(),"two");
        for(int i=0;i<3;i++) plan(notification());
        var attempts=new ConcurrentHashMap<String,AtomicInteger>();
        PushTransport provider=envelope->{
            String key=envelope.data().get("notificationId")+":"+envelope.token();
            int attempt=attempts.computeIfAbsent(key,ignored->new AtomicInteger()).incrementAndGet();
            if(envelope.token().equals("one") && attempt==1) throw new IllegalStateException("fixture provider outage");
            return PushSendResult.accepted("fixture-accepted");
        };
        var workers=workers();
        var metrics=new SimpleMeterRegistry();
        try {
            var dispatcher=new PushDispatcher(store,provider,properties,workers,metrics,clock);
            drainDue(dispatcher,workers);
            assertThat(operations.summary().counts()).containsEntry("ACCEPTED",3L).containsEntry("PENDING",3L);
            jdbc.update("update tbl_push_delivery set next_attempt_at=? where status='PENDING'",java.sql.Timestamp.from(clock.instant()));
            drainDue(dispatcher,workers);
            assertThat(operations.summary().counts()).containsOnlyKeys("ACCEPTED").containsEntry("ACCEPTED",6L);
            assertThat(attempts.values().stream().mapToInt(AtomicInteger::get).sum()).isEqualTo(9);
            assertThat(jdbc.queryForObject("select count(*) from tbl_notification where is_read",Integer.class)).isZero();
        } finally { workers.shutdown(); metrics.close(); }
    }
    @Test void invalidDeviceOnlySuppressesItsQueueAndPermanentErrorsRemainInspectable() {
        register(user,installation,"one"); var second=UUID.randomUUID(); register(user,second,"two");
        plan(notification()); plan(notification());
        while(true) {
            var next=store.claimNext(); if(next.isEmpty()) break;
            var claim=next.get(); var envelope=store.prepare(claim); if(envelope.isEmpty()) continue;
            var outcome=envelope.get().token().equals("one") ? PushSendResult.Outcome.INVALID_DEVICE : PushSendResult.Outcome.PERMANENT_FAILURE;
            store.complete(claim,PushSendResult.failed(outcome,outcome==PushSendResult.Outcome.INVALID_DEVICE?"UNREGISTERED":"SENDER_ID_MISMATCH",null),PushTokenCipher.hash(envelope.get().token()));
        }
        assertThat(operations.summary().counts()).containsEntry("SUPPRESSED",2L).containsEntry("DEAD_LETTER",2L);
        assertThat(operations.failed(10)).allSatisfy(job->assertThat(job.lastErrorCode()).isEqualTo("SENDER_ID_MISMATCH"));
        assertThat(jdbc.queryForObject("select revoked_at is null from tbl_push_device where installation_id=?",Boolean.class,second)).isTrue();
    }
    @Test @EnabledIfSystemProperty(named="push.test.benchmark",matches="true")
    void representativeLocalBurstDrainsAtConfiguredRateWithFakeProvider() throws Exception {
        int notificationsCount=150, jobs=notificationsCount*2;
        register(user,installation,"one"); register(user,UUID.randomUUID(),"two");
        tx.executeWithoutResult(s->{for(int i=0;i<notificationsCount;i++) planner.plan(new NotificationPersisted(notification()));});
        var delivered=ConcurrentHashMap.<String>newKeySet(); var delays=Collections.synchronizedList(new ArrayList<Long>());
        long started=System.nanoTime();
        PushTransport provider=envelope->{
            assertThat(delivered.add(envelope.data().get("notificationId")+":"+envelope.token())).isTrue();
            try { Thread.sleep(5); } catch(InterruptedException ex) { Thread.currentThread().interrupt(); }
            delays.add(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started));
            return PushSendResult.accepted("fixture-accepted");
        };
        var samples=new ArrayList<Map<String,Object>>(); var workers=workers();
        var metrics=new SimpleMeterRegistry();
        try {
            var dispatcher=new PushDispatcher(store,provider,properties,workers,metrics,clock);
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(90);
            while(System.nanoTime()<until) {
                dispatcher.poll(); var summary=operations.summary();
                long accepted=summary.counts().getOrDefault("ACCEPTED",0L);
                samples.add(Map.of("elapsedMs",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started),"accepted",accepted,
                        "pending",summary.counts().getOrDefault("PENDING",0L),"inFlight",summary.counts().getOrDefault("IN_FLIGHT",0L)));
                if(accepted==jobs) break;
                Thread.sleep(200);
            }
            awaitIdle(workers);
            long elapsed=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started);
            assertThat(operations.summary().counts()).containsOnlyKeys("ACCEPTED").containsEntry("ACCEPTED",(long)jobs);
            assertThat(delivered).hasSize(jobs);
            assertThat(elapsed).isGreaterThanOrEqualTo(14_000);
            var sorted=delays.stream().sorted().toList();
            var result=new LinkedHashMap<String,Object>();
            result.put("notificationCount",notificationsCount); result.put("deliveryJobs",jobs);
            result.put("workers",properties.getWorkerThreads()); result.put("configuredAttemptsPerSecond",properties.getMaxSendsPerSecond());
            result.put("fakeProviderDelayMs",5); result.put("elapsedMs",elapsed); result.put("acceptedJobsPerSecond",jobs*1000.0/elapsed);
            result.put("p50BurstQueueDelayMs",sorted.get(jobs/2)); result.put("p95BurstQueueDelayMs",sorted.get((int)(jobs*.95)));
            result.put("peakBacklog",jobs); result.put("finalBacklog",0); result.put("duplicateSubmissions",0);
            result.put("realFcmRequests",0); result.put("sourceEligibility","mocked"); result.put("productionCapacityClaim",false);
            result.put("samples",samples);
            new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of(System.getProperty("push.test.benchmark-output")).toFile(),result);
        } finally { workers.shutdown(); metrics.close(); }
    }
}

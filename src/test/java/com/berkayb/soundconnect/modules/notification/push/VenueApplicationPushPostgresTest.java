package com.berkayb.soundconnect.modules.notification.push;

import com.berkayb.soundconnect.modules.application.venueapplication.entity.VenueApplication;
import com.berkayb.soundconnect.modules.application.venueapplication.enums.ApplicationStatus;
import com.berkayb.soundconnect.modules.application.venueapplication.service.VenueApplicationDecisionNotifications;
import com.berkayb.soundconnect.modules.application.venueapplication.service.VenueApplicationDecisionEligibility;
import com.berkayb.soundconnect.modules.notification.entity.Notification;
import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.modules.notification.helper.NotificationBadgeCacheHelper;
import com.berkayb.soundconnect.modules.notification.mapper.NotificationMapper;
import com.berkayb.soundconnect.modules.notification.repository.NotificationReceiptRepository;
import com.berkayb.soundconnect.modules.notification.service.*;
import com.berkayb.soundconnect.modules.notification.websocket.NotificationWebSocketService;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.support.AccountDeliveryFence;
import com.berkayb.soundconnect.shared.messaging.events.notification.NotificationInboundEvent;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.berkayb.soundconnect.modules.notification.enums.NotificationType.*;

/** Real isolated PostgreSQL constraints, account/source locks, receipts and jobs; provider/mail never invoked. */
@EnabledIfSystemProperty(named="push.test.jdbc-url",matches="jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/soundconnect_push_test")
class VenueApplicationPushPostgresTest {
    private final PushFoundationPostgresTest f=new PushFoundationPostgresTest();
    private UUID app, venue;
    private VenueApplicationDecisionNotifications producer;
    private final Map<UUID,Notification> delivered=new ConcurrentHashMap<>();

    @BeforeEach void setup() throws Exception {
        f.clock=Clock.systemUTC(); f.setup();
        f.jdbc.execute("alter table tbl_user alter column status type varchar(40)");
        f.jdbc.execute("create table tbl_role(id uuid primary key,name text)");
        f.jdbc.execute("create table user_roles(user_id uuid,role_id uuid)");
        f.jdbc.execute("create table user_permissions(user_id uuid,permission_id uuid)");
        f.jdbc.execute("alter table tbl_notification add column source_event_id uuid unique");
        f.jdbc.execute("create table tbl_notification_receipt(source_event_id uuid primary key,recipient_id uuid,recorded_at timestamptz)");
        f.jdbc.update("update tbl_user set status='PENDING_VENUE_REQUEST' where id=?",f.user);
        app=UUID.randomUUID(); venue=UUID.randomUUID();
        f.jdbc.update("insert into tbl_venue_applications(id,user_id,status,application_date,created_at) values(?,?,'PENDING',?,?)",
                app,f.user,Timestamp.from(f.clock.instant()),Timestamp.from(f.clock.instant()));
        f.properties.setAllowedTypes(EnumSet.of(VENUE_APPLICATION_APPROVED,VENUE_APPLICATION_REJECTED,DM_NEW_MESSAGE,ARTIST_VENUE_LINK_APPLICATION_REQUEST));
        f.policy=new NotificationDeliveryPolicy(new AccountDeliveryFence(f.sql),f.sql,f.transactionManager,mock(AfterCommitDeliveryExecutor.class));
        var proxy=new ProxyFactory(new PushDeliveryStore(f.sql,f.notifications,f.policy,f.cipher,f.properties,f.clock));
        proxy.setProxyTargetClass(true); proxy.addAdvice(new TransactionInterceptor(f.transactionManager,new AnnotationTransactionAttributeSource()));
        f.store=(PushDeliveryStore)proxy.getProxy();
        doAnswer(call->Optional.ofNullable(delivered.get(call.getArgument(0)))).when(f.notifications).findById(any());
        when(f.notifications.existsBySourceEventId(any())).thenAnswer(call->f.jdbc.queryForObject(
                "select exists(select 1 from tbl_notification where source_event_id=?)",Boolean.class,call.getArgument(0,UUID.class)));
        when(f.notifications.saveAndFlush(any())).thenAnswer(call->{
            Notification n=call.getArgument(0); ReflectionTestUtils.setField(n,"id",UUID.randomUUID());
            f.jdbc.update("insert into tbl_notification(id,recipient_id,source_event_id,type) values(?,?,?,?)",n.getId(),n.getRecipientId(),n.getSourceEventId(),n.getType().name());
            delivered.put(n.getId(),n); return n;
        });
        var receipts=mock(NotificationReceiptRepository.class);
        when(receipts.claim(any(),any())).thenAnswer(call->f.jdbc.update(
                "insert into tbl_notification_receipt values(?,?,current_timestamp) on conflict do nothing",call.getArgument(0,UUID.class),call.getArgument(1,UUID.class)));
        var transactional=new TransactionalNotificationService(f.notifications,mock(NotificationMapper.class),mock(NotificationBadgeCacheHelper.class),
                mock(NotificationWebSocketService.class),mock(NotificationService.class),receipts,f.policy,
                event->f.planner.plan((NotificationPersisted)event));
        producer=new VenueApplicationDecisionNotifications(transactional);
        registerScoped(1);
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void v6PreparesBothApplicationDecisionsWithScope(boolean approved) {
        f.tx.executeWithoutResult(s->f.devices.registerApplication(f.user,app,f.installation,
                registration(2,MediaPushPresentation.CAPABILITY)));
        exactVerifiedDecisionCreatesOneAtomicReceiptInboxJobAndPrivateDataOnlyPayload(approved);
        int before=count("tbl_push_delivery");
        plan(unrelated(DM_NEW_MESSAGE,null));
        plan(unrelated(VENUE_APPLICATION_REJECTED,UUID.randomUUID()));
        assertThat(count("tbl_push_delivery")).isEqualTo(before);
    }

    @AfterEach void cleanup() { f.cleanup(); }
    private PushDeviceService.Registration registration(long revision,String capability) {
        return new PushDeviceService.Registration("application-fixture-token",PushDeviceService.Platform.ANDROID,
                PushDeviceService.Permission.AUTHORIZED,"application-test",revision,capability);
    }
    private void registerScoped(long revision) {
        f.tx.executeWithoutResult(s->f.devices.registerApplication(f.user,app,f.installation,registration(revision,VenueApplicationPushPresentation.CAPABILITY)));
    }
    private VenueApplication decide(boolean approved) {
        if(approved) {
            f.jdbc.update("update tbl_user set status='ACTIVE' where id=?",f.user);
            UUID role=UUID.randomUUID(); f.jdbc.update("insert into tbl_role values(?,'ROLE_VENUE')",role);
            f.jdbc.update("insert into user_roles values(?,?)",f.user,role);
            f.jdbc.update("insert into tbl_venues values(?,?,'APPROVED')",venue,f.user);
        }
        var state=approved?ApplicationStatus.APPROVED:ApplicationStatus.REJECTED;
        var now=LocalDateTime.now(ZoneOffset.UTC);
        f.jdbc.update("update tbl_venue_applications set status=?,decision_date=?,approved_venue_id=? where id=?",state.name(),now,approved?venue:null,app);
        return VenueApplication.builder().id(app).applicant(User.builder().id(f.user).build()).status(state).decisionDate(now).build();
    }
    private Notification publish(boolean approved) {
        f.tx.executeWithoutResult(s->producer.decided(decide(approved)));
        UUID id=f.jdbc.queryForObject("select id from tbl_notification",UUID.class); return delivered.get(id);
    }
    private NotificationInboundEvent event(Notification n) {
        return NotificationInboundEvent.builder().recipientId(n.getRecipientId()).type(n.getType()).payload(n.getPayload()).build();
    }
    private int count(String table) { return f.jdbc.queryForObject("select count(*) from "+table,Integer.class); }
    private Notification unrelated(NotificationType type,UUID application) {
        var n=Notification.builder().recipientId(f.user).sourceEventId(UUID.randomUUID()).type(type).occurredAt(f.clock.instant())
                .payload(application==null?Map.of():Map.of("applicationId",application.toString())).read(false).build();
        ReflectionTestUtils.setField(n,"id",UUID.randomUUID());
        f.jdbc.update("insert into tbl_notification(id,recipient_id) values(?,?)",n.getId(),f.user); delivered.put(n.getId(),n); return n;
    }
    private void plan(Notification n) { f.tx.executeWithoutResult(s->f.planner.plan(new NotificationPersisted(n))); }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void exactVerifiedDecisionCreatesOneAtomicReceiptInboxJobAndPrivateDataOnlyPayload(boolean approved) {
        var n=publish(approved);
        assertThat(count("tbl_notification_receipt")).isEqualTo(1);
        assertThat(count("tbl_notification")).isEqualTo(1); assertThat(count("tbl_push_delivery")).isEqualTo(1);
        var envelope=f.store.prepare(f.store.claimNext().orElseThrow()).orElseThrow();
        assertThat(envelope.data()).containsOnlyKeys("notificationId","recipientId","type","presentationVersion","displayVariant","sentAt","expiresAt")
                .containsEntry("presentationVersion",VenueApplicationPushPresentation.VERSION).containsEntry("displayVariant","DEFAULT");
        assertThat(envelope.data().toString()).doesNotContain("applicationId","reason","venueName","phone");
        assertThat(n.getPayload()).containsOnlyKeys("module","applicationId","applicantUserId","action","status");
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void v4ApplicationRegistrationPreservesExactScopeAndDecisionDelivery(boolean approved) {
        f.tx.executeWithoutResult(s->f.devices.registerApplication(f.user,app,f.installation,
                registration(2,StudioPushPresentation.CAPABILITY)));
        assertThat(f.jdbc.queryForObject("select application_scope_id from tbl_push_device",UUID.class)).isEqualTo(app);
        var n=publish(approved);
        var envelope=f.store.prepare(f.store.claimNext().orElseThrow()).orElseThrow();
        assertThat(envelope.data()).containsEntry("presentationVersion",VenueApplicationPushPresentation.VERSION)
                .containsEntry("notificationId",n.getId().toString());
        int before=count("tbl_push_delivery");
        plan(unrelated(DM_NEW_MESSAGE,null));
        plan(unrelated(VENUE_APPLICATION_REJECTED,UUID.randomUUID()));
        assertThat(count("tbl_push_delivery")).isEqualTo(before);
    }
    @Test void domainRollbackAlsoRollsBackReceiptInboxAndJobAndAllowsExactRetry() {
        assertThatThrownBy(()->f.tx.executeWithoutResult(s->{producer.decided(decide(false));throw new IllegalStateException("domain failure");}))
                .isInstanceOf(IllegalStateException.class);
        for(String table:List.of("tbl_notification_receipt","tbl_notification","tbl_push_delivery")) assertThat(count(table)).isZero();
        assertThat(f.jdbc.queryForObject("select status from tbl_venue_applications",String.class)).isEqualTo("PENDING");
        publish(false); assertThat(count("tbl_push_delivery")).isEqualTo(1);
    }
    @Test void concurrentProducerRetryAndReplayAfterInboxDeletionNeverDuplicate() throws Exception {
        var source=f.tx.execute(s->decide(false));
        try(var executor=Executors.newFixedThreadPool(2)) {
            var gate=new CountDownLatch(1);
            var a=executor.submit(()->{gate.await();f.tx.executeWithoutResult(s->producer.decided(source));return true;});
            var b=executor.submit(()->{gate.await();f.tx.executeWithoutResult(s->producer.decided(source));return true;});
            gate.countDown(); assertThat(a.get(5,TimeUnit.SECONDS)).isTrue(); assertThat(b.get(5,TimeUnit.SECONDS)).isTrue();
        }
        assertThat(count("tbl_notification")).isEqualTo(1);assertThat(count("tbl_push_delivery")).isEqualTo(1);
        f.jdbc.execute("delete from tbl_notification"); f.tx.executeWithoutResult(s->producer.decided(source));
        assertThat(count("tbl_notification")).isZero();assertThat(count("tbl_notification_receipt")).isEqualTo(1);
    }
    @Test void approvalDoesNotUnscopeDeviceOrEnableDmAndBusinessUntilFreshNormalRegistration() {
        var n=publish(true); long generation=f.jdbc.queryForObject("select generation from tbl_push_device",Long.class);
        var oldClaim=f.store.claimNext().orElseThrow();
        plan(unrelated(DM_NEW_MESSAGE,null)); plan(unrelated(ARTIST_VENUE_LINK_APPLICATION_REQUEST,null));
        plan(unrelated(VENUE_APPLICATION_APPROVED,UUID.randomUUID()));
        assertThat(count("tbl_push_delivery")).isEqualTo(1);
        f.tx.executeWithoutResult(s->f.devices.register(f.user,f.installation,registration(1,VenueApplicationPushPresentation.CAPABILITY)));
        assertThat(f.jdbc.queryForObject("select application_scope_id from tbl_push_device",UUID.class)).isEqualTo(app);
        f.tx.executeWithoutResult(s->f.devices.register(f.user,f.installation,registration(2,VenueApplicationPushPresentation.CAPABILITY)));
        assertThat(f.jdbc.queryForObject("select application_scope_id from tbl_push_device",UUID.class)).isNull();
        assertThat(f.jdbc.queryForObject("select generation from tbl_push_device",Long.class)).isEqualTo(generation+1);
        assertThat(f.store.prepare(oldClaim)).isEmpty();
        plan(unrelated(DM_NEW_MESSAGE,null)); assertThat(count("tbl_push_delivery")).isEqualTo(2);
    }
    @Test void pendingCannotUseNormalRegisterAndWrongOrUnverifiedScopedSourceIsRejected() {
        assertThatThrownBy(()->f.tx.executeWithoutResult(s->f.devices.register(f.user,f.installation,registration(2,VenueApplicationPushPresentation.CAPABILITY))))
                .isInstanceOf(com.berkayb.soundconnect.shared.exception.SoundConnectException.class);
        assertThatThrownBy(()->f.tx.executeWithoutResult(s->f.devices.registerApplication(f.other,app,UUID.randomUUID(),registration(2,VenueApplicationPushPresentation.CAPABILITY))))
                .isInstanceOf(com.berkayb.soundconnect.shared.exception.SoundConnectException.class);
        f.jdbc.update("update tbl_user set email_verified=false where id=?",f.user);
        assertThatThrownBy(()->registerScoped(2)).isInstanceOf(com.berkayb.soundconnect.shared.exception.SoundConnectException.class);
    }
    @ParameterizedTest @ValueSource(strings={"erased","unverified","inactive","studio","directPermission","listener","changedOwner","changedState","newerApplication"})
    void queuedRejectionRechecksEveryCurrentSourceAndAccountBoundary(String change) {
        var n=publish(false); var claim=f.store.claimNext().orElseThrow();
        switch(change) {
            case "erased" -> f.jdbc.update("update tbl_user set erased_at=current_timestamp where id=?",f.user);
            case "unverified" -> f.jdbc.update("update tbl_user set email_verified=false where id=?",f.user);
            case "inactive" -> f.jdbc.update("update tbl_user set status='INACTIVE' where id=?",f.user);
            case "studio" -> f.jdbc.update("update tbl_user set status='PENDING_STUDIO_REQUEST' where id=?",f.user);
            case "directPermission" -> f.jdbc.update("insert into user_permissions values(?,?)",f.user,UUID.randomUUID());
            case "listener" -> {UUID role=UUID.randomUUID();f.jdbc.update("insert into tbl_role values(?,'ROLE_LISTENER')",role);f.jdbc.update("insert into user_roles values(?,?)",f.user,role);}
            case "changedOwner" -> f.jdbc.update("update tbl_venue_applications set user_id=? where id=?",f.other,app);
            case "changedState" -> f.jdbc.update("update tbl_venue_applications set status='PENDING' where id=?",app);
            case "newerApplication" -> f.jdbc.update("insert into tbl_venue_applications(id,user_id,status,application_date,created_at) values(?,?,'PENDING',current_timestamp+interval '1 day',current_timestamp)",UUID.randomUUID(),f.user);
        }
        assertThat(f.store.prepare(claim)).isEmpty();
        if(change.equals("erased")) {
            // Existing account-erasure trigger deletes private devices; jobs cascade.
            assertThat(count("tbl_push_device")).isZero();assertThat(count("tbl_push_delivery")).isZero();
        } else assertThat(f.jdbc.queryForObject("select status from tbl_push_delivery",String.class)).isEqualTo("SUPPRESSED");
    }
    @ParameterizedTest @ValueSource(strings={"owner","status","deleted"})
    void approvedRequiresExactCreatedVenueAndCurrentOwnership(String change) {
        publish(true);var claim=f.store.claimNext().orElseThrow();
        if(change.equals("owner")) f.jdbc.update("update tbl_venues set owner_id=? where id=?",f.other,venue);
        if(change.equals("status")) f.jdbc.update("update tbl_venues set status='REJECTED' where id=?",venue);
        if(change.equals("deleted")) f.jdbc.update("delete from tbl_venues where id=?",venue);
        f.jdbc.update("insert into tbl_venues values(?,?,'APPROVED')",UUID.randomUUID(),f.user);
        assertThat(f.store.prepare(claim)).isEmpty();
    }
    @ParameterizedTest @ValueSource(strings={"permission","global","category","revoked","wrongScope","oldCapability"})
    void finalDeviceAndPreferenceFencesCannotBeBypassed(String change) {
        publish(false);var claim=f.store.claimNext().orElseThrow();
        switch(change) {
            case "permission" -> f.jdbc.execute("update tbl_push_device set permission='DENIED'");
            case "global" -> f.jdbc.update("insert into tbl_push_preference values(?,false,'{}',current_timestamp)",f.user);
            case "category" -> f.jdbc.update("insert into tbl_push_preference values(?,true,'{VENUE}',current_timestamp)",f.user);
            case "revoked" -> f.tx.executeWithoutResult(s->f.devices.revokeApplication(f.user,app,f.installation,2L));
            case "wrongScope" -> f.jdbc.update("update tbl_push_device set application_scope_id=?",UUID.randomUUID());
            case "oldCapability" -> f.jdbc.execute("update tbl_push_device set application_scope_id=null,presentation_version='ANDROID_NATIVE_V2'");
        }
        assertThat(f.store.prepare(claim)).isEmpty();
    }
    @Test void scopedActiveDeviceCannotHaveNullCapabilityAndDeletedApplicationNeverRemovesRestriction() {
        assertThatThrownBy(()->f.jdbc.execute("update tbl_push_device set presentation_version=null"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        f.tx.executeWithoutResult(s->f.devices.revokeApplication(f.user,app,f.installation,2L));
        assertThat(f.jdbc.queryForObject("select presentation_version from tbl_push_device",String.class)).isNull();
        assertThat(f.jdbc.queryForObject("select application_scope_id from tbl_push_device",UUID.class)).isEqualTo(app);
        f.jdbc.update("delete from tbl_venue_applications where id=?",app);
        assertThat(f.jdbc.queryForObject("select application_scope_id from tbl_push_device",UUID.class)).isEqualTo(app);
        f.jdbc.execute("alter table tbl_push_device drop constraint ck_push_device_application_scope");
        f.jdbc.execute("alter table tbl_push_device add constraint ck_push_device_application_scope check(true)");
        assertThatThrownBy(()->f.operations.run(new DefaultApplicationArguments())).hasMessageContaining("venue-application-notifications");
    }

    @Test void enumMigrationExtendsOnlyPureTypeChecksAndPreservesCompoundRules() throws Exception {
        f.jdbc.execute("alter table tbl_notification add constraint compound_message_business check(type<>'VENUE_APPLICATION_APPROVED' or is_read=false)");
        String before=f.jdbc.queryForObject("select pg_get_constraintdef(oid) from pg_constraint where conrelid='tbl_notification'::regclass and conname='compound_message_business'",String.class);
        f.jdbc.execute(Files.readString(Path.of("scripts/db/2026-09-24-venue-application-notifications.sql")));
        assertThat(f.jdbc.queryForObject("select pg_get_constraintdef(oid) from pg_constraint where conrelid='tbl_notification'::regclass and conname='compound_message_business'",String.class)).isEqualTo(before);
        publish(true);
        assertThatThrownBy(()->f.jdbc.update("update tbl_notification set type='UNKNOWN_TYPE'")).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->f.jdbc.update("update tbl_notification set is_read=true")).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test void oldV2DevicesNeverGetApplicationJobsAndMigrationReplayPreservesDevices() throws Exception {
        var before=f.jdbc.queryForList("select * from tbl_push_device");
        String migration=Files.readString(Path.of("scripts/db/2026-09-24-venue-application-notifications.sql"));
        f.jdbc.execute(migration);f.jdbc.execute(migration);
        assertThat(f.jdbc.queryForList("select * from tbl_push_device")).isEqualTo(before);
        f.jdbc.execute(Files.readString(Path.of("scripts/db/2026-09-24-push-native-studio-capability.sql")));
        f.jdbc.execute(Files.readString(Path.of("scripts/db/2026-09-28-push-native-follow-capability.sql")));
        assertThatThrownBy(() -> f.operations.run(new DefaultApplicationArguments())).hasMessageContaining("push-native-table-capability");
        assertThat(f.jdbc.queryForObject("select exists(select 1 from pg_indexes where schemaname=current_schema() and indexname='idx_venue_application_applicant_latest')",Boolean.class)).isTrue();
        f.jdbc.execute("update tbl_push_device set application_scope_id=null,presentation_version='ANDROID_NATIVE_V2'");
        publish(false);assertThat(count("tbl_push_delivery")).isZero();
        f.jdbc.update("delete from soundconnect_schema_migrations where migration_id='2026-09-24-venue-application-notifications'");
        assertThatThrownBy(()->f.operations.run(new DefaultApplicationArguments())).hasMessageContaining("venue-application-notifications");
    }
    private com.berkayb.soundconnect.modules.application.venueapplication.service.VenueApplicationServiceImpl domain(boolean failAfterNotification) {
        var applications=mock(com.berkayb.soundconnect.modules.application.venueapplication.repository.VenueApplicationRepository.class);
        var users=mock(com.berkayb.soundconnect.modules.user.repository.UserRepository.class);
        var mapper=mock(com.berkayb.soundconnect.modules.application.venueapplication.mapper.VenueApplicationMapper.class);
        when(applications.findByIdForUpdate(app)).thenAnswer(call->Optional.of(f.jdbc.queryForObject(
                "select status,decision_date from tbl_venue_applications where id=? for update",(rs,index)->VenueApplication.builder()
                        .id(app).applicant(User.builder().id(f.user).build()).status(ApplicationStatus.valueOf(rs.getString(1)))
                        .decisionDate(rs.getTimestamp(2)==null?null:rs.getTimestamp(2).toLocalDateTime()).build(),app)));
        when(users.findByIdForUpdate(f.user)).thenAnswer(call->Optional.of(f.jdbc.queryForObject(
                "select status,email_verified from tbl_user where id=? for update",(rs,index)->User.builder().id(f.user)
                        .status(com.berkayb.soundconnect.modules.user.enums.UserStatus.valueOf(rs.getString(1)))
                        .emailVerified(rs.getBoolean(2)).roles(new HashSet<>()).permissions(new HashSet<>()).build(),f.user)));
        when(applications.saveAndFlush(any())).thenAnswer(call->{VenueApplication value=call.getArgument(0);
            f.jdbc.update("update tbl_venue_applications set status=?,decision_date=? where id=?",value.getStatus().name(),value.getDecisionDate(),value.getId());return value;});
        if(failAfterNotification) when(mapper.toResponseDto(any())).thenThrow(new IllegalStateException("after notification persistence"));
        var service=new com.berkayb.soundconnect.modules.application.venueapplication.service.VenueApplicationServiceImpl(
                applications,mock(com.berkayb.soundconnect.modules.user.support.UserEntityFinder.class),
                mock(com.berkayb.soundconnect.modules.location.support.LocationEntityFinder.class),mapper,
                mock(com.berkayb.soundconnect.modules.role.repository.RoleRepository.class),users,
                mock(com.berkayb.soundconnect.modules.venue.repository.VenueRepository.class),
                mock(com.berkayb.soundconnect.modules.profile.VenueProfile.service.VenueProfileService.class),
                mock(com.berkayb.soundconnect.modules.application.venueapplication.service.VenueApplicationAdminMailService.class),
                mock(com.berkayb.soundconnect.modules.profile.shared.type.PersonalProfileTypePolicy.class),producer,
                mock(jakarta.persistence.EntityManager.class));
        var proxy=new ProxyFactory(service);proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(f.transactionManager,new AnnotationTransactionAttributeSource()));
        return (com.berkayb.soundconnect.modules.application.venueapplication.service.VenueApplicationServiceImpl)proxy.getProxy();
    }
    @Test void actualDecisionServiceRollsBackAllThreeWritesOnLaterFailureAndRejectsUnverifiedBeforeMutation() {
        f.jdbc.update("update tbl_user set email_verified=false where id=?",f.user);
        assertThatThrownBy(()->domain(false).rejectApplication(app,UUID.randomUUID(),"private"))
                .isInstanceOfSatisfying(com.berkayb.soundconnect.shared.exception.SoundConnectException.class,
                    error->assertThat(error.getErrorType()).isEqualTo(com.berkayb.soundconnect.shared.exception.ErrorType.VENUE_APPLICANT_EMAIL_VERIFICATION_REQUIRED));
        for(String table:List.of("tbl_notification_receipt","tbl_notification","tbl_push_delivery")) assertThat(count(table)).isZero();
        f.jdbc.update("update tbl_user set email_verified=true where id=?",f.user);
        assertThatThrownBy(()->domain(true).rejectApplication(app,UUID.randomUUID(),"private")).isInstanceOf(IllegalStateException.class);
        assertThat(f.jdbc.queryForObject("select status from tbl_venue_applications",String.class)).isEqualTo("PENDING");
        for(String table:List.of("tbl_notification_receipt","tbl_notification","tbl_push_delivery")) assertThat(count(table)).isZero();
        domain(false).rejectApplication(app,UUID.randomUUID(),"private");assertThat(count("tbl_push_delivery")).isEqualTo(1);
    }
    @Test void twoConcurrentActualAdminDecisionsSerializeAndProduceOnlyOneResult() throws Exception {
        var service=domain(false);var gate=new CountDownLatch(1);
        java.util.concurrent.Callable<Boolean> call=()->{gate.await();try{service.rejectApplication(app,UUID.randomUUID(),"private");return true;}
            catch(com.berkayb.soundconnect.shared.exception.SoundConnectException error){assertThat(error.getErrorType()).isEqualTo(com.berkayb.soundconnect.shared.exception.ErrorType.INVALID_APPLICATION_STATUS);return false;}};
        try(var pool=Executors.newFixedThreadPool(2)){var a=pool.submit(call);var b=pool.submit(call);gate.countDown();
            assertThat(List.of(a.get(5,TimeUnit.SECONDS),b.get(5,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);}
        for(String table:List.of("tbl_notification_receipt","tbl_notification","tbl_push_delivery")) assertThat(count(table)).isEqualTo(1);
    }

    @Test void sourceLockContentionUsesRetryInsteadOfDeadlockOrStaleDelivery() throws Exception {
        publish(false);var claim=f.store.claimNext().orElseThrow();
        var held=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var executor=Executors.newSingleThreadExecutor()) {
            var writer=executor.submit(()->f.tx.executeWithoutResult(s->{f.jdbc.queryForObject("select id from tbl_venue_applications where id=? for update",UUID.class,app);held.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){throw new RuntimeException(e);}}));
            assertThat(held.await(3,TimeUnit.SECONDS)).isTrue();
            try { assertThatThrownBy(()->f.store.prepare(claim)).isInstanceOf(org.springframework.dao.DataAccessException.class);f.store.transientFailure(claim); }
            finally {release.countDown();}writer.get(5,TimeUnit.SECONDS);
        }
        f.jdbc.update("update tbl_push_delivery set next_attempt_at=?", java.sql.Timestamp.from(f.clock.instant()));
        assertThat(f.store.prepare(f.store.claimNext().orElseThrow())).isPresent();
    }
}

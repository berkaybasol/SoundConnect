package com.berkayb.soundconnect.modules.notification.service;

import com.berkayb.soundconnect.modules.notification.dto.response.NotificationResponseDto;
import com.berkayb.soundconnect.modules.notification.entity.Notification;
import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.modules.notification.helper.NotificationBadgeCacheHelper;
import com.berkayb.soundconnect.modules.notification.listener.NotificationEventListener;
import com.berkayb.soundconnect.modules.notification.mapper.NotificationMapper;
import com.berkayb.soundconnect.modules.notification.repository.NotificationRepository;
import com.berkayb.soundconnect.modules.notification.support.BandNotificationIdentity;
import com.berkayb.soundconnect.modules.notification.websocket.NotificationWebSocketService;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.dto.request.BandCreateRequestDto;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity.*;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.enums.*;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.mapper.BandMapper;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.service.BandServiceImpl;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.support.BandEntityFinder;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.entity.MusicianProfile;
import com.berkayb.soundconnect.modules.profile.shared.identity.GhostListenerIdentityBatchResolver;
import com.berkayb.soundconnect.modules.role.entity.Role;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.modules.user.support.*;
import com.berkayb.soundconnect.shared.config.JpaAuditingConfig;
import com.berkayb.soundconnect.shared.messaging.events.notification.NotificationInboundEvent;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.mapstruct.factory.Mappers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import javax.sql.DataSource;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real band mutations, PG transactions, scalar identity locks and delayed commit delivery. */
@DataJpaTest(properties={"spring.config.location=classpath:/application-test.yml","spring.config.import=",
        "spring.jpa.hibernate.ddl-auto=create-drop","spring.jpa.show-sql=false",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect"})
@ActiveProfiles("test") @Testcontainers @AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes=BandNotificationIdentityPostgresTest.Config.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional(propagation=Propagation.NOT_SUPPORTED)
class BandNotificationIdentityPostgresTest {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:16.4-alpine")
            .withDatabaseName("band_identity_regression").withUsername("band_identity").withPassword("disposable")
            .withLabel("soundconnect.task","band-notification-identity-20260929").withReuse(false);
    @Autowired DataSource source; @Autowired EntityManager em; @Autowired PlatformTransactionManager manager;
    @Autowired BandServiceImpl bands; @Autowired NotificationService reads; @Autowired NotificationRepository inbox;
    @Autowired TransactionalNotificationService writer; @Autowired NotificationEventListener consumer;
    @Autowired NotificationDeliveryPolicy policy;
    @MockitoBean com.berkayb.soundconnect.modules.media.service.MediaAssetService media;
    @MockitoBean com.berkayb.soundconnect.modules.event.performer.service.EventPerformerRequestService performers;
    @MockitoBean NotificationBadgeCacheHelper badges; @MockitoBean NotificationWebSocketService sockets;
    @MockitoBean AfterCommitDeliveryExecutor executor;
    @MockitoBean com.berkayb.soundconnect.shared.mail.producer.MailProducer mail;
    JdbcTemplate jdbc; UUID founder,member,band;
    List<Runnable> queued=new CopyOnWriteArrayList<>();
    @BeforeEach void setup() throws Exception {
        jdbc=new JdbcTemplate(source);
        try(var c=source.getConnection();var s=c.createStatement()) {
            assertThat(c.getMetaData().getURL()).contains("band_identity_regression");
            for(String file:List.of("2026-09-10-listener-account-erasure.sql","2026-09-29-band-notification-identity.sql"))
                s.execute(Files.readString(Path.of("scripts/db",file)));
        }
        doAnswer(call -> {queued.add(call.getArgument(0));return null;}).when(executor).submit(any());
        tx(() -> {founder=user();member=user();return null;});
        band=bands.createBand(founder,profile("OldBand"+UUID.randomUUID().toString().substring(0,8))).id();
    }
    @Test void realDomainFiveTypesRenameAndDelayedDeliveryUseCurrentIdentity() {
        produceFive(); var rows=rows();assertThat(rows.stream().map(Notification::getType)).containsAll(BandNotificationIdentity.TYPES);
        bands.updateBand(band,founder,profile("CurrentBand"));
        jdbc.update("update tbl_user set user_name='fresh_'||left(replace(id::text,'-',''),20) where id in (?,?)",founder,member);
        for(var n:rows) {
            assertThat(n.getTitle()+n.getMessage()+n.getPayload()).doesNotContain("OldBand","fixture_");
            assertThat(n.getPayload()).containsEntry("bandIdentityVersion",1).doesNotContainKey("bandName");
            var fresh=reads.getUserNotification(n.getRecipientId(),n.getId());
            assertThat(fresh.title()+fresh.message()).contains("CurrentBand");
            assertThat(fresh.title()+fresh.message()).doesNotContain("fixture_");
        }
        queued.forEach(Runnable::run);
        var delivered=org.mockito.ArgumentCaptor.forClass(NotificationResponseDto.class);
        verify(sockets,atLeast(5)).sendNotificationToUser(any(),delivered.capture());
        assertThat(delivered.getAllValues()).allSatisfy(n -> assertThat(n.title()+n.message()).contains("CurrentBand").doesNotContain("OldBand","fixture_"));
    }
    @Test void transactionFailureRollsBackDomainAndReceiptsAndNeverQueues() {
        assertThatThrownBy(() -> tx(() -> {bands.inviteMember(band,founder,member,null);throw new IllegalStateException("rollback");})).hasMessage("rollback");
        assertThat(rows()).isEmpty();assertThat(receiptCount()).isZero();assertThat(queued).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from tbl_band_member where band_id=? and user_id=?",Long.class,band,member)).isZero();
    }
    @Test void secondFounderInsertFailureRollsBackAcceptanceAndBothRecipients() {
        UUID other=tx(this::user);
        tx(() -> {em.persist(BandMember.builder().band(em.getReference(Band.class,band)).user(em.getReference(User.class,other))
                .bandRole(BandRole.FOUNDER).status(BandMemberShipStatus.ACTIVE).build());return null;});
        bands.inviteMember(band,founder,member,null);queued.clear();long receipts=receiptCount();
        // Whichever founder is processed second fails after an actual first inbox/receipt insert.
        jdbc.execute("""
                create or replace function band_fixture_second_failure() returns trigger language plpgsql as $$
                begin if new.type='BAND_INVITE_ACCEPTED' and exists(select 1 from tbl_notification
                    where type='BAND_INVITE_ACCEPTED' and payload->>'bandId'=new.payload->>'bandId') then
                    raise exception 'second founder fixture failure'; end if; return new; end $$;
                create trigger zz_band_fixture_failure before insert on tbl_notification for each row execute function band_fixture_second_failure();
                """);
        try { assertThatThrownBy(() -> bands.acceptInvite(band,member,invitation())).isInstanceOf(RuntimeException.class); }
        finally {jdbc.execute("drop trigger zz_band_fixture_failure on tbl_notification");}
        assertThat(status()).isEqualTo("PENDING");assertThat(receiptCount()).isEqualTo(receipts);
        assertThat(rows()).hasSize(1);assertThat(queued).isEmpty();
    }
    @Test void deleteReplayRetainsReceiptAndCannotResurrectInbox() {
        bands.inviteMember(band,founder,member,null);var n=rows().getFirst();var e=event(n);
        reads.deleteById(member,n.getId());consumer.handle(e);tx(() -> {writer.persistInCurrentTransaction(e);return null;});
        assertThat(rows()).isEmpty();assertThat(receiptCount()).isEqualTo(1);
        queued.forEach(Runnable::run);verify(sockets,never()).sendNotificationToUser(any(),any());
    }
    @Test void missingInactiveOrWrongRoleActorCannotFallBackToSnapshot() {
        bands.inviteMember(band,founder,member,null);var n=rows().getFirst();
        jdbc.update("update tbl_user set status='INACTIVE' where id=?",founder);
        assertThat(reads.getUserNotification(member,n.getId()).title()).isEqualTo("Yeni grup daveti");
        jdbc.update("update tbl_user set status='ACTIVE' where id=?",founder);
        assertThat(reads.getUserNotification(member,n.getId()).title()).contains("OldBand");
        jdbc.update("delete from user_roles where user_id=?",founder);
        assertThat(reads.getUserNotification(member,n.getId()).title()).isEqualTo("Yeni grup daveti");
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"band-rename","band-delete","actor-rename"})
    void identityMutationWaitsForDeliveryTransactionFence(String mutation) throws Exception {
        bands.inviteMember(band,founder,member,null);var n=rows().getFirst();
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var delivery=pool.submit(() -> tx(() -> {
                reads.refreshActorIdentityForDelivery(new NotificationResponseDto(n.getId(),n.getRecipientId(),n.getType(),"stale","stale",false,n.getOccurredAt(),n.getPayload()));
                entered.countDown();try {assertThat(release.await(15,TimeUnit.SECONDS)).isTrue();} catch(InterruptedException e){throw new RuntimeException(e);}return null;
            }));
            assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
            var rename=pool.submit(() -> {
                switch(mutation) {
                    case "band-delete" -> bands.deleteBand(band,founder);
                    case "actor-rename" -> jdbc.update("update tbl_user set user_name=? where id=?","AfterFence"+founder.toString().substring(0,8),founder);
                    default -> bands.updateBand(band,founder,profile("AfterFence"));
                }
            });
            org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(8)).until(() -> jdbc.queryForObject(
                    "select count(*) from pg_stat_activity where datname=current_database() and wait_event_type='Lock' and query like ?",Long.class,
                    mutation.equals("actor-rename") ? "%tbl_user%" : "%tbl_band%")>0);
            assertThat(rename.isDone()).isFalse();release.countDown();delivery.get(10,TimeUnit.SECONDS);rename.get(10,TimeUnit.SECONDS);
        } finally {release.countDown();}
        var current=reads.getUserNotification(member,n.getId());
        if(mutation.equals("band-delete")) assertThat(current.title()).isEqualTo("Yeni grup daveti");
        else assertThat(current.title()+current.message()).contains("AfterFence");
    }
    @Test void unavailableRealBandQueryNeverReturnsStaleIdentitySuccessfully() {
        bands.inviteMember(band,founder,member,null);var n=rows().getFirst();
        jdbc.execute("alter table tbl_band rename column name to fixture_missing_name");
        try {
            var response=new java.util.concurrent.atomic.AtomicReference<NotificationResponseDto>();
            Throwable failure=catchThrowable(() -> response.set(reads.getUserNotification(member,n.getId())));
            if(failure==null) assertThat(response.get().title()).isEqualTo("Yeni grup daveti");
            else {assertThat(response.get()).isNull();assertThat(failure).isInstanceOf(org.springframework.transaction.UnexpectedRollbackException.class);}
        } finally {jdbc.execute("alter table tbl_band rename column fixture_missing_name to name");}
        assertThat(reads.getUserNotification(member,n.getId()).title()).contains("OldBand");
    }
    @Test void oldQueuedWriterAndMalformedContractsStayAnonymousAndReplayDoesNotRewriteRead() {
        bands.inviteMember(band,founder,member,null);var real=rows().getFirst();
        reads.markAsRead(member,real.getId());consumer.handle(event(real));
        assertThat(reads.getUserNotification(member,real.getId()).read()).isTrue();
        assertThat(rows()).hasSize(1);assertThat(receiptCount()).isEqualTo(1);
        for(var type:BandNotificationIdentity.TYPES) {
            var payload=new HashMap<String,Object>();payload.put("bandId",band.toString());payload.put("action",BandNotificationIdentity.action(type));
            payload.put("invitationId",UUID.randomUUID().toString());payload.put("bandName","Leaked legacy name");
            payload.put(BandNotificationIdentity.actorKey(type),founder.toString());
            var event=new NotificationInboundEvent(UUID.randomUUID(),member,type,"Leaked legacy title","Leaked legacy body",payload,false,java.time.Instant.now());
            consumer.handle(event);
            var n=rows().stream().filter(x -> x.getSourceEventId().equals(event.eventId())).findFirst().orElseThrow();
            assertThat(n.getPayload()).containsEntry("bandIdentityVersion",0).doesNotContainKey(BandNotificationIdentity.actorKey(type));
            assertThat(reads.getUserNotification(member,n.getId()).title()).isEqualTo(BandNotificationIdentity.title(type));
            payload.put("bandIdentityVersion",1);payload.put("module","BAND");payload.put(BandNotificationIdentity.actorKey(type),"1-1-1-1-1");
            assertThat(BandNotificationIdentity.actorId(type,payload)).isEmpty();
        }
    }
    @Test void sqlSanitizerHandlesNullArrayAndTypeChangesAndReplayPreservesRows() throws Exception {
        bands.inviteMember(band,founder,member,null);var n=rows().getFirst();
        for(String payload:List.of("null","[]","42","{\"bandIdentityVersion\":\"1\",\"actorId\":\"fake\"}")) {
            jdbc.update("update tbl_notification set title='Legacy private',message='Legacy band',payload=?::jsonb where id=?",payload,n.getId());
            assertThat(jdbc.queryForObject("select payload->>'bandIdentityVersion' from tbl_notification where id=?",String.class,n.getId())).isEqualTo("0");
            assertThat(reads.getUserNotification(member,n.getId()).title()).isEqualTo("Yeni grup daveti");
        }
        jdbc.update("update tbl_notification set type='BAND_MEMBER_LEFT' where id=?",n.getId());
        var before=jdbc.queryForObject("select to_jsonb(n)::text from tbl_notification n where id=?",String.class,n.getId());
        try(var c=source.getConnection();var s=c.createStatement()) {s.execute(Files.readString(Path.of("scripts/db/2026-09-29-band-notification-identity.sql")));}
        assertThat(jdbc.queryForObject("select to_jsonb(n)::text from tbl_notification n where id=?",String.class,n.getId())).isEqualTo(before);
        assertThat(reads.getUserNotification(member,n.getId()).title()).isEqualTo("Bir üye gruptan ayrıldı");
    }
    void produceFive() {
        bands.inviteMember(band,founder,member,null);bands.rejectInvite(band,member,invitation());
        bands.inviteMember(band,founder,member,null);bands.acceptInvite(band,member,invitation());
        bands.leaveBand(band,member,version());bands.inviteMember(band,founder,member,null);
        bands.acceptInvite(band,member,invitation());bands.removeMember(band,founder,member,version());
    }
    UUID invitation(){return jdbc.queryForObject("select invitation_id from tbl_band_member where band_id=? and user_id=?",UUID.class,band,member);}
    Long version(){return jdbc.queryForObject("select title_version from tbl_band_member where band_id=? and user_id=?",Long.class,band,member);}
    String status(){return jdbc.queryForObject("select status from tbl_band_member where band_id=? and user_id=?",String.class,band,member);}
    long receiptCount(){return jdbc.queryForObject("select count(*) from tbl_notification_receipt where recipient_id in (?,?)",Long.class,founder,member);}
    List<Notification> rows(){return inbox.findAll().stream().filter(n -> band.toString().equals(n.getPayload().get("bandId"))).toList();}
    NotificationInboundEvent event(Notification n){return new NotificationInboundEvent(n.getSourceEventId(),n.getRecipientId(),n.getType(),n.getTitle(),n.getMessage(),n.getPayload(),false,n.getOccurredAt());}
    BandCreateRequestDto profile(String name){return new BandCreateRequestDto(name,null,null,null,null,null,null,null,null);}
    UUID user(){
        var u=User.builder().username("fixture_"+UUID.randomUUID().toString().substring(0,12)).email(UUID.randomUUID()+"@example.invalid")
                .password("unused").status(UserStatus.ACTIVE).emailVerified(true).build();em.persist(u);
        var role=em.createQuery("select r from Role r where r.name='ROLE_MUSICIAN'",Role.class).getResultStream().findFirst().orElseGet(() -> {var r=Role.builder().name("ROLE_MUSICIAN").build();em.persist(r);return r;});
        u.getRoles().add(role);em.persist(MusicianProfile.builder().user(u).stageName("Synthetic").build());return u.getId();
    }
    <T>T tx(Supplier<T> action){return new TransactionTemplate(manager).execute(s -> action.get());}
    @Configuration(proxyBeanMethods=false) @EnableJpaRepositories(basePackages="com.berkayb.soundconnect")
    @EntityScan(basePackages="com.berkayb.soundconnect")
    @Import({JpaAuditingConfig.class,BandServiceImpl.class,BandEntityFinder.class,UserEntityFinder.class,
            TransactionalNotificationService.class,NotificationServiceImpl.class,GhostListenerIdentityBatchResolver.class,
            NotificationDeliveryPolicy.class,AccountDeliveryFence.class,NotificationEventListener.class})
    static class Config {
        @Bean DataSource dataSource(){if(!PG.isRunning())throw new IllegalStateException("Disposable PG required");return new DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword());}
        @Bean NamedParameterJdbcTemplate named(DataSource d){return new NamedParameterJdbcTemplate(d);}
        @Bean BandMapper bandMapper(){return Mappers.getMapper(BandMapper.class);}
        @Bean NotificationMapper notificationMapper(){return Mappers.getMapper(NotificationMapper.class);}
    }
}

package com.berkayb.soundconnect.modules.notification.service;

import com.berkayb.soundconnect.modules.comment.abuse.CommentBurstGuard;
import com.berkayb.soundconnect.modules.comment.dto.request.CommentCreateRequestDto;
import com.berkayb.soundconnect.modules.comment.mapper.CommentMapper;
import com.berkayb.soundconnect.modules.comment.service.CommentServiceImpl;
import com.berkayb.soundconnect.modules.comment.support.*;
import com.berkayb.soundconnect.modules.engagement.enums.EngagementTargetType;
import com.berkayb.soundconnect.modules.engagement.service.*;
import com.berkayb.soundconnect.modules.like.service.*;
import com.berkayb.soundconnect.modules.media.entity.MediaAsset;
import com.berkayb.soundconnect.modules.media.enums.*;
import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.notification.dto.response.NotificationResponseDto;
import com.berkayb.soundconnect.modules.notification.entity.Notification;
import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.modules.notification.helper.NotificationBadgeCacheHelper;
import com.berkayb.soundconnect.modules.notification.listener.NotificationEventListener;
import com.berkayb.soundconnect.modules.notification.mapper.NotificationMapper;
import com.berkayb.soundconnect.modules.notification.repository.NotificationRepository;
import com.berkayb.soundconnect.modules.notification.websocket.NotificationWebSocketService;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.entity.ListenerProfile;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.entity.MusicianProfile;
import com.berkayb.soundconnect.modules.profile.shared.identity.GhostListenerIdentityBatchResolver;
import com.berkayb.soundconnect.modules.role.entity.Role;
import com.berkayb.soundconnect.modules.tablegroup.game.service.TableGroupGameLifecycleService;
import com.berkayb.soundconnect.modules.user.deletion.*;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.modules.user.support.*;
import com.berkayb.soundconnect.shared.config.JpaAuditingConfig;
import com.berkayb.soundconnect.shared.mail.adapter.MailSenderClient;
import com.berkayb.soundconnect.shared.mail.producer.MailProducer;
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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import javax.sql.DataSource;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real mutation, repositories, identity/erasure and delivery transactions; no application datasource. */
@DataJpaTest(properties={"spring.config.location=classpath:/application-test.yml","spring.config.import=",
        "spring.jpa.hibernate.ddl-auto=create-drop","spring.jpa.show-sql=false",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect"})
@ActiveProfiles("test") @Testcontainers @AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes=MediaNotificationIdentityPostgresTest.Config.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional(propagation=Propagation.NOT_SUPPORTED)
class MediaNotificationIdentityPostgresTest {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:16.4-alpine")
            .withDatabaseName("media_identity_test").withUsername("media_identity").withPassword("disposable")
            .withLabel("soundconnect.task","media-notification-identity").withReuse(false);
    @Autowired DataSource dataSource; @Autowired EntityManager em; @Autowired PlatformTransactionManager manager;
    @Autowired LikeServiceImpl likes; @Autowired CommentServiceImpl comments;
    @Autowired NotificationService reads; @Autowired NotificationRepository inbox;
    @Autowired ListenerAccountDeletionService deletion; @Autowired PasswordEncoder passwords;
    @Autowired TransactionalNotificationService transactional; @Autowired NotificationDeliveryPolicy policy;
    @Autowired NotificationEventListener consumer; @Autowired NotificationMailDelivery mailDelivery;
    @MockitoBean MediaAssetService media; @MockitoBean UserEntityFinder users; @MockitoBean CommentBurstGuard burst;
    @MockitoBean TableGroupGameLifecycleService games; @MockitoBean NotificationBadgeCacheHelper badges;
    @MockitoBean NotificationWebSocketService sockets; @MockitoBean AfterCommitDeliveryExecutor executor;
    @MockitoBean MailSenderClient mailSender; @MockitoBean MailProducer mailProducer;
    @MockitoBean com.berkayb.soundconnect.modules.promotion.announcement.AnnouncementAccess announcements;
    @MockitoBean com.berkayb.soundconnect.modules.analytics.AnnouncementAnalyticsStore analytics;
    @Autowired com.berkayb.soundconnect.modules.notification.push.PushProperties nativeProperties;
    JdbcTemplate jdbc; UUID actor,recipient,asset; String oldName;
    List<Runnable> deliveries=new CopyOnWriteArrayList<>();

    @BeforeEach void setup() throws Exception {
        nativeProperties.setEnabled(false);
        jdbc=new JdbcTemplate(dataSource);
        try(var connection=dataSource.getConnection();var statement=connection.createStatement()) {
            assertThat(connection.getMetaData().getURL()).isEqualTo(POSTGRES.getJdbcUrl());
            assertThat(connection.getCatalog()).isEqualTo("media_identity_test");
            for(String migration:List.of("2026-09-09-overthinking-lifecycle.sql","2026-09-10-overthinking-inbox-seen.sql",
                    "2026-09-10-overthinking-profile-shares.sql","2026-09-10-overthinking-production-safety.sql",
                    "2026-09-10-listener-account-erasure.sql","2026-09-10-tablegroup-profile-shares.sql",
                    "2026-09-10-tablegroup-profile-share-history.sql","2026-09-24-studio-reservation-notification-outbox.sql",
                    "2026-09-27-follow-notification-outbox.sql"))
                statement.execute(Files.readString(Path.of("scripts/db",migration)));
            for(String migration:List.of("2026-09-22-push-delivery-foundation","2026-09-23-push-device-registration-revision",
                    "2026-09-24-push-native-venue-capability","2026-09-24-venue-application-notifications",
                    "2026-09-24-push-native-studio-capability","2026-09-28-push-native-follow-capability","2026-09-29-push-native-media-capability")) {
                if(!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from soundconnect_schema_migrations where migration_id=?)",Boolean.class,migration)))
                    statement.execute(Files.readString(Path.of("scripts/db",migration+".sql")));
            }
        }
        when(users.getUser(any())).thenAnswer(call -> em.getReference(User.class,call.getArgument(0)));
        doAnswer(call -> { deliveries.add(call.getArgument(0)); return null; }).when(executor).submit(any());
        tx(() -> {
            User a=listener(); actor=a.getId(); oldName=a.getUsername();
            User owner=user(); recipient=owner.getId();
            var profile=persist(MusicianProfile.builder().user(owner).stageName("Media owner").build());
            asset=persist(MediaAsset.builder().ownerType(MediaOwnerType.MUSICIAN_PROFILE).ownerId(profile.getId())
                    .kind(MediaKind.AUDIO).status(MediaStatus.READY).visibility(MediaVisibility.PUBLIC)
                    .size(100L).mimeType("audio/mpeg").sourceUrl("https://fixture.invalid/private-source.mp3").build()).getId();
            return null;
        });
    }

    @Test void mediaNativeRealDomainRollbackReceiptInboxPlannerAndDedupAreAtomic() {
        var named=new NamedParameterJdbcTemplate(jdbc);
        nativeProperties.setEnabled(true);
        nativeProperties.setAllowedTypes(com.berkayb.soundconnect.modules.notification.push.MediaPushPresentation.TYPES);
        nativeProperties.setTokenEncryptionKey(Base64.getEncoder().encodeToString(new byte[32]));
        var devices=new com.berkayb.soundconnect.modules.notification.push.PushDeviceService(named,new AccountDeliveryFence(named),
                new com.berkayb.soundconnect.modules.notification.push.PushTokenCipher(nativeProperties),nativeProperties,java.time.Clock.systemUTC());
        var current=UUID.randomUUID();var old=UUID.randomUUID();
        tx(() -> {
            for(var entry:Map.of(current,"ANDROID_NATIVE_V6",old,"ANDROID_NATIVE_V5").entrySet())
                devices.register(recipient,entry.getKey(),new com.berkayb.soundconnect.modules.notification.push.PushDeviceService.Registration(
                    "fixture-"+entry.getKey(),com.berkayb.soundconnect.modules.notification.push.PushDeviceService.Platform.ANDROID,
                    com.berkayb.soundconnect.modules.notification.push.PushDeviceService.Permission.AUTHORIZED,"fixture",1L,entry.getValue()));
            return null;
        });
        assertThatThrownBy(() -> tx(() -> {produceBoth();throw new IllegalStateException("native rollback");})).hasMessage("native rollback");
        assertThat(rows()).isEmpty();assertThat(receipts()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from tbl_push_delivery where recipient_id=?",Long.class,recipient)).isZero();
        produceBoth();likes.like(actor,EngagementTargetType.MEDIA,asset);
        assertThat(rows()).hasSize(2);assertThat(receipts()).isEqualTo(2);
        assertThat(jdbc.queryForList("select installation_id from tbl_push_delivery where recipient_id=?",UUID.class,recipient)).containsExactly(current,current);
        var before=jdbc.queryForList("select * from tbl_push_delivery where recipient_id=? order by id",recipient);
        rows().forEach(n->consumer.handle(event(n)));
        assertThat(jdbc.queryForList("select * from tbl_push_delivery where recipient_id=? order by id",recipient)).isEqualTo(before);
        assertThat(rows()).hasSize(2);assertThat(receipts()).isEqualTo(2);
        nativeProperties.setEnabled(false);
    }

    @Test void realMutationThenGhostAliasMustRefreshReadUnreadExactListAndDelivery() {
        produceBoth();
        var rows=rows(); assertThat(rows).hasSize(2);
        reads.markAsRead(recipient,rows.getFirst().getId());
        jdbc.update("update tbl_user set user_name='current_alias' where id=?",actor);
        jdbc.update("update \"tbl_listener-profile\" set visibility_mode='GHOST' where user_id=?",actor);
        for(var row:rows) {
            assertThat(reads.getUserNotification(recipient,row.getId()).title()).contains("current_alias").doesNotContain(oldName);
            assertThat(reads.refreshActorIdentityForDelivery(dto(row)).title()).contains("current_alias").doesNotContain(oldName);
        }
        assertThat(reads.getUserNotifications(recipient,0,20).getContent()).allSatisfy(n -> assertThat(n.title()).contains("current_alias"));
        assertThat(reads.getUserNotificationsByTypes(recipient,List.of(NotificationType.SOCIAL_LIKE,NotificationType.SOCIAL_COMMENT),0,20).getContent())
                .hasSize(2).allSatisfy(n -> assertThat(n.title()).contains("current_alias").doesNotContain(oldName));
        assertThat(reads.getRecentNotifications(recipient)).allSatisfy(n -> assertThat(n.title()).contains("current_alias"));
        assertThat(inbox.countByRecipientIdAndReadIsFalse(recipient)).isEqualTo(1);
    }

    @Test void realActorErasureMustRemoveOtherRecipientsMediaRowsAndRetainReceipts() {
        produceBoth(); var rows=rows();
        assertThat(rows).hasSize(2); assertThat(receipts()).isEqualTo(2);
        deletion.deleteSelf(actor,"fixture-password",null);
        assertThat(jdbc.queryForObject("select erased_at is not null from tbl_user where id=?",Boolean.class,actor)).isTrue();
        assertThat(rows()).as("actor erasure removes the two other-recipient MEDIA notifications").isEmpty();
        assertThat(receipts()).isEqualTo(2);
        deliveries.forEach(Runnable::run);
        verifyNoInteractions(sockets);
    }

    @Test void mutationStoresOnlyStableActorAndDedupSurvivesReadDeleteAndRedelivery() {
        produceBoth(); var originals=rows();
        assertThat(originals).allSatisfy(n -> {
            assertThat(n.getTitle()).startsWith("Bir kullanıcı").doesNotContain(oldName);
            assertThat(n.getPayload()).containsEntry("actorId",actor.toString()).containsEntry("mediaIdentityVersion",1)
                    .containsEntry("targetId",asset.toString()).doesNotContainKeys("username","actorAvatarUrl","sourceUrl","commentText");
            assertThat(reads.getUserNotification(recipient,n.getId()).title()).contains(oldName);
        });
        var read=originals.getFirst(); var deleted=originals.getLast();
        reads.markAsRead(recipient,read.getId()); reads.deleteById(recipient,deleted.getId());
        for(var n:originals) { consumer.handle(event(n)); tx(() -> { transactional.persistInCurrentTransaction(event(n)); return null; }); }
        likes.unlike(actor,EngagementTargetType.MEDIA,asset); likes.like(actor,EngagementTargetType.MEDIA,asset);
        assertThat(rows()).singleElement().satisfies(n -> {assertThat(n.getId()).isEqualTo(read.getId());assertThat(n.isRead()).isTrue();});
        assertThat(receipts()).isEqualTo(2);
        deliveries.forEach(Runnable::run); verify(sockets,never()).sendNotificationToUser(any(),any());
        verifyNoInteractions(mailProducer);
    }

    @Test void pendingMissingErasedAndMalformedActorsNeverUseOldSnapshotOrAlternateIdentity() {
        produceBoth(); var original=rows().getFirst();
        jdbc.update("update \"tbl_listener-profile\" set visibility_choice_completed=false where user_id=?",actor);
        assertThat(reads.getUserNotification(recipient,original.getId()).title()).startsWith("Kullanici ");
        for(Object id:List.of(UUID.randomUUID().toString(),"1-1-1-1-1","bad-uuid",List.of(actor.toString()),17)) {
            var p=new LinkedHashMap<String,Object>(original.getPayload()); p.put("actorId",id);
            p.put("actorUsername",oldName);p.put("actorAvatarUrl","https://private.invalid/avatar");
            var n=new NotificationResponseDto(original.getId(),recipient,original.getType(),oldName,"private old message",false,Instant.now(),p);
            var projected=reads.refreshActorIdentityForDelivery(n);
            assertThat(projected.title()).startsWith("Bir kullanıcı").doesNotContain(oldName);
            assertThat(projected.message()).doesNotContain("private");
            assertThat(projected.payload()).doesNotContainKeys("actorUsername","actorAvatarUrl");
        }
        var stale=dto(original); deletion.deleteSelf(actor,"fixture-password",null);
        assertThat(reads.refreshActorIdentityForDelivery(stale).title()).startsWith("Bir kullanıcı");
    }

    @Test void erasureAndLateConsumerDoNotTouchUnrelatedUuidTextOrReceiptHistory() {
        produceBoth(); var originals=rows();
        UUID other=tx(() -> listener().getId());
        var siblingPayload=Map.<String,Object>of("targetType","MEDIA","targetId",asset.toString(),"mediaIdentityVersion",1,
                "actorId",other.toString(),"unrelatedNote",actor.toString());
        UUID sibling=store(NotificationType.SOCIAL_LIKE,siblingPayload,true);
        String siblingBefore=notificationRow(sibling);
        deletion.deleteSelf(actor,"fixture-password",null);
        for(var n:originals) { consumer.handle(event(n)); tx(() -> { transactional.persistInCurrentTransaction(event(n));return null; }); }
        var delayed=new NotificationInboundEvent(UUID.randomUUID(),recipient,NotificationType.SOCIAL_COMMENT,oldName,"old",
                originals.getFirst().getPayload(),false,Instant.now());
        consumer.handle(delayed);
        assertThat(rows()).singleElement().satisfies(n -> assertThat(n.getId()).isEqualTo(sibling));
        assertThat(notificationRow(sibling)).isEqualTo(siblingBefore);
        assertThat(receipts()).isEqualTo(4); // two originals + sibling + suppressed late event
        assertThatThrownBy(() -> likes.like(actor,EngagementTargetType.MEDIA,asset)).isInstanceOf(RuntimeException.class);
        deliveries.forEach(Runnable::run); verify(sockets,never()).sendNotificationToUser(any(),any());
    }

    @Test void recipientErasureSuppressesPendingAndRedeliveredMediaWithoutErasingActor() {
        recipient=tx(() -> listener().getId());
        UUID profile=jdbc.queryForObject("select id from \"tbl_listener-profile\" where user_id=?",UUID.class,recipient);
        asset=tx(() -> persist(MediaAsset.builder().ownerType(MediaOwnerType.LISTENER_PROFILE).ownerId(profile)
                .kind(MediaKind.AUDIO).status(MediaStatus.READY).visibility(MediaVisibility.PUBLIC)
                .size(1L).mimeType("audio/mpeg").sourceUrl("https://fixture.invalid/media").build()).getId());
        produceBoth(); var originals=rows(); assertThat(originals).hasSize(2);
        deletion.deleteSelf(recipient,"fixture-password",null);
        originals.forEach(n -> consumer.handle(event(n)));
        assertThat(rows()).isEmpty();assertThat(receipts()).isEqualTo(2);
        assertThat(jdbc.queryForObject("select user_name from tbl_user where id=?",String.class,actor)).isEqualTo(oldName);
        deliveries.forEach(Runnable::run); verifyNoInteractions(sockets);
    }

    @Test void realErasureRollbackRestoresInboxReadReceiptsAndActorThenCommittedRetryRemovesOnlyLinkedRows() {
        produceBoth();var original=rows();reads.markAsRead(recipient,original.getFirst().getId());
        var before=original.stream().map(n -> notificationRow(n.getId())).toList();
        jdbc.execute("create or replace function test_media_erase_fail() returns trigger language plpgsql as $$ begin if NEW.erased_at is not null then raise exception 'fixture rollback'; end if; return NEW; end $$");
        jdbc.execute("create trigger test_media_erase_fail before update of erased_at on tbl_user for each row execute function test_media_erase_fail()");
        try { assertThatThrownBy(() -> deletion.deleteSelf(actor,"fixture-password",null)).hasStackTraceContaining("fixture rollback"); }
        finally { jdbc.execute("drop trigger test_media_erase_fail on tbl_user");jdbc.execute("drop function test_media_erase_fail()"); }
        assertThat(original.stream().map(n -> notificationRow(n.getId())).toList()).isEqualTo(before);
        assertThat(receipts()).isEqualTo(2);
        assertThat(jdbc.queryForObject("select user_name from tbl_user where id=?",String.class,actor)).isEqualTo(oldName);
        deletion.deleteSelf(actor,"fixture-password",null);assertThat(rows()).isEmpty();assertThat(receipts()).isEqualTo(2);
    }

    @Test void domainRollbackDoesNotLeaveLikeCommentInboxReceiptOrAfterCommitWork() {
        assertThatThrownBy(() -> tx(() -> {produceBoth();throw new IllegalStateException("rollback fixture");})).hasMessage("rollback fixture");
        assertThat(rows()).isEmpty();assertThat(receipts()).isZero();assertThat(deliveries).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from tbl_like where target_id=?",Long.class,asset)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from tbl_comment where target_id=?",Long.class,asset)).isZero();
    }

    @Test void selfPrivateAndUnsupportedEngagementCannotCreateOwnerNotifications() {
        likes.like(recipient,EngagementTargetType.MEDIA,asset);
        var own=comments.createComment(recipient,EngagementTargetType.MEDIA,asset,new CommentCreateRequestDto("self",null));
        likes.setCommentLike(actor,own.id(),true);
        assertThat(rows()).isEmpty();assertThat(receipts()).isZero();
        jdbc.update("update tbl_media_asset set visibility='PRIVATE' where id=?",asset);
        assertThatThrownBy(() -> produceBoth()).isInstanceOf(RuntimeException.class);
        assertThat(rows()).isEmpty();assertThat(receipts()).isZero();
    }

    @Test void queuedWebsocketAndMailUseCurrentAliasAndNeverOldSubjectBodyOrHtml() {
        produceBoth();var originals=rows();
        jdbc.update("update tbl_user set user_name='fresh_alias' where id=?",actor);
        jdbc.update("update \"tbl_listener-profile\" set visibility_mode='GHOST' where user_id=?",actor);
        List<NotificationResponseDto> delivered=new ArrayList<>();
        doAnswer(call -> {delivered.add(call.getArgument(1));return null;}).when(sockets).sendNotificationToUser(any(),any());
        deliveries.forEach(Runnable::run);
        assertThat(delivered).hasSize(2).allSatisfy(n -> assertThat(n.title()).contains("fresh_alias").doesNotContain(oldName));
        for(var n:originals) mailDelivery.sendIfCurrent(mail(n));
        var subject=org.mockito.ArgumentCaptor.forClass(String.class);var text=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(mailSender,times(2)).send(anyString(),subject.capture(),text.capture(),isNull());
        assertThat(subject.getAllValues()).allSatisfy(s -> assertThat(s).contains("fresh_alias").doesNotContain(oldName));
        assertThat(text.getAllValues()).allSatisfy(s -> assertThat(s).doesNotContain(oldName,"old private body","old html"));
        clearInvocations(mailSender);deletion.deleteSelf(actor,"fixture-password",null);
        originals.forEach(n -> mailDelivery.sendIfCurrent(mail(n)));verifyNoInteractions(mailSender);
    }

    @Test void finalDeliveryHoldsAccountAndVisibilityFenceThroughWebsocketTransport() throws Exception {
        produceBoth();var delivered=new CountDownLatch(1);var release=new CountDownLatch(1);
        doAnswer(call -> {delivered.countDown();await(release);return null;}).when(sockets).sendNotificationToUser(any(),any());
        try(var pool=Executors.newFixedThreadPool(2)) {
            var sending=pool.submit(deliveries.getFirst());assertThat(delivered.await(10,TimeUnit.SECONDS)).isTrue();
            try {
                assertThatThrownBy(() -> jdbc.queryForObject("select id from \"tbl_listener-profile\" where user_id=? for update nowait",UUID.class,actor))
                        .isInstanceOf(org.springframework.dao.DataAccessException.class);
                var erasing=pool.submit(() -> deletion.deleteSelf(actor,"fixture-password",null));
                awaitLock("tbl_user");assertThat(erasing.isDone()).isFalse();release.countDown();
                sending.get(10,TimeUnit.SECONDS);erasing.get(10,TimeUnit.SECONDS);
                clearInvocations(sockets);deliveries.getLast().run();verifyNoInteractions(sockets);
            } finally {release.countDown();}
        }
    }

    @Test void finalMailTransportHoldsPrivacyFenceUntilProviderReturns() throws Exception {
        produceBoth();var n=rows().getFirst();var sent=new CountDownLatch(1);var release=new CountDownLatch(1);
        doAnswer(call -> {sent.countDown();await(release);return null;}).when(mailSender).send(anyString(),anyString(),anyString(),isNull());
        try(var pool=Executors.newFixedThreadPool(2)) {
            var sending=pool.submit(() -> mailDelivery.sendIfCurrent(mail(n)));assertThat(sent.await(10,TimeUnit.SECONDS)).isTrue();
            try {
                var changing=pool.submit(() -> jdbc.update("update \"tbl_listener-profile\" set visibility_mode='GHOST' where user_id=?",actor));
                awaitLock("tbl_listener-profile");assertThat(changing.isDone()).isFalse();release.countDown();
                sending.get(10,TimeUnit.SECONDS);changing.get(10,TimeUnit.SECONDS);
            } finally {release.countDown();}
        }
    }

    @Test void erasureWinningAccountLockRejectsLateRealMutationWithoutReceiptOrInbox() throws Exception {
        var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var erasing=pool.submit(() -> tx(() -> {
                jdbc.queryForObject("select id from tbl_user where id=? for update",UUID.class,actor);
                locked.countDown();await(release);deletion.deleteSelf(actor,"fixture-password",null);return null;
            }));
            assertThat(locked.await(10,TimeUnit.SECONDS)).isTrue();
            try {
                var mutation=pool.submit(() -> likes.like(actor,EngagementTargetType.MEDIA,asset));
                awaitLock("tbl_user");release.countDown();erasing.get(10,TimeUnit.SECONDS);
                assertThatThrownBy(() -> mutation.get(10,TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
            } finally {release.countDown();}
        }
        assertThat(rows()).isEmpty();assertThat(receipts()).isZero();
    }

    @Test void identityReadWaitingForPrivacyWriterUsesCommittedAliasNotEarlierDto() throws Exception {
        produceBoth();var n=rows().getFirst();var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var writer=pool.submit(() -> tx(() -> {
                jdbc.queryForObject("select id from tbl_user where id=? for update",UUID.class,actor);
                locked.countDown();await(release);
                jdbc.update("update tbl_user set user_name='committed_alias' where id=?",actor);
                jdbc.update("update \"tbl_listener-profile\" set visibility_mode='GHOST' where user_id=?",actor);return null;
            }));assertThat(locked.await(10,TimeUnit.SECONDS)).isTrue();
            try {
                var read=pool.submit(() -> reads.refreshActorIdentityForDelivery(dto(n)));awaitLock("tbl_user");release.countDown();
                writer.get(10,TimeUnit.SECONDS);assertThat(read.get(10,TimeUnit.SECONDS).title()).contains("committed_alias").doesNotContain(oldName);
            } finally {release.countDown();}
        }
    }

    @Test void legacyMigrationAndDelayedActorlessEventsStayAnonymousAcrossRealErasure() throws Exception {
        produceBoth();var current=rows();
        List<Notification> legacy=new ArrayList<>();
        for(var n:current) {
            var old=tx(() -> persist(Notification.builder().recipientId(recipient).type(n.getType()).sourceEventId(UUID.randomUUID())
                    .title(oldName+" old notification").message("Old message").occurredAt(Instant.now()).read(n.getType()==NotificationType.SOCIAL_COMMENT)
                    .payload(Map.of("targetType","MEDIA","targetId",asset.toString(),"unrelated",7)).build()));
            legacy.add(old);
            jdbc.update("insert into tbl_notification_receipt(source_event_id,recipient_id,recorded_at) values(?,?,now())",old.getSourceEventId(),recipient);
        }
        try(var c=dataSource.getConnection();var s=c.createStatement()) {
            s.execute(Files.readString(Path.of("scripts/db/2026-09-28-media-notification-identity.sql")));
        }
        for(var n:legacy) {
            var exact=reads.getUserNotification(recipient,n.getId());
            assertThat(exact.title()).startsWith("Bir kullanıcı").doesNotContain(oldName);
            assertThat(exact.read()).isEqualTo(n.isRead());
            assertThat(exact.payload()).containsEntry("unrelated",7).containsEntry("mediaIdentityVersion",0).doesNotContainKey("actorId");
            assertThat(notificationRow(n.getId())).doesNotContain(oldName);
        }
        deletion.deleteSelf(actor,"fixture-password",null);
        assertThat(rows()).hasSize(2).allSatisfy(n -> assertThat(n.getTitle()).startsWith("Bir kullanıcı"));
        assertThat(receipts()).isEqualTo(4);
        for(var n:legacy) consumer.handle(event(n)); // old event replay cannot resurrect/read-reset either row
        assertThat(rows()).hasSize(2);assertThat(receipts()).isEqualTo(4);
        var delayed=event(legacy.getFirst());
        consumer.handle(new NotificationInboundEvent(UUID.randomUUID(),recipient,delayed.type(),oldName,"old message",delayed.payload(),false,Instant.now()));
        assertThat(rows()).hasSize(3).allSatisfy(n -> {assertThat(n.getTitle()).startsWith("Bir kullanıcı");assertThat(n.getPayload()).doesNotContainKey("actorId");});
    }

    @Test void bandMediaNotifiesOnlyActiveFounderManagerAndStillSuppressesSelfOwnedFanout() {
        UUID[] band=tx(() -> {
            var b=persist(com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity.Band.builder().name("Fixture "+UUID.randomUUID()).build());
            List<UUID> ids=new ArrayList<>();ids.add(b.getId());
            for(var role:com.berkayb.soundconnect.modules.profile.MusicianProfile.band.enums.BandRole.values()) {
                User u=user();ids.add(u.getId());
                persist(com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity.BandMember.builder().band(b).user(u).bandRole(role)
                        .status(com.berkayb.soundconnect.modules.profile.MusicianProfile.band.enums.BandMemberShipStatus.ACTIVE).build());
            }
            return ids.toArray(UUID[]::new);
        });
        asset=tx(() -> persist(MediaAsset.builder().ownerType(MediaOwnerType.BAND).ownerId(band[0]).kind(MediaKind.AUDIO)
                .status(MediaStatus.READY).visibility(MediaVisibility.PUBLIC).size(1L).mimeType("audio/mpeg").sourceUrl("https://fixture.invalid/band").build()).getId());
        List<UUID> managers=jdbc.queryForList("select user_id from tbl_band_member where band_id=? and band_role in ('FOUNDER','MANAGER') order by user_id",UUID.class,band[0]);
        assertThat(managers).hasSize(2);produceBoth();
        assertThat(jdbc.queryForList("select recipient_id from tbl_notification where payload->>'targetId'=? order by recipient_id",UUID.class,asset.toString()))
                .containsExactlyInAnyOrder(managers.getFirst(),managers.getFirst(),managers.getLast(),managers.getLast());
        likes.like(managers.getFirst(),EngagementTargetType.MEDIA,asset);
        assertThat(jdbc.queryForObject("select count(*) from tbl_notification where payload->>'targetId'=?",Long.class,asset.toString())).isEqualTo(4);
    }

    @Test void actualInboxPlannerStoreAndFakeTransportNeverCarryIdentityAndErasureCascadesOnlyLinkedJobs() {
        // Enable MEDIA only in this disposable fixture to exercise the V6 native wire.
        // The shared LOCAL17 allowlist and all native capabilities remain unchanged.
        var properties=new com.berkayb.soundconnect.modules.notification.push.PushProperties();properties.setEnabled(true);
        properties.setAllowedTypes(Set.of(NotificationType.SOCIAL_LIKE,NotificationType.SOCIAL_COMMENT));
        properties.setTokenEncryptionKey(Base64.getEncoder().encodeToString(new byte[32]));
        var cipher=new com.berkayb.soundconnect.modules.notification.push.PushTokenCipher(properties);
        var named=new NamedParameterJdbcTemplate(dataSource);var clock=java.time.Clock.systemUTC();
        var devices=new com.berkayb.soundconnect.modules.notification.push.PushDeviceService(named,new AccountDeliveryFence(named),cipher,properties,clock);
        UUID device=UUID.randomUUID();
        tx(() -> {devices.register(recipient,device,new com.berkayb.soundconnect.modules.notification.push.PushDeviceService.Registration(
                "fixture-device-token",com.berkayb.soundconnect.modules.notification.push.PushDeviceService.Platform.ANDROID,
                com.berkayb.soundconnect.modules.notification.push.PushDeviceService.Permission.AUTHORIZED,"fixture",1L,"ANDROID_NATIVE_V6"));return null;});
        produceBoth();var actorRows=rows();UUID siblingActor=tx(() -> listener().getId());
        UUID sibling=store(NotificationType.SOCIAL_LIKE,Map.of("module","SOCIAL","targetType","MEDIA","targetId",asset.toString(),"actorId",siblingActor.toString(),"mediaIdentityVersion",1),false);
        var planner=new com.berkayb.soundconnect.modules.notification.push.PushDeliveryPlanner(named,properties,clock);
        tx(() -> {rows().forEach(n -> planner.plan(new com.berkayb.soundconnect.modules.notification.push.NotificationPersisted(n)));return null;});
        var proxy=new org.springframework.aop.framework.ProxyFactory(new com.berkayb.soundconnect.modules.notification.push.PushDeliveryStore(named,inbox,policy,cipher,properties,clock));
        proxy.setProxyTargetClass(true);proxy.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(manager,new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        var store=(com.berkayb.soundconnect.modules.notification.push.PushDeliveryStore)proxy.getProxy();
        assertThat(jdbc.queryForObject("select count(*) from tbl_push_delivery where recipient_id=?",Long.class,recipient)).isEqualTo(3);
        var claim=store.claimNext().orElseThrow();var prepared=store.prepare(claim).orElseThrow();
        assertThat(prepared.data()).containsOnlyKeys("notificationId","recipientId","type","presentationVersion","displayVariant","sentAt","expiresAt");
        assertThat(prepared.title()+prepared.body()+prepared.data()).doesNotContain(oldName,actor.toString(),"avatar","private-source");
        deletion.deleteSelf(actor,"fixture-password",null);
        // A prepare-to-network race can still send this generic envelope, but has no identity to leak.
        var fakeTransport=(com.berkayb.soundconnect.modules.notification.push.transport.PushTransport)(envelope -> {
            assertThat(envelope.title()+envelope.body()+envelope.data()).doesNotContain(oldName,actor.toString());
            return com.berkayb.soundconnect.modules.notification.push.transport.PushSendResult.accepted("fixture-provider");
        });
        store.complete(claim,fakeTransport.send(prepared),null);
        assertThat(jdbc.queryForList("select notification_id from tbl_push_delivery where recipient_id=?",UUID.class,recipient)).containsExactly(sibling);
        assertThat(rows()).singleElement().satisfies(n -> assertThat(n.getId()).isEqualTo(sibling));
        assertThat(receipts()).isEqualTo(3);
        actorRows.forEach(n -> consumer.handle(event(n)));assertThat(rows()).hasSize(1);
    }

    @Test void failedRealIdentityQueryCannotProduceASuccessfulOldIdentityResponse() {
        produceBoth();var n=rows().getFirst();
        // Break only the identity scalar query in this disposable database.
        jdbc.execute("alter table tbl_user rename column user_name to fixture_unavailable_name");
        try {
            var response=new java.util.concurrent.atomic.AtomicReference<NotificationResponseDto>();
            Throwable failure=catchThrowable(() -> response.set(reads.getUserNotification(recipient,n.getId())));
            if(failure==null) assertThat(response.get().title()).startsWith("Bir kullanıcı");
            else {assertThat(response.get()).isNull();assertThat(failure).isInstanceOf(org.springframework.transaction.UnexpectedRollbackException.class);}
        } finally {jdbc.execute("alter table tbl_user rename column fixture_unavailable_name to user_name");}
        assertThat(reads.getUserNotification(recipient,n.getId()).title()).contains(oldName);
    }

    UUID store(NotificationType type,Map<String,Object> payload,boolean read) {
        UUID event=UUID.randomUUID();
        tx(() -> {transactional.persistInCurrentTransaction(new NotificationInboundEvent(event,recipient,type,"old snapshot","old",payload,false,Instant.now()));return null;});
        UUID id=jdbc.queryForObject("select id from tbl_notification where source_event_id=?",UUID.class,event);
        if(read)reads.markAsRead(recipient,id);return id;
    }
    String notificationRow(UUID id) {return jdbc.queryForObject("select to_jsonb(n)::text from tbl_notification n where id=?",String.class,id);}
    com.berkayb.soundconnect.shared.mail.dto.MailSendRequest mail(Notification n) {
        return new com.berkayb.soundconnect.shared.mail.dto.MailSendRequest("fixture@invalid.test",oldName,"old html","old private body",
                com.berkayb.soundconnect.shared.mail.enums.MailKind.NOTIFICATION,Map.of("_notificationId",n.getId().toString()));
    }
    void awaitLock(String table) {org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(8)).until(() ->
            jdbc.queryForObject("select count(*) from pg_stat_activity where datname=current_database() and wait_event_type='Lock' and query like ?",Long.class,"%"+table+"%")>0);}
    static void await(CountDownLatch latch) {try {if(!latch.await(15,TimeUnit.SECONDS))throw new IllegalStateException("fixture timeout");}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}

    void produceBoth() {
        likes.like(actor,EngagementTargetType.MEDIA,asset);
        comments.createComment(actor,EngagementTargetType.MEDIA,asset,new CommentCreateRequestDto("private comment never copied",null));
    }
    List<Notification> rows() { return inbox.findByRecipientId(recipient,org.springframework.data.domain.PageRequest.of(0,20)).getContent(); }
    long receipts() { return jdbc.queryForObject("select count(*) from tbl_notification_receipt where recipient_id=?",Long.class,recipient); }
    NotificationResponseDto dto(Notification n) { return new NotificationResponseDto(n.getId(),n.getRecipientId(),n.getType(),n.getTitle(),n.getMessage(),n.isRead(),n.getOccurredAt(),n.getPayload()); }
    NotificationInboundEvent event(Notification n) { return new NotificationInboundEvent(n.getSourceEventId(),n.getRecipientId(),n.getType(),n.getTitle(),n.getMessage(),n.getPayload(),false,n.getOccurredAt()); }
    User user() { return persist(User.builder().username("fixture_"+UUID.randomUUID().toString().replace("-","").substring(0,16))
            .email(UUID.randomUUID()+"@fixture.invalid").password(passwords.encode("fixture-password")).status(UserStatus.ACTIVE).emailVerified(true).build()); }
    User listener() {
        User u=user();
        var role=em.createQuery("select r from Role r where r.name='ROLE_LISTENER'",Role.class).getResultStream().findFirst()
                .orElseGet(() -> persist(Role.builder().name("ROLE_LISTENER").build()));
        u.getRoles().add(role);
        persist(ListenerProfile.builder().user(u).name("Alternative private name").visibilityChoiceCompleted(true).build()); return u;
    }
    <T>T persist(T entity) { em.persist(entity);return entity; }
    <T>T tx(Supplier<T> action) { return new TransactionTemplate(manager).execute(status -> action.get()); }

    @Configuration(proxyBeanMethods=false) @EnableJpaRepositories(basePackages="com.berkayb.soundconnect")
    @EntityScan(basePackages="com.berkayb.soundconnect")
    @Import({JpaAuditingConfig.class,CommentServiceImpl.class,CommentEntityFinder.class,CommentTargetAccessGuard.class,
            CommentAuthorBatchResolver.class,LikeServiceImpl.class,CommentLikeAccessGuard.class,EngagementTargetValidatorImpl.class,
            MediaEngagementNotificationService.class,TransactionalNotificationService.class,NotificationServiceImpl.class,
            GhostListenerIdentityBatchResolver.class,ListenerAccountDeletionService.class,ListenerAccountDataCleaner.class,
            NotificationDeliveryPolicy.class,AccountDeliveryFence.class,NotificationEventListener.class,NotificationMailDelivery.class})
    static class Config {
        @Bean DataSource dataSource() { if(!POSTGRES.isRunning())throw new IllegalStateException("Disposable PG required");
            return new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()); }
        @Bean NamedParameterJdbcTemplate named(DataSource source) { return new NamedParameterJdbcTemplate(source); }
        @Bean com.berkayb.soundconnect.modules.notification.push.PushProperties nativeProperties() {
            return new com.berkayb.soundconnect.modules.notification.push.PushProperties();
        }
        @Bean com.berkayb.soundconnect.modules.notification.push.PushDeliveryPlanner nativePlanner(
                NamedParameterJdbcTemplate jdbc,com.berkayb.soundconnect.modules.notification.push.PushProperties props) {
            return new com.berkayb.soundconnect.modules.notification.push.PushDeliveryPlanner(jdbc,props,java.time.Clock.systemUTC());
        }
        @Bean PasswordEncoder passwords() { return new BCryptPasswordEncoder(4); }
        @Bean CommentMapper comments() { return Mappers.getMapper(CommentMapper.class); }
        @Bean NotificationMapper notifications() { return Mappers.getMapper(NotificationMapper.class); }
    }
}

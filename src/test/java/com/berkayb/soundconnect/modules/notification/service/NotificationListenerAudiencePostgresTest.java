package com.berkayb.soundconnect.modules.notification.service;

import com.berkayb.soundconnect.modules.comment.support.CommentTargetAccessGuard;
import com.berkayb.soundconnect.modules.media.mapper.MediaAssetMapper;
import com.berkayb.soundconnect.modules.media.repository.MediaAssetRepository;
import com.berkayb.soundconnect.modules.notification.dto.response.NotificationResponseDto;
import com.berkayb.soundconnect.modules.notification.entity.Notification;
import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.modules.notification.helper.NotificationBadgeCacheHelper;
import com.berkayb.soundconnect.modules.notification.listener.NotificationEventListener;
import com.berkayb.soundconnect.modules.notification.mapper.NotificationMapperImpl;
import com.berkayb.soundconnect.modules.notification.repository.NotificationReceiptRepository;
import com.berkayb.soundconnect.modules.notification.repository.NotificationRepository;
import com.berkayb.soundconnect.modules.notification.support.NotificationAudienceTestSchema;
import com.berkayb.soundconnect.modules.notification.websocket.NotificationWebSocketService;
import com.berkayb.soundconnect.modules.profile.shared.identity.GhostListenerIdentityBatchResolver;
import com.berkayb.soundconnect.modules.user.support.AccountDeliveryFence;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.berkayb.soundconnect.shared.mail.adapter.MailSenderClient;
import com.berkayb.soundconnect.shared.mail.dto.MailSendRequest;
import com.berkayb.soundconnect.shared.mail.enums.MailKind;
import com.berkayb.soundconnect.shared.mail.producer.MailProducer;
import com.berkayb.soundconnect.shared.messaging.events.notification.NotificationInboundEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static com.berkayb.soundconnect.modules.notification.enums.NotificationType.*;

/** Real recipient roles, SQL pagination, receipt and final-delivery boundaries in a disposable database. */
@Testcontainers
@DataJpaTest(properties = {
        "spring.config.import=", "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.hikari.connection-init-sql=",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@ContextConfiguration(classes = NotificationListenerAudiencePostgresTest.Config.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class NotificationListenerAudiencePostgresTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4")
            .withDatabaseName("notification_listener_audience_test").withUsername("audience_test").withPassword("audience_test");
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }
    private static final Instant NOW = Instant.parse("2026-09-14T10:00:00Z");
    // Independent product contract: do not derive these expectations from category or production policy.
    private static final Set<NotificationType> EXPECTED_LISTENER_TYPES = Set.of(
            ADMIN_BROADCAST,
            AUTH_EMAIL_VERIFIED, AUTH_RESET_PASSWORD,
            MEDIA_UPLOAD_RECEVIED, MEDIA_TRANSCODE_READY, MEDIA_TRANSCODE_FAILED,
            SOCIAL_NEW_FOLLOWER, SOCIAL_NEW_BAND_FOLLOWER, SOCIAL_LIKE, SOCIAL_COMMENT, DM_NEW_MESSAGE,
            TABLE_JOIN_REQUEST_RECEIVED, TABLE_JOIN_REQUEST_APPROVED, TABLE_JOIN_REQUEST_REJECTED,
            TABLE_PARTICIPANT_LEFT, TABLE_REMOVED, TABLE_CANCELLED, TABLE_EXPIRED,
            OVERTHINKING_REVEAL_REQUEST_RECEIVED, OVERTHINKING_REVEAL_REQUEST_APPROVED,
            OVERTHINKING_REVEAL_REQUEST_REJECTED);
    private static final Set<NotificationType> EXPECTED_BUSINESS_TYPES = Set.of(
            STUDIO_RESERVATION_CREATED, STUDIO_RESERVATION_CONFLICTING_REQUESTS,
            STUDIO_RESERVATION_APPROVED, STUDIO_RESERVATION_REJECTED,
            STUDIO_RESERVATION_CANCELLED_BY_CUSTOMER, STUDIO_RESERVATION_CANCELLED_BY_STUDIO,
            VENUE_APPLICATION_APPROVED, VENUE_APPLICATION_REJECTED,
            ARTIST_VENUE_LINK_APPLICATION_REQUEST, ARTIST_VENUE_LINK_APPLICATION_ACCEPT,
            ARTIST_VENUE_LINK_APPLICATION_REJECT,
            EVENT_PERFORMER_ADDED, EVENT_PERFORMER_APPROVAL_REQUESTED, EVENT_PERFORMER_APPROVED,
            EVENT_PERFORMER_REJECTED, EVENT_VENUE_APPROVAL_REQUESTED, EVENT_VENUE_APPROVED, EVENT_VENUE_REJECTED,
            BAND_INVITE_RECEIVED, BAND_INVITE_ACCEPTED, BAND_INVITE_REJECTED, BAND_MEMBER_REMOVED, BAND_MEMBER_LEFT,
            COLLAB_APPLICATION_RECEIVED, COLLAB_APPLICATION_ACCEPTED, COLLAB_APPLICATION_REJECTED,
            COLLAB_APPLICATION_WITHDRAWN, COLLAB_APPLICATION_INVALIDATED, COLLAB_LISTING_EXPIRED,
            COLLAB_JOB_COMPLETION_REQUESTED, COLLAB_JOB_COMPLETED, COLLAB_REVIEW_RECEIVED,
            COLLAB_LISTING_REMOVED, COLLAB_REPORT_RESOLVED);
    // This generic admission fixture does not build campaign provenance or the reveal-request lifecycle.
    // CampaignPostgresTest exercises real campaign admission for every supported recipient profile.
    private static final Set<NotificationType> ADMISSION_SOURCE_EXCLUSIONS = Set.of(
            ADMIN_BROADCAST,
            OVERTHINKING_REVEAL_REQUEST_RECEIVED, OVERTHINKING_REVEAL_REQUEST_APPROVED,
            OVERTHINKING_REVEAL_REQUEST_REJECTED);
    @Autowired NotificationRepository notifications;
    @Autowired NotificationReceiptRepository receipts;
    @Autowired NotificationService service;
    @Autowired NotificationEventListener broker;
    @Autowired TransactionalNotificationService transactional;
    @Autowired NotificationDeliveryPolicy policy;
    @Autowired AfterCommitDeliveryExecutor executor;
    @Autowired NotificationMailDelivery mailDelivery;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoBean NotificationBadgeCacheHelper badges;
    @MockitoBean NotificationWebSocketService websocket;
    @MockitoBean GhostListenerIdentityBatchResolver identities;
    @MockitoBean MailProducer mail;
    @MockitoBean MailSenderClient mailSender;
    TransactionTemplate transaction;
    UUID recipient;

    @BeforeEach void setup() throws Exception {
        assertTypeContract();
        drain();
        transaction = new TransactionTemplate(transactionManager);
        try (var connection = jdbc.getDataSource().getConnection()) {
            assertThat(connection.getCatalog()).isEqualTo("notification_listener_audience_test");
        }
        jdbc.execute("create table if not exists tbl_user(id uuid primary key, status text, email_verified boolean, erased_at timestamp)");
        jdbc.execute("create table if not exists tbl_dm_conversation(id uuid primary key, user_a_id uuid, user_b_id uuid)");
        jdbc.execute("create table if not exists tbl_dm_message(id uuid primary key, conversation_id uuid, sender_id uuid, recipient_id uuid, read_at timestamp, deleted_at timestamp)");
        jdbc.execute("truncate tbl_notification,tbl_notification_receipt,tbl_dm_message,tbl_dm_conversation,user_roles,tbl_role,tbl_user");
        recipient = UUID.randomUUID();
        jdbc.update("insert into tbl_user values (?,'ACTIVE',true,null)", recipient);
        role("ROLE_LISTENER");
        reset(badges, websocket, identities, mail, mailSender);
        SecurityContextHolder.clearContext();
    }

    @Test void explicitAudienceContractPartitionsEveryEnumMember() {
        assertTypeContract();
    }

    @Test void legacyBusinessRowsAreFilteredBeforeLimitsTotalsRecentAndExplicitTypeFilters() {
        var visible = new ArrayList<UUID>();
        for (int i = 0; i < 12; i++) {
            visible.add(seed(NotificationType.TABLE_JOIN_REQUEST_APPROVED, NOW.minusSeconds(i * 3L + 1)).getId());
            seed(NotificationType.STUDIO_RESERVATION_APPROVED, NOW.minusSeconds(i * 3L));
        }
        var first = service.getUserNotifications(recipient, 0, 5);
        var second = service.getUserNotifications(recipient, 1, 5);
        var last = service.getUserNotifications(recipient, 2, 5);
        assertThat(first.getContent()).extracting(NotificationResponseDto::id).containsExactlyElementsOf(visible.subList(0, 5));
        assertThat(second.getContent()).extracting(NotificationResponseDto::id).containsExactlyElementsOf(visible.subList(5, 10));
        assertThat(last.getContent()).extracting(NotificationResponseDto::id).containsExactlyElementsOf(visible.subList(10, 12));
        assertThat(first.getTotalElements()).isEqualTo(12);
        assertThat(first.hasNext()).isTrue(); assertThat(last.hasNext()).isFalse();
        assertThat(service.getRecentNotifications(recipient)).extracting(NotificationResponseDto::id)
                .containsExactlyElementsOf(visible.subList(0, 10));
        var mixed = service.getUserNotificationsByTypes(recipient,
                List.of(NotificationType.STUDIO_RESERVATION_APPROVED, NotificationType.TABLE_JOIN_REQUEST_APPROVED), 0, 5);
        assertThat(mixed.getTotalElements()).isEqualTo(12);
        assertThat(mixed.getContent()).extracting(NotificationResponseDto::id).containsExactlyElementsOf(visible.subList(0, 5));
        assertThat(service.getUserNotificationsByTypes(recipient, List.of(NotificationType.STUDIO_RESERVATION_APPROVED), 0, 5)).isEmpty();
        assertThat(service.getUnreadCount(recipient)).isEqualTo(12);
        verify(badges).setUnreadWithTtl(recipient, 12);
        assertThat(notifications.count()).isEqualTo(24);
        role("ROLE_MUSICIAN");
        assertThat(service.getUserNotifications(recipient, 0, 5).getTotalElements()).isEqualTo(24);
        assertThat(service.getUnreadCount(recipient)).isEqualTo(24);
        role("ROLE_LISTENER");
        assertThat(service.getUnreadCount(recipient)).isEqualTo(12);
        assertThat(notifications.count()).isEqualTo(24);
    }

    @Test void allCurrentConsumerTypesSurviveWhileEveryBusinessTypeIsHiddenWithoutDeletingHistory() {
        for (NotificationType type : NotificationType.values()) seed(type, NOW.minusSeconds(type.ordinal()));
        assertThat(service.getUserNotifications(recipient, 0, 100).getContent()).extracting(NotificationResponseDto::type)
                .containsExactlyInAnyOrderElementsOf(EXPECTED_LISTENER_TYPES);
        assertThat(service.getUnreadCount(recipient)).isEqualTo(EXPECTED_LISTENER_TYPES.size());
        assertThat(notifications.count()).isEqualTo(NotificationType.values().length);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void sourceLessAdminBroadcastCannotEnterListenerInboxOrReplayAfterRoleChange(boolean throughBroker) throws Exception {
        var sibling = seed(TABLE_JOIN_REQUEST_APPROVED, NOW.minusSeconds(1));
        transaction.executeWithoutResult(status ->
                assertThat(receipts.retainForNotification(sibling.getId(), recipient)).isEqualTo(1));
        var originalInbox = inboxRows();
        var originalReceipts = receiptRows();
        var forged = event(ADMIN_BROADCAST);

        send(forged, throughBroker);
        drain();
        assertThat(inboxRows()).containsExactlyInAnyOrderElementsOf(originalInbox);
        var rejectedReceipts = receiptRows();
        assertThat(rejectedReceipts).containsAll(originalReceipts).hasSize(originalReceipts.size() + 1);
        assertThat(rejectedReceipts).filteredOn(row -> row.sourceEventId().equals(forged.eventId()))
                .extracting(ReceiptRow::recipientId).containsExactly(recipient);
        assertThat(rejectedReceipts).extracting(ReceiptRow::recordedAt).doesNotContainNull();
        verifyNoInteractions(websocket, badges, mail, mailSender);

        role("ROLE_MUSICIAN");
        send(forged, throughBroker);
        drain();
        assertThat(inboxRows()).containsExactlyInAnyOrderElementsOf(originalInbox);
        assertThat(receiptRows()).containsExactlyInAnyOrderElementsOf(rejectedReceipts);
        verifyNoInteractions(websocket, badges, mail, mailSender);
    }

    @Test void directLookupMediaResolutionMarkAndClearCannotExposeOrEraseHiddenLegacyRows() {
        var business = seed(NotificationType.COLLAB_APPLICATION_ACCEPTED, NOW);
        var publicItem = seed(NotificationType.SOCIAL_LIKE, NOW.minusSeconds(1));
        assertThat(notifications.findByIdAndRecipientId(business.getId(), recipient)).isEmpty();
        assertThat(notifications.findByIdAndRecipientId(publicItem.getId(), UUID.randomUUID())).isEmpty();
        assertThat(catchThrowableOfType(() -> service.markAsRead(recipient, business.getId()), SoundConnectException.class)
                .getErrorType()).isEqualTo(ErrorType.NOTIFICATION_NOT_FOUND);
        assertThat(catchThrowableOfType(() -> service.deleteById(recipient, business.getId()), SoundConnectException.class)
                .getErrorType()).isEqualTo(ErrorType.NOTIFICATION_NOT_FOUND);
        assertThat(notifications.markAsRead(business.getId(), recipient)).isZero();
        var access = mock(CommentTargetAccessGuard.class);
        var media = mock(MediaAssetRepository.class);
        var mapper = mock(MediaAssetMapper.class);
        var targets = new NotificationMediaTargetService(notifications, access, media, mapper);
        assertThat(catchThrowableOfType(() -> targets.resolve(recipient, business.getId()), SoundConnectException.class)
                .getErrorType()).isEqualTo(ErrorType.NOTIFICATION_NOT_FOUND);
        verifyNoInteractions(access, media, mapper);
        assertThat(service.markAllAsRead(recipient)).isEqualTo(1);
        assertThat(notifications.findById(business.getId()).orElseThrow().isRead()).isFalse();
        assertThat(service.clearAll(recipient)).isEqualTo(1);
        assertThat(notifications.findById(business.getId())).isPresent();
        assertThat(notifications.findById(publicItem.getId())).isEmpty();
        assertThat(service.getUnreadCount(recipient)).isZero();
        verify(websocket, atLeastOnce()).sendUnreadBadgeToUser(recipient, 0);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void brokerAndTransactionalAdmissionUseTheRecipientsDatabaseRoleAndKeepReplayReceipts(boolean throughBroker) throws Exception {
        // Unrelated read/unread rows and their durable receipts must survive every phase.
        var readSibling = seed(TABLE_JOIN_REQUEST_APPROVED, NOW.minusSeconds(2));
        var unreadSibling = seed(TABLE_EXPIRED, NOW.minusSeconds(1));
        assertThat(notifications.markAsRead(readSibling.getId(), recipient)).isEqualTo(1);
        transaction.executeWithoutResult(status -> {
            assertThat(receipts.retainForNotification(readSibling.getId(), recipient)).isEqualTo(1);
            assertThat(receipts.retainForNotification(unreadSibling.getId(), recipient)).isEqualTo(1);
        });
        var originalInbox = inboxRows();
        var originalReceipts = receiptRows();
        assertThat(originalInbox).extracting(InboxRow::id, InboxRow::read).containsExactlyInAnyOrder(
                tuple(readSibling.getId(), true), tuple(unreadSibling.getId(), false));
        var submittedTypes = EnumSet.copyOf(EXPECTED_LISTENER_TYPES);
        submittedTypes.addAll(EXPECTED_BUSINESS_TYPES);
        submittedTypes.removeAll(ADMISSION_SOURCE_EXCLUSIONS);
        var acceptedTypes = EnumSet.copyOf(EXPECTED_LISTENER_TYPES);
        acceptedTypes.removeAll(ADMISSION_SOURCE_EXCLUSIONS);
        var submitted = new ArrayList<NotificationInboundEvent>();
        // A musician sender/authentication must never make a listener recipient eligible.
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("sender", "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_MUSICIAN"))));
        try {
            for (NotificationType type : submittedTypes) {
                var event = event(type);
                submitted.add(event);
                send(event, throughBroker);
            }
        } finally { SecurityContextHolder.clearContext(); }
        drain();
        assertThat(submitted).extracting(NotificationInboundEvent::type).containsExactlyInAnyOrderElementsOf(submittedTypes);
        assertThat(submitted).extracting(NotificationInboundEvent::eventId).doesNotHaveDuplicates().doesNotContainNull();
        var firstInbox = inboxRows();
        var firstReceipts = receiptRows();
        assertThat(firstInbox).containsAll(originalInbox);
        assertThat(firstReceipts).containsAll(originalReceipts);
        var admitted = firstInbox.stream().filter(row -> !originalInbox.contains(row)).toList();
        assertThat(admitted).extracting(InboxRow::type).containsExactlyInAnyOrderElementsOf(acceptedTypes);
        assertThat(admitted).extracting(InboxRow::id).doesNotHaveDuplicates().doesNotContainNull();
        assertThat(admitted).extracting(InboxRow::sourceEventId, InboxRow::recipientId, InboxRow::type, InboxRow::read)
                .containsExactlyInAnyOrderElementsOf(submitted.stream().filter(e -> acceptedTypes.contains(e.type()))
                        .map(e -> tuple(e.eventId(), e.recipientId(), e.type(), false)).toList());
        // Observe rejection from unfiltered PG rows, not from the expected classification.
        var admittedEvents = admitted.stream().map(InboxRow::sourceEventId).toList();
        var rejected = submitted.stream().filter(e -> !admittedEvents.contains(e.eventId())).toList();
        assertThat(rejected).extracting(NotificationInboundEvent::type)
                .containsExactlyInAnyOrderElementsOf(EXPECTED_BUSINESS_TYPES);
        var expectedReceiptKeys = new ArrayList<>(originalReceipts.stream()
                .map(row -> tuple(row.sourceEventId(), row.recipientId())).toList());
        submitted.forEach(e -> expectedReceiptKeys.add(tuple(e.eventId(), e.recipientId())));
        assertThat(firstReceipts).extracting(ReceiptRow::sourceEventId, ReceiptRow::recipientId)
                .containsExactlyInAnyOrderElementsOf(expectedReceiptKeys);
        assertThat(firstReceipts).extracting(ReceiptRow::recordedAt).doesNotContainNull();

        var deliveredTo = ArgumentCaptor.forClass(UUID.class);
        var delivered = ArgumentCaptor.forClass(NotificationResponseDto.class);
        verify(websocket, times(acceptedTypes.size())).sendNotificationToUser(deliveredTo.capture(), delivered.capture());
        assertThat(deliveredTo.getAllValues()).containsOnly(recipient);
        assertThat(delivered.getAllValues()).extracting(NotificationResponseDto::id, NotificationResponseDto::recipientId,
                        NotificationResponseDto::type)
                .containsExactlyInAnyOrderElementsOf(admitted.stream().map(row -> tuple(row.id(), row.recipientId(), row.type())).toList());
        verify(websocket, never()).sendNotificationToUser(any(), argThat(dto -> dto != null && EXPECTED_BUSINESS_TYPES.contains(dto.type())));
        clearInvocations(websocket, badges, mail, mailSender);

        role("ROLE_MUSICIAN");
        assertThat(jdbc.queryForList("select r.name from user_roles ur join tbl_role r on r.id=ur.role_id where ur.user_id=?",
                String.class, recipient)).containsExactly("ROLE_MUSICIAN");
        assertThat(inboxRows()).containsExactlyInAnyOrderElementsOf(firstInbox);
        assertThat(receiptRows()).containsExactlyInAnyOrderElementsOf(firstReceipts);
        for (var rejectedEvent : rejected) send(rejectedEvent, throughBroker);
        drain();
        // Compare notification ID/source/recipient/type/read and receipt ID/recipient/timestamp in real PG.
        assertThat(inboxRows()).containsExactlyInAnyOrderElementsOf(firstInbox);
        assertThat(receiptRows()).containsExactlyInAnyOrderElementsOf(firstReceipts);
        verify(websocket, never()).sendNotificationToUser(any(), argThat(dto -> dto != null && EXPECTED_BUSINESS_TYPES.contains(dto.type())));
        verifyNoInteractions(websocket, badges, mail, mailSender);
        System.out.printf("AUDIENCE_REPLAY_COMPLETE broker=%s submitted=%d admitted=%d rejected=%d inboxAndReceiptIdentityUnchanged=true noReplayDelivery=true%n",
                throughBroker, submitted.size(), admitted.size(), rejected.size());

        // Positive control: this same rejected business payload is eligible with a NEW event ID after the role change.
        // VENUE_APPLICATION_* additionally need their separate source/approval lifecycle; this fixture does not claim that acceptance.
        var previous = rejected.stream().filter(e -> e.type() == COLLAB_REPORT_RESOLVED).findFirst().orElseThrow();
        var fresh = new NotificationInboundEvent(UUID.randomUUID(), previous.recipientId(), previous.type(), previous.title(),
                previous.message(), previous.payload(), previous.emailForce(), previous.occurredAt());
        send(fresh, throughBroker);
        drain();
        var freshInbox = inboxRows();
        assertThat(freshInbox).containsAll(firstInbox).hasSize(firstInbox.size() + 1);
        var newRow = freshInbox.stream().filter(row -> !firstInbox.contains(row)).toList();
        assertThat(newRow).extracting(InboxRow::sourceEventId, InboxRow::recipientId, InboxRow::type, InboxRow::read)
                .containsExactly(tuple(fresh.eventId(), recipient, COLLAB_REPORT_RESOLVED, false));
        assertThat(receiptRows()).containsAll(firstReceipts).hasSize(firstReceipts.size() + 1);
        assertThat(receiptRows()).filteredOn(row -> row.sourceEventId().equals(fresh.eventId()))
                .extracting(ReceiptRow::recipientId).containsExactly(recipient);
        verify(websocket).sendNotificationToUser(eq(recipient), argThat(dto -> dto.id().equals(newRow.getFirst().id())
                && dto.recipientId().equals(recipient) && dto.type() == COLLAB_REPORT_RESOLVED));
        System.out.printf("AUDIENCE_FRESH_EVENT_CONTROL_COMPLETE broker=%s%n", throughBroker);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void finalDispatchRechecksRoleAfterCommitAndHiddenRowsNeverInflateTheNextBadge(boolean throughBroker) throws Exception {
        role("ROLE_MUSICIAN");
        var started = new CountDownLatch(1); var release = new CountDownLatch(1);
        executor.submit(() -> { started.countDown(); await(release); });
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        var business = event(NotificationType.STUDIO_RESERVATION_APPROVED);
        try {
            send(business, throughBroker);
            assertThat(notifications.findBySourceEventId(business.eventId())).isPresent();
            role("ROLE_LISTENER");
            send(event(NotificationType.TABLE_CANCELLED), throughBroker);
        } finally { release.countDown(); }
        drain();
        verify(websocket, never()).sendNotificationToUser(any(), argThat(dto -> dto != null && dto.type() == business.type()));
        verify(websocket).sendNotificationToUser(eq(recipient), argThat(dto -> dto.type() == NotificationType.TABLE_CANCELLED));
        verify(websocket).sendUnreadBadgeToUser(recipient, 1);
        verify(badges).setUnreadWithTtl(recipient, 1);
        assertThat(notifications.count()).isEqualTo(2);
        assertThat(service.getUnreadCount(recipient)).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings = {"ROLE_MUSICIAN", "ROLE_VENUE", "ROLE_STUDIO"})
    void backstageRecipientsKeepTheirBusinessInboxAndLiveDelivery(String recipientRole) throws Exception {
        role(recipientRole);
        var event = event(NotificationType.COLLAB_APPLICATION_ACCEPTED);
        broker.handle(event); drain();
        assertThat(service.getUserNotifications(recipient, 0, 10).getContent()).extracting(NotificationResponseDto::type)
                .containsExactly(event.type());
        verify(websocket).sendNotificationToUser(eq(recipient), argThat(dto -> dto.type() == event.type()));
        verify(websocket).sendUnreadBadgeToUser(recipient, 1);
    }

    @Test void queuedBusinessMailCannotOutliveAChangeToListener() {
        role("ROLE_MUSICIAN");
        var item = seed(NotificationType.VENUE_APPLICATION_REJECTED, NOW);
        role("ROLE_LISTENER");
        mailDelivery.sendIfCurrent(new MailSendRequest("recipient@example.test", "Private venue workflow", null,
                "Business message", MailKind.NOTIFICATION, Map.of("_notificationId", item.getId().toString())));
        verifyNoInteractions(mailSender);
        assertThat(notifications.findById(item.getId())).isPresent();
    }

    private static void assertTypeContract() {
        assertThat(EXPECTED_LISTENER_TYPES).doesNotContainAnyElementsOf(EXPECTED_BUSINESS_TYPES);
        var covered = EnumSet.copyOf(EXPECTED_LISTENER_TYPES);
        covered.addAll(EXPECTED_BUSINESS_TYPES);
        assertThat(covered).containsExactlyInAnyOrderElementsOf(EnumSet.allOf(NotificationType.class));
        assertThat(EXPECTED_LISTENER_TYPES).containsAll(ADMISSION_SOURCE_EXCLUSIONS);
    }
    private record InboxRow(UUID id, UUID sourceEventId, UUID recipientId, NotificationType type, boolean read) { }
    private record ReceiptRow(UUID sourceEventId, UUID recipientId, Instant recordedAt) { }
    private List<InboxRow> inboxRows() {
        return jdbc.query("select id,source_event_id,recipient_id,type,is_read from tbl_notification", (rs, index) ->
                new InboxRow(rs.getObject("id", UUID.class), rs.getObject("source_event_id", UUID.class),
                        rs.getObject("recipient_id", UUID.class), NotificationType.valueOf(rs.getString("type")), rs.getBoolean("is_read")));
    }
    private List<ReceiptRow> receiptRows() {
        return jdbc.query("select source_event_id,recipient_id,recorded_at from tbl_notification_receipt", (rs, index) ->
                new ReceiptRow(rs.getObject("source_event_id", UUID.class), rs.getObject("recipient_id", UUID.class),
                        rs.getTimestamp("recorded_at").toInstant()));
    }
    private Notification seed(NotificationType type, Instant occurredAt) {
        return notifications.saveAndFlush(Notification.builder().sourceEventId(UUID.randomUUID()).recipientId(recipient)
                .type(type).title(type.getDefaultTitle()).message("Existing inbox body").occurredAt(occurredAt)
                .payload(Map.of()).read(false).build());
    }
    private NotificationInboundEvent event(NotificationType type) {
        if (type == NotificationType.DM_NEW_MESSAGE) {
            // Audience tests must supply a valid unread source: admission also
            // verifies source ownership/lifecycle before creating a DM inbox row.
            UUID sender = UUID.randomUUID(), message = UUID.randomUUID(), conversation = UUID.randomUUID();
            jdbc.update("insert into tbl_user values (?,'ACTIVE',true,null)", sender);
            jdbc.update("insert into tbl_dm_conversation values (?,?,?)", conversation, sender, recipient);
            jdbc.update("insert into tbl_dm_message values (?,?,?,?,null,null)", message, conversation, sender, recipient);
            return new NotificationInboundEvent(UUID.randomUUID(), recipient, type, type.getDefaultTitle(), "Current event",
                    Map.of("messageId", message.toString(), "conversationId", conversation.toString(),
                            "senderId", sender.toString(), "recipientId", recipient.toString()), false, NOW);
        }
        return new NotificationInboundEvent(UUID.randomUUID(), recipient, type, type.getDefaultTitle(), "Current event",
                Map.of(), false, NOW);
    }
    private void send(NotificationInboundEvent event, boolean throughBroker) {
        if (throughBroker) broker.handle(event);
        else transaction.executeWithoutResult(status -> transactional.persistInCurrentTransaction(event));
    }
    private void role(String name) {
        transaction.executeWithoutResult(status -> {
            // Domain role writers serialize with the existing account delivery fence.
            jdbc.queryForObject("select id from tbl_user where id=? for update", UUID.class, recipient);
            jdbc.update("delete from user_roles where user_id=?", recipient);
            UUID id = UUID.randomUUID();
            jdbc.update("insert into tbl_role(id,name) values (?,?)", id, name);
            jdbc.update("insert into user_roles(user_id,role_id) values (?,?)", recipient, id);
        });
    }
    private void drain() throws Exception {
        var drained = new CountDownLatch(1); executor.submit(drained::countDown);
        assertThat(drained.await(10, TimeUnit.SECONDS)).isTrue();
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("Delivery gate timed out"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
    }
    @Configuration(proxyBeanMethods = false)
    @EntityScan(basePackageClasses = Notification.class)
    @EnableJpaRepositories(basePackageClasses = NotificationRepository.class)
    @Import({NotificationAudienceTestSchema.class, NotificationEventListener.class, NotificationServiceImpl.class,
            TransactionalNotificationService.class, NotificationDeliveryPolicy.class, AccountDeliveryFence.class,
            AfterCommitDeliveryExecutor.class, NotificationMapperImpl.class, NotificationMailDelivery.class})
    static class Config { }
}

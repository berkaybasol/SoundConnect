package com.berkayb.soundconnect.modules.overthinking.service;

import com.berkayb.soundconnect.modules.like.repository.LikeRepository;
import com.berkayb.soundconnect.modules.comment.support.CommentAuthorBatchResolver;
import com.berkayb.soundconnect.modules.notification.repository.NotificationRepository;
import com.berkayb.soundconnect.modules.notification.helper.NotificationBadgeCacheHelper;
import com.berkayb.soundconnect.modules.notification.websocket.NotificationWebSocketService;
import com.berkayb.soundconnect.modules.notification.listener.NotificationEventListener;
import com.berkayb.soundconnect.modules.notification.mapper.NotificationMapper;
import com.berkayb.soundconnect.modules.notification.service.NotificationService;
import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.shared.mail.producer.MailProducer;
import com.berkayb.soundconnect.shared.messaging.events.notification.NotificationInboundEvent;
import com.berkayb.soundconnect.modules.overthinking.dto.response.OverthinkingRevealRequestResponseDto;
import com.berkayb.soundconnect.modules.overthinking.entity.OverthinkingPost;
import com.berkayb.soundconnect.modules.overthinking.entity.OverthinkingRevealRequest;
import com.berkayb.soundconnect.modules.overthinking.enums.OverthinkingRevealRequestStatus;
import com.berkayb.soundconnect.modules.overthinking.enums.OverthinkingVisibilityType;
import com.berkayb.soundconnect.modules.overthinking.mapper.OverthinkingRevealRequestMapper;
import com.berkayb.soundconnect.modules.overthinking.repository.OverthinkingPostRepository;
import com.berkayb.soundconnect.modules.overthinking.repository.OverthinkingRevealRequestRepository;
import com.berkayb.soundconnect.modules.overthinking.outbox.OverthinkingNotificationOutboxRepository;
import com.berkayb.soundconnect.modules.overthinking.outbox.OverthinkingNotificationOutboxStatus;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.enums.ListenerVisibilityMode;
import com.berkayb.soundconnect.modules.profile.shared.identity.GhostListenerIdentity;
import com.berkayb.soundconnect.modules.profile.shared.identity.GhostListenerIdentityBatchResolver;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.berkayb.soundconnect.modules.user.support.UserEntityFinder;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mapstruct.factory.Mappers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
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

import java.time.Duration;
import java.time.Instant;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Real parent/child locking and consent projection on an isolated PostgreSQL database. */
@Testcontainers
@DataJpaTest(properties = {
        "spring.config.import=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false",
        "spring.datasource.hikari.connection-init-sql=",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@ContextConfiguration(classes = OverthinkingRevealRequestPostgresTest.TestConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OverthinkingRevealRequestPostgresTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.4-alpine")
            .withDatabaseName("overthinking_reveal_test")
            .withUsername("overthinking_test").withPassword("overthinking_test");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", postgres::getJdbcUrl);
        properties.add("spring.datasource.username", postgres::getUsername);
        properties.add("spring.datasource.password", postgres::getPassword);
        properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired OverthinkingRevealRequestService service;
    @Autowired OverthinkingRevealInboxService inbox;
    @Autowired OverthinkingRevealNotificationRetractionService retraction;
    @Autowired NotificationEventListener notificationConsumer;
    @Autowired OverthinkingPostRepository posts;
    @Autowired OverthinkingRevealRequestRepository requests;
    @Autowired OverthinkingNotificationOutboxRepository outbox;
    @Autowired UserRepository users;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean OverthinkingNotificationService notifications;
    @MockitoBean OverthinkingRevealRateGuard rateGuard;
    @MockitoBean GhostListenerIdentityBatchResolver identities;
    @MockitoBean CommentAuthorBatchResolver requesterIdentityResolver;
    @MockitoBean NotificationBadgeCacheHelper notificationBadges;
    @MockitoBean NotificationWebSocketService notificationWebsocket;
    @MockitoBean NotificationMapper notificationMapper;
    @MockitoBean NotificationService notificationService;
    @MockitoBean MailProducer mailProducer;
    private TransactionTemplate transactions;
    private Fixture fixture;

    @BeforeEach
    void setup() throws Exception {
        applyInboxMigration();
        transactions = new TransactionTemplate(transactionManager);
        reset(notifications, identities);
        when(identities.resolve(any())).thenReturn(Map.of());
        fixture = transactions.execute(status -> {
            requests.deleteAllInBatch();
            posts.deleteAllInBatch();
            users.deleteAllInBatch();
            User author = saveUser("author");
            User requester = saveUser("requester");
            User outsider = saveUser("outsider");
            OverthinkingPost post = posts.saveAndFlush(OverthinkingPost.builder()
                    .author(author).title("Anonymous title").content("Anonymous content")
                    .visibilityType(OverthinkingVisibilityType.ANONYMOUS).build());
            return new Fixture(author.getId(), requester.getId(), outsider.getId(), post.getId());
        });
    }

    @Test
    void openingInboxPersistsSeenWithoutDecidingAndRetryDoesNotCreateNewUnread() {
        assertThat(inbox.getUnreadStatus(fixture.authorId).hasUnread()).isFalse();
        var request = service.createRevealRequest(fixture.requesterId, fixture.postId);
        var snapshot = inbox.getUnreadStatus(fixture.authorId);
        assertThat(snapshot.hasUnread()).isTrue();
        assertThat(snapshot.revision()).isEqualTo(1);
        assertThat(inbox.getUnreadStatus(fixture.outsiderId).hasUnread()).isFalse();

        assertThat(inbox.markSeen(fixture.authorId, snapshot.revision()).hasUnread()).isFalse();
        assertThat(service.getIncomingPendingRequestCount(fixture.authorId)).isEqualTo(1);
        assertThat(requests.findById(request.id()).orElseThrow().isPending()).isTrue();
        assertThat(jdbc.queryForObject("select seen_revision from tbl_overthinking_reveal_inbox where author_id=?", Long.class, fixture.authorId)).isEqualTo(1);
        assertThat(service.createRevealRequest(fixture.requesterId, fixture.postId).id()).isEqualTo(request.id());
        assertThat(inbox.getUnreadStatus(fixture.authorId)).isEqualTo(inbox.markSeen(fixture.authorId, 0));
        assertThat(inbox.getUnreadStatus(fixture.authorId).hasUnread()).isFalse();
        assertThat(inbox.getUnreadStatus(fixture.authorId).revision()).isEqualTo(1);
    }

    @Test
    void oldSnapshotCannotAcknowledgeNewArrivalAndDecisionsDoNotReplaceSeenState() {
        var first = service.createRevealRequest(fixture.requesterId, fixture.postId);
        var snapshot = inbox.getUnreadStatus(fixture.authorId);
        var second = service.createRevealRequest(fixture.outsiderId, fixture.postId);
        var afterOldAck = inbox.markSeen(fixture.authorId, snapshot.revision());
        assertThat(afterOldAck.hasUnread()).isTrue();
        assertThat(afterOldAck.revision()).isEqualTo(2);
        service.approveRevealRequest(fixture.authorId, first.id());
        service.rejectRevealRequest(fixture.authorId, second.id());
        assertThat(service.getIncomingPendingRequestCount(fixture.authorId)).isZero();
        assertThat(inbox.getUnreadStatus(fixture.authorId).hasUnread()).isTrue();
        assertThat(inbox.markSeen(fixture.authorId, 2).hasUnread()).isFalse();
        assertThat(inbox.markSeen(fixture.authorId, 1).hasUnread()).isFalse();
        assertThat(inbox.getUnreadStatus(fixture.outsiderId).revision()).isZero();
    }

    @Test
    void deletedRequestsDoNotLeavePhantomDotAndReplacementWithSameCountIsUnread() {
        service.createRevealRequest(fixture.requesterId, fixture.postId);
        var initial = inbox.getUnreadStatus(fixture.authorId);
        inbox.markSeen(fixture.authorId, initial.revision());
        assertThat(service.getIncomingPendingRequestCount(fixture.authorId)).isEqualTo(1);
        transactions.executeWithoutResult(status -> requests.deleteByPostId(fixture.postId));
        assertThat(inbox.getUnreadStatus(fixture.authorId).hasUnread()).isFalse();
        service.createRevealRequest(fixture.outsiderId, fixture.postId);
        assertThat(service.getIncomingPendingRequestCount(fixture.authorId)).isEqualTo(1);
        assertThat(inbox.getUnreadStatus(fixture.authorId).hasUnread()).isTrue();
        assertThat(inbox.getUnreadStatus(fixture.authorId).revision()).isEqualTo(2);
        transactions.executeWithoutResult(status -> {
            requests.deleteByPostId(fixture.postId);
            posts.deleteById(fixture.postId);
        });
        assertThat(inbox.getUnreadStatus(fixture.authorId).hasUnread()).isFalse();
    }

    @Test
    void acknowledgementWaitingForConcurrentArrivalKeepsThatNewRequestUnread() throws Exception {
        service.createRevealRequest(fixture.requesterId, fixture.postId);
        var snapshot = inbox.getUnreadStatus(fixture.authorId);
        CountDownLatch insertPrepared = new CountDownLatch(1), releaseInsert = new CountDownLatch(1);
        doAnswer(call -> {
            requests.flush();
            insertPrepared.countDown();
            waitFor(releaseInsert);
            return null;
        }).when(notifications).sendRevealRequestReceivedNotification(any());
        try (var pool = Executors.newFixedThreadPool(2)) {
            var creation = pool.submit(() -> service.createRevealRequest(fixture.outsiderId, fixture.postId));
            try {
                waitFor(insertPrepared);
                var acknowledgement = pool.submit(() -> inbox.markSeen(fixture.authorId, snapshot.revision()));
                await().atMost(Duration.ofSeconds(10)).until(() -> jdbc.queryForObject(
                        "select count(*) from pg_stat_activity where datname=current_database() and wait_event_type='Lock' and query like '%update tbl_overthinking_reveal_inbox%'", Integer.class) > 0);
                releaseInsert.countDown();
                creation.get(10, TimeUnit.SECONDS);
                assertThat(acknowledgement.get(10, TimeUnit.SECONDS).hasUnread()).isTrue();
                assertThat(inbox.getUnreadStatus(fixture.authorId).revision()).isEqualTo(2);
                assertThat(inbox.getUnreadStatus(fixture.authorId).hasUnread()).isTrue();
            } finally {
                releaseInsert.countDown();
            }
        }
    }

    @Test
    void acknowledgementCannotReadFutureArrivalsAndFailedCreationCannotAdvanceRevision() {
        inbox.markSeen(fixture.authorId, Long.MAX_VALUE);
        service.createRevealRequest(fixture.requesterId, fixture.postId);
        assertThat(inbox.getUnreadStatus(fixture.authorId).hasUnread()).isTrue();
        assertThat(inbox.markSeen(fixture.authorId, Long.MAX_VALUE).hasUnread()).isFalse();
        doAnswer(call -> {
            requests.flush();
            throw new IllegalStateException("outbox unavailable");
        }).when(notifications).sendRevealRequestReceivedNotification(any());
        assertThatThrownBy(() -> service.createRevealRequest(fixture.outsiderId, fixture.postId)).isInstanceOf(IllegalStateException.class);
        assertThat(inbox.getUnreadStatus(fixture.authorId).hasUnread()).isFalse();
        assertThat(inbox.getUnreadStatus(fixture.authorId).revision()).isEqualTo(1);
        doNothing().when(notifications).sendRevealRequestReceivedNotification(any());
        service.createRevealRequest(fixture.outsiderId, fixture.postId);
        assertThat(inbox.getUnreadStatus(fixture.authorId).hasUnread()).isTrue();
        assertThat(inbox.getUnreadStatus(fixture.authorId).revision()).isEqualTo(2);
    }

    @Test
    void inboxMigrationBackfillsDeterministicallyPreservesRowsAndDoesNotResetSeenOnRerun() throws Exception {
        var first = service.createRevealRequest(fixture.requesterId, fixture.postId);
        var second = service.createRevealRequest(fixture.outsiderId, fixture.postId);
        service.approveRevealRequest(fixture.authorId, first.id());
        service.rejectRevealRequest(fixture.authorId, second.id());
        jdbc.update("update tbl_overthinking_reveal_request set created_at='2026-09-01 12:00:00'");
        var before = jdbc.queryForList("select (to_jsonb(request)-'inbox_revision')::text from tbl_overthinking_reveal_request request order by id", String.class);
        // Reproduce the old schema only in this disposable PostgreSQL container.
        jdbc.execute("drop trigger trg_assign_overthinking_reveal_inbox_revision on tbl_overthinking_reveal_request");
        jdbc.execute("alter table tbl_overthinking_reveal_request drop column inbox_revision cascade");
        jdbc.execute("drop table tbl_overthinking_reveal_inbox");
        applyInboxMigration();
        assertThat(jdbc.queryForList("select (to_jsonb(request)-'inbox_revision')::text from tbl_overthinking_reveal_request request order by id", String.class)).isEqualTo(before);
        assertThat(jdbc.queryForList("select inbox_revision from tbl_overthinking_reveal_request order by created_at,id", Long.class)).containsExactly(1L, 2L);
        assertThat(inbox.getUnreadStatus(fixture.authorId).hasUnread()).isTrue();
        inbox.markSeen(fixture.authorId, 2);
        applyInboxMigration();
        assertThat(inbox.getUnreadStatus(fixture.authorId).hasUnread()).isFalse();
        assertThat(inbox.getUnreadStatus(fixture.authorId).revision()).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from soundconnect_schema_migrations where migration_id='2026-09-10-overthinking-inbox-seen'", Long.class)).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update("update tbl_overthinking_reveal_inbox set seen_revision=3 where author_id=?", fixture.authorId))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update tbl_overthinking_reveal_request set inbox_revision=0 where id=?", first.id()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        transactions.executeWithoutResult(status -> requests.deleteByPostId(fixture.postId));
        service.createRevealRequest(fixture.requesterId, fixture.postId);
        assertThat(inbox.getUnreadStatus(fixture.authorId).revision()).isEqualTo(3);
        assertThat(inbox.getUnreadStatus(fixture.authorId).hasUnread()).isTrue();
    }

    @Test
    void withdrawalSuppressesDeliveredAndDelayedEventsButNewRequestRemainsIndependent() {
        var first = service.createRevealRequest(fixture.requesterId, fixture.postId);
        var firstEvent = receivedEvent(first.id());
        notificationConsumer.handle(firstEvent);
        assertThat(notificationCount(firstEvent.eventId())).isEqualTo(1);
        service.cancelRevealRequest(fixture.outsiderId, fixture.postId);
        assertThat(requests.existsById(first.id())).isTrue();
        service.cancelRevealRequest(fixture.requesterId, fixture.postId);
        service.cancelRevealRequest(fixture.requesterId, fixture.postId);
        assertThat(requests.existsById(first.id())).isFalse();
        assertThat(notificationCount(firstEvent.eventId())).isZero();
        assertThat(inbox.getUnreadStatus(fixture.authorId).hasUnread()).isFalse();
        notificationConsumer.handle(firstEvent);
        assertThat(notificationCount(firstEvent.eventId())).isZero();

        var replacement = service.createRevealRequest(fixture.requesterId, fixture.postId);
        var replacementEvent = receivedEvent(replacement.id());
        assertThat(replacement.id()).isNotEqualTo(first.id());
        assertThat(inbox.getUnreadStatus(fixture.authorId).revision()).isEqualTo(2);
        assertThat(inbox.getUnreadStatus(fixture.authorId).hasUnread()).isTrue();
        clearInvocations(notificationWebsocket, notificationBadges);
        service.cancelRevealRequest(fixture.requesterId, fixture.postId);
        verify(notificationWebsocket).sendUnreadBadgeToUser(fixture.authorId, 0L);
        verify(notificationBadges).setUnread(fixture.authorId, 0L);
        notificationConsumer.handle(replacementEvent);
        assertThat(notificationCount(replacementEvent.eventId())).isZero();
        assertThat(inbox.getUnreadStatus(fixture.authorId).hasUnread()).isFalse();

        var newest = service.createRevealRequest(fixture.requesterId, fixture.postId);
        var newestEvent = receivedEvent(newest.id());
        notificationConsumer.handle(newestEvent);
        assertThat(notificationCount(newestEvent.eventId())).isEqualTo(1);
        inbox.markSeen(fixture.authorId, 3);
        notificationConsumer.handle(firstEvent);
        assertThat(inbox.getUnreadStatus(fixture.authorId).hasUnread()).isFalse();
        assertThat(requests.findById(newest.id()).orElseThrow().isPending()).isTrue();
    }

    @Test
    void brokerPublishedButUnconsumedEventSurvivesRetentionUntilCancellationClaimsItsReceipt() {
        var request = service.createRevealRequest(fixture.requesterId, fixture.postId);
        var event = receivedEvent(request.id());
        jdbc.update("update tbl_overthinking_notification_outbox set status='PUBLISHED',published_at=current_timestamp - interval '8 days' where event_id=?", event.eventId());
        transactions.executeWithoutResult(status -> outbox.deletePublishedBefore(
                OverthinkingNotificationOutboxStatus.PUBLISHED, Instant.now().minus(Duration.ofDays(7))));
        assertThat(outbox.existsById(event.eventId())).isTrue();
        service.cancelRevealRequest(fixture.requesterId, fixture.postId);
        transactions.executeWithoutResult(status -> outbox.deletePublishedBefore(
                OverthinkingNotificationOutboxStatus.PUBLISHED, Instant.now().minus(Duration.ofDays(7))));
        assertThat(outbox.existsById(event.eventId())).isFalse();
        notificationConsumer.handle(event);
        assertThat(notificationCount(event.eventId())).isZero();
        assertThat(inbox.getUnreadStatus(fixture.authorId).hasUnread()).isFalse();
    }

    @Test
    void withdrawalWaitsForClaimedNotificationThenRemovesItAndRetainsReplayReceipt() throws Exception {
        var request = service.createRevealRequest(fixture.requesterId, fixture.postId);
        var event = receivedEvent(request.id());
        CountDownLatch consumed = new CountDownLatch(1), releaseConsumer = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var consuming = pool.submit(() -> transactions.executeWithoutResult(status -> {
                notificationConsumer.handle(event);
                consumed.countDown();
                waitFor(releaseConsumer);
            }));
            try {
                waitFor(consumed);
                var cancellation = pool.submit(() -> service.cancelRevealRequest(fixture.requesterId, fixture.postId));
                await().atMost(Duration.ofSeconds(10)).until(() -> jdbc.queryForObject(
                        "select count(*) from pg_stat_activity where datname=current_database() and wait_event_type='Lock' and query like '%insert into tbl_notification_receipt%'", Integer.class) > 0);
                releaseConsumer.countDown();
                consuming.get(10, TimeUnit.SECONDS);
                cancellation.get(10, TimeUnit.SECONDS);
                assertThat(notificationCount(event.eventId())).isZero();
                notificationConsumer.handle(event);
                assertThat(notificationCount(event.eventId())).isZero();
                assertThat(inbox.getUnreadStatus(fixture.authorId).hasUnread()).isFalse();
            } finally { releaseConsumer.countDown(); }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void cancellationAndApprovalShareDecisionBoundaryAndCannotEraseConsent(boolean approveFirst) throws Exception {
        var request = service.createRevealRequest(fixture.requesterId, fixture.postId);
        CountDownLatch prepared = new CountDownLatch(1), release = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> transactions.executeWithoutResult(status -> {
                if (approveFirst) service.approveRevealRequest(fixture.authorId, request.id());
                else service.cancelRevealRequest(fixture.requesterId, fixture.postId);
                prepared.countDown(); waitFor(release);
            }));
            try {
                waitFor(prepared);
                var second = pool.submit(() -> domainError(() -> {
                    if (approveFirst) service.cancelRevealRequest(fixture.requesterId, fixture.postId);
                    else service.approveRevealRequest(fixture.authorId, request.id());
                    return null;
                }));
                awaitPostLockWaiter();
                release.countDown();
                first.get(10, TimeUnit.SECONDS);
                assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo(approveFirst
                        ? ErrorType.OVERTHINKING_REVEAL_REQUEST_ALREADY_DECIDED : ErrorType.OVERTHINKING_REVEAL_REQUEST_NOT_FOUND);
                if (approveFirst) assertThat(requests.findById(request.id()).orElseThrow().isApproved()).isTrue();
                else assertThat(requests.existsById(request.id())).isFalse();
            } finally { release.countDown(); }
        }
    }

    @Test
    void decidedRequestsCannotBeCancelledOrPretendToBeNewPendingRequests() {
        var request = service.createRevealRequest(fixture.requesterId, fixture.postId);
        service.approveRevealRequest(fixture.authorId, request.id());
        assertDomainError(() -> { service.cancelRevealRequest(fixture.requesterId, fixture.postId); return null; }, ErrorType.OVERTHINKING_REVEAL_REQUEST_ALREADY_DECIDED);
        assertDomainError(() -> service.createRevealRequest(fixture.requesterId, fixture.postId), ErrorType.OVERTHINKING_REVEAL_REQUEST_ALREADY_DECIDED);
        assertThat(requests.findById(request.id()).orElseThrow().isApproved()).isTrue();
    }

    @Test
    void deletingSourceRetractsDeliveredAndDelayedSnapshotsAndRetainsReplayReceipts() {
        var request = service.createRevealRequest(fixture.requesterId,fixture.postId);
        var delivered = receivedEvent(request.id());
        var delayed = receivedEvent(request.id());
        var unrelated = receivedEvent(request.id());
        jdbc.update("update tbl_overthinking_notification_outbox set payload=jsonb_set(payload,'{postId}',to_jsonb(cast(? as text))) where event_id=?",
                UUID.randomUUID().toString(),unrelated.eventId());
        notificationConsumer.handle(delivered);
        assertThat(notificationCount(delivered.eventId())).isEqualTo(1);
        transactions.executeWithoutResult(status -> {
            posts.findByIdForUpdate(fixture.postId).orElseThrow();
            retraction.retractPost(fixture.authorId,fixture.postId);
            requests.deleteByPostId(fixture.postId);
            posts.deleteById(fixture.postId);
        });
        assertThat(notificationCount(delivered.eventId())).isZero();
        assertThat(outbox.existsById(delivered.eventId())).isFalse();
        assertThat(outbox.existsById(delayed.eventId())).isFalse();
        assertThat(outbox.existsById(unrelated.eventId())).isTrue();
        for (var event : List.of(delivered,delayed)) {
            assertThat(jdbc.queryForObject("select count(*) from tbl_notification_receipt where source_event_id=? and recipient_id=?",
                    Integer.class,event.eventId(),fixture.authorId)).isEqualTo(1);
            notificationConsumer.handle(event);
            assertThat(notificationCount(event.eventId())).isZero();
        }
        assertThat(inbox.getUnreadStatus(fixture.authorId).hasUnread()).isFalse();
    }

    private NotificationInboundEvent receivedEvent(UUID requestId) {
        UUID eventId = UUID.randomUUID();
        var event = NotificationInboundEvent.builder().eventId(eventId).recipientId(fixture.authorId)
                .type(NotificationType.OVERTHINKING_REVEAL_REQUEST_RECEIVED).title("Reveal request").message("Incoming request")
                .occurredAt(Instant.now()).emailForce(false)
                .payload(Map.of("revealRequestId", requestId.toString(), "postId", fixture.postId.toString(),
                        "requesterId",fixture.requesterId.toString())).build();
        jdbc.update("""
                insert into tbl_overthinking_notification_outbox(event_id,recipient_id,notification_type,title,message,payload,email_force,
                occurred_at,status,attempt_count,next_attempt_at,created_at,updated_at)
                values(?,?,'OVERTHINKING_REVEAL_REQUEST_RECEIVED','Reveal request','Incoming request',cast(? as jsonb),false,
                current_timestamp,'PENDING',0,current_timestamp,current_timestamp,current_timestamp)
                """, eventId, fixture.authorId, "{\"revealRequestId\":\"" + requestId + "\",\"postId\":\"" + fixture.postId + "\"}");
        return event;
    }

    private long notificationCount(UUID eventId) {
        return jdbc.queryForObject("select count(*) from tbl_notification where source_event_id=?", Long.class, eventId);
    }

    private void applyInboxMigration() throws Exception {
        try (var connection = jdbc.getDataSource().getConnection()) {
            assertThat(connection.getMetaData().getURL()).isEqualTo(postgres.getJdbcUrl());
            assertThat(connection.getCatalog()).isEqualTo(postgres.getDatabaseName());
            try (var statement = connection.createStatement()) {
                statement.execute(Files.readString(Path.of("scripts/db/2026-09-10-overthinking-inbox-seen.sql")));
            }
        }
    }

    @Test
    void incomingBadgeCountsOnlyOwnersPendingRequestsAcrossPagesAndTracksDecisionsAndDeletion() {
        assertThat(service.getIncomingPendingRequestCount(fixture.authorId)).isZero();
        var first = service.createRevealRequest(fixture.requesterId, fixture.postId);
        var second = service.createRevealRequest(fixture.outsiderId, fixture.postId);
        UUID anotherOwnPost = transactions.execute(status -> posts.saveAndFlush(OverthinkingPost.builder()
                .author(users.getReferenceById(fixture.authorId)).title("Another post").content("Body")
                .visibilityType(OverthinkingVisibilityType.ANONYMOUS).build()).getId());
        UUID otherAuthorsPost = transactions.execute(status -> posts.saveAndFlush(OverthinkingPost.builder()
                .author(users.getReferenceById(fixture.outsiderId)).title("Other author's post").content("Body")
                .visibilityType(OverthinkingVisibilityType.ANONYMOUS).build()).getId());
        service.createRevealRequest(fixture.requesterId, anotherOwnPost);
        service.createRevealRequest(fixture.requesterId, otherAuthorsPost);

        assertThat(service.getIncomingRequests(fixture.authorId, PageRequest.of(0, 1)).getContent()).hasSize(1);
        assertThat(service.getIncomingPendingRequestCount(fixture.authorId)).isEqualTo(3);
        assertThat(service.getIncomingPendingRequestCount(fixture.outsiderId)).isEqualTo(1);
        assertThat(service.getIncomingPendingRequestCount(fixture.requesterId)).isZero();
        service.approveRevealRequest(fixture.authorId, first.id());
        assertThat(service.getIncomingPendingRequestCount(fixture.authorId)).isEqualTo(2);
        service.rejectRevealRequest(fixture.authorId, second.id());
        assertThat(service.getIncomingPendingRequestCount(fixture.authorId)).isEqualTo(1);

        transactions.executeWithoutResult(status -> {
            posts.findByIdForUpdate(anotherOwnPost).orElseThrow();
            requests.deleteByPostId(anotherOwnPost);
            posts.deleteById(anotherOwnPost);
        });
        assertThat(service.getIncomingPendingRequestCount(fixture.authorId)).isZero();
        assertThat(service.getIncomingPendingRequestCount(fixture.outsiderId)).isEqualTo(1);
    }

    @Test
    void pendingAndRejectedWireProjectionsHideAuthorAndKeepGhostRequester() throws Exception {
        var ghost = new GhostListenerIdentity(fixture.requesterId, "ghost-requester", "ghost-avatar.jpg", ListenerVisibilityMode.GHOST);
        when(identities.resolve(any())).thenReturn(Map.of(fixture.requesterId, ghost));
        var pending = service.createRevealRequest(fixture.requesterId, fixture.postId);
        assertPrivate(pending);
        assertThat(pending.requesterUsername()).isEqualTo("ghost-requester");
        assertThat(pending.requesterVisibilityMode()).isEqualTo(ListenerVisibilityMode.GHOST);
        assertPrivate(service.getMySentRequests(fixture.requesterId, PageRequest.of(0, 20)).getContent().getFirst());
        var rejected = service.rejectRevealRequest(fixture.authorId, pending.id());
        assertPrivate(rejected);
        assertPrivate(service.getMySentRequests(fixture.requesterId, PageRequest.of(0, 20)).getContent().getFirst());
        assertDomainError(() -> service.createRevealRequest(fixture.requesterId, fixture.postId), ErrorType.OVERTHINKING_REVEAL_REQUEST_ALREADY_DECIDED);
        assertThat(requests.count()).isEqualTo(1);
        verify(notifications, times(1)).sendRevealRequestReceivedNotification(any());
        verify(notifications, times(1)).sendRevealRequestRejectedNotification(any());
    }

    @Test
    void approvalExposesOnlyAuthorizedAuthorAndRepeatingDecisionDoesNotNotifyAgain() {
        var pending = service.createRevealRequest(fixture.requesterId, fixture.postId);
        var approved = service.approveRevealRequest(fixture.authorId, pending.id());
        assertThat(approved.authorId()).isEqualTo(fixture.authorId);
        assertThat(service.getMySentRequests(fixture.requesterId, PageRequest.of(0, 20))
                .getContent().getFirst().authorId()).isEqualTo(fixture.authorId);
        assertThat(service.approveRevealRequest(fixture.authorId, pending.id()).id()).isEqualTo(pending.id());
        verify(notifications, times(1)).sendRevealRequestApprovedNotification(any());
        assertDomainError(() -> service.rejectRevealRequest(fixture.authorId, pending.id()), ErrorType.OVERTHINKING_REVEAL_REQUEST_INVALID_STATUS);
        assertDomainError(() -> service.approveRevealRequest(fixture.outsiderId, pending.id()), ErrorType.OVERTHINKING_REVEAL_REQUEST_NOT_FOUND);
    }

    @Test
    void simultaneousCreateRetriesReturnSameRequestWithoutUniqueConflict() throws Exception {
        CountDownLatch firstInsideTransaction = new CountDownLatch(1), releaseFirst = new CountDownLatch(1);
        doAnswer(call -> { firstInsideTransaction.countDown(); waitFor(releaseFirst); return null; })
                .when(notifications).sendRevealRequestReceivedNotification(any());
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> service.createRevealRequest(fixture.requesterId, fixture.postId));
            try {
                waitFor(firstInsideTransaction);
                var second = pool.submit(() -> service.createRevealRequest(fixture.requesterId, fixture.postId));
                awaitPostLockWaiter();
                releaseFirst.countDown();
                assertThat(second.get(10, TimeUnit.SECONDS).id()).isEqualTo(first.get(10, TimeUnit.SECONDS).id());
                assertThat(requests.count()).isEqualTo(1);
                verify(notifications, times(1)).sendRevealRequestReceivedNotification(any());
            } finally {
                releaseFirst.countDown();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void oppositeConcurrentDecisionsCannotOverwriteCommittedDecision(boolean approveFirst) throws Exception {
        UUID requestId = service.createRevealRequest(fixture.requesterId, fixture.postId).id();
        clearInvocations(notifications);
        CountDownLatch firstInsideTransaction = new CountDownLatch(1), releaseFirst = new CountDownLatch(1);
        var answer = (org.mockito.stubbing.Answer<Void>) call -> {
            firstInsideTransaction.countDown(); waitFor(releaseFirst); return null;
        };
        if (approveFirst) doAnswer(answer).when(notifications).sendRevealRequestApprovedNotification(any());
        else doAnswer(answer).when(notifications).sendRevealRequestRejectedNotification(any());
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> decide(approveFirst, requestId));
            try {
                waitFor(firstInsideTransaction);
                var second = pool.submit(() -> domainError(() -> decide(!approveFirst, requestId)));
                awaitPostLockWaiter();
                releaseFirst.countDown();
                var expected = approveFirst ? OverthinkingRevealRequestStatus.APPROVED : OverthinkingRevealRequestStatus.REJECTED;
                assertThat(first.get(10, TimeUnit.SECONDS).status()).isEqualTo(expected);
                assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo(ErrorType.OVERTHINKING_REVEAL_REQUEST_INVALID_STATUS);
                assertThat(requests.findById(requestId).orElseThrow().getStatus()).isEqualTo(expected);
                if (approveFirst) {
                    verify(notifications, times(1)).sendRevealRequestApprovedNotification(any());
                    verify(notifications, never()).sendRevealRequestRejectedNotification(any());
                } else {
                    verify(notifications, times(1)).sendRevealRequestRejectedNotification(any());
                    verify(notifications, never()).sendRevealRequestApprovedNotification(any());
                }
            } finally {
                releaseFirst.countDown();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void deletionWinningPostLockPreventsNewRequestAndPendingDecision(boolean existingRequest) throws Exception {
        UUID requestId = existingRequest ? service.createRevealRequest(fixture.requesterId, fixture.postId).id() : null;
        clearInvocations(notifications);
        CountDownLatch deletionPrepared = new CountDownLatch(1), releaseDeletion = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var deletion = pool.submit(() -> transactions.executeWithoutResult(status -> {
                var post = posts.findByIdForUpdate(fixture.postId).orElseThrow();
                requests.deleteByPostId(fixture.postId);
                posts.delete(post);
                posts.flush();
                deletionPrepared.countDown();
                waitFor(releaseDeletion);
            }));
            try {
                waitFor(deletionPrepared);
                var mutation = pool.submit(() -> domainError(() -> existingRequest
                        ? service.approveRevealRequest(fixture.authorId, requestId)
                        : service.createRevealRequest(fixture.requesterId, fixture.postId)));
                awaitPostLockWaiter();
                releaseDeletion.countDown();
                deletion.get(10, TimeUnit.SECONDS);
                assertThat(mutation.get(10, TimeUnit.SECONDS)).isEqualTo(existingRequest
                        ? ErrorType.OVERTHINKING_REVEAL_REQUEST_NOT_FOUND : ErrorType.OVERTHINKING_POST_NOT_FOUND);
                assertThat(posts.existsById(fixture.postId)).isFalse();
                assertThat(requests.count()).isZero();
                verifyNoInteractions(notifications);
            } finally {
                releaseDeletion.countDown();
            }
        }
    }

    @Test
    void inactiveActorCannotCreateOrDecideEvenWhenOldSessionStillExists() {
        UUID requestId = service.createRevealRequest(fixture.requesterId, fixture.postId).id();
        jdbc.update("update tbl_user set status='INACTIVE' where id=?", fixture.authorId);
        assertDomainError(() -> service.approveRevealRequest(fixture.authorId, requestId), ErrorType.UNAUTHORIZED);
        jdbc.update("update tbl_user set email_verified=false where id=?", fixture.requesterId);
        assertDomainError(() -> service.createRevealRequest(fixture.requesterId, fixture.postId), ErrorType.UNAUTHORIZED);
        assertThat(requests.findById(requestId).orElseThrow().getStatus()).isEqualTo(OverthinkingRevealRequestStatus.PENDING);
        verify(notifications, never()).sendRevealRequestApprovedNotification(any());
    }

    @Test
    void selfRequestsAndVisiblePostsAreRejectedBeforeCreatingRecords() {
        assertDomainError(() -> service.createRevealRequest(fixture.authorId, fixture.postId), ErrorType.OVERTHINKING_REVEAL_REQUEST_SELF_NOT_ALLOWED);
        jdbc.update("update tbl_overthinking_post set visibility_type='VISIBLE' where id=?", fixture.postId);
        assertDomainError(() -> service.createRevealRequest(fixture.requesterId, fixture.postId), ErrorType.OVERTHINKING_POST_NOT_ANONYMOUS);
        assertThat(requests.count()).isZero();
        verifyNoInteractions(notifications);
    }

    private User saveUser(String name) {
        return users.saveAndFlush(User.builder().username(name).email(name + "@example.test")
                .password("unused-test-password").emailVerified(true).status(UserStatus.ACTIVE).build());
    }

    private void assertPrivate(OverthinkingRevealRequestResponseDto response) throws Exception {
        assertThat(response.authorId()).isNull();
        var json = new ObjectMapper().findAndRegisterModules().valueToTree(response);
        assertThat(json.has("authorId")).isFalse();
        assertThat(json.toString()).doesNotContain(fixture.authorId.toString());
    }

    private OverthinkingRevealRequestResponseDto decide(boolean approve, UUID requestId) {
        return approve ? service.approveRevealRequest(fixture.authorId, requestId)
                : service.rejectRevealRequest(fixture.authorId, requestId);
    }

    private void awaitPostLockWaiter() {
        // Wait for PostgreSQL to observe contention, not a scheduler-dependent sleep.
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(20)).until(() ->
                jdbc.queryForObject("select count(*) from pg_stat_activity where datname=current_database() "
                        + "and wait_event_type='Lock' and query like '%tbl_overthinking_post%'", Integer.class) > 0);
    }

    private static void waitFor(CountDownLatch latch) {
        try {
            assertThat(latch.await(15, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private static ErrorType domainError(Callable<?> action) {
        try {
            action.call();
            return null;
        } catch (SoundConnectException exception) {
            return exception.getErrorType();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void assertDomainError(Callable<?> action, ErrorType error) {
        assertThat(domainError(action)).isEqualTo(error);
    }

    private record Fixture(UUID authorId, UUID requesterId, UUID outsiderId, UUID postId) { }

    @Configuration
    @EntityScan("com.berkayb.soundconnect")
    @EnableJpaRepositories(basePackageClasses = {OverthinkingPostRepository.class, UserRepository.class, LikeRepository.class,
            NotificationRepository.class, OverthinkingNotificationOutboxRepository.class})
    @Import({OverthinkingRevealRequestServiceImpl.class, OverthinkingRevealParticipantGuard.class, OverthinkingRevealInboxService.class, UserEntityFinder.class,
            OverthinkingRevealNotificationRetractionService.class, NotificationEventListener.class,
            com.berkayb.soundconnect.support.DeliveryPolicyTestSupport.Config.class})
    static class TestConfiguration {
        @Bean OverthinkingRevealRequestMapper revealMapper() {
            return Mappers.getMapper(OverthinkingRevealRequestMapper.class);
        }
    }
}

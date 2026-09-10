package com.berkayb.soundconnect.modules.overthinking.service;

import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.modules.overthinking.entity.OverthinkingPost;
import com.berkayb.soundconnect.modules.overthinking.entity.OverthinkingRevealRequest;
import com.berkayb.soundconnect.modules.overthinking.enums.OverthinkingVisibilityType;
import com.berkayb.soundconnect.modules.overthinking.outbox.OverthinkingNotificationOutbox;
import com.berkayb.soundconnect.modules.overthinking.outbox.OverthinkingNotificationOutboxPublisher;
import com.berkayb.soundconnect.modules.overthinking.outbox.OverthinkingNotificationOutboxTimeProvider;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.shared.messaging.events.notification.NotificationInboundEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OverthinkingNotificationServiceTest {
    @Mock OverthinkingNotificationOutboxPublisher publisher;
    @Mock OverthinkingNotificationOutboxTimeProvider clock;
    @Captor ArgumentCaptor<List<NotificationInboundEvent>> events;
    OverthinkingNotificationServiceImpl service;
    OverthinkingRevealRequest request;
    static final Instant NOW = Instant.parse("2026-09-09T12:00:00Z");

    @BeforeEach void setup() {
        service = new OverthinkingNotificationServiceImpl(publisher, clock);
        lenient().when(clock.now()).thenReturn(NOW);
        User author = User.builder().id(UUID.randomUUID()).username("private-author").build();
        User requester = User.builder().id(UUID.randomUUID()).username("old-requester-name").build();
        OverthinkingPost post = OverthinkingPost.builder().id(UUID.randomUUID()).author(author)
                .title("Geceye bir not").visibilityType(OverthinkingVisibilityType.ANONYMOUS).build();
        request = OverthinkingRevealRequest.builder().id(UUID.randomUUID()).post(post)
                .author(author).requester(requester).build();
    }

    @Test void receivedSnapshotCarriesOnlyPermittedRequesterIdAndNoIdentityNames() {
        service.sendRevealRequestReceivedNotification(request);
        NotificationInboundEvent event = onlyEvent();
        assertThat(event.recipientId()).isEqualTo(request.getAuthor().getId());
        assertThat(event.type()).isEqualTo(NotificationType.OVERTHINKING_REVEAL_REQUEST_RECEIVED);
        assertThat(event.payload()).containsEntry("requesterId", request.getRequester().getId().toString())
                .doesNotContainKeys("authorId", "actorId", "authorUsername", "authorAvatarUrl");
        assertThat(event.payload().values()).doesNotContain(request.getAuthor().getId().toString(), "private-author", "old-requester-name");
        assertThat(event.message()).doesNotContain("private-author", "old-requester-name");
        assertValidSnapshot(event);
    }

    @Test void approvedSnapshotDisclosesAuthorOnlyToTheGrantedRequester() {
        request.approve();
        service.sendRevealRequestApprovedNotification(request);
        NotificationInboundEvent event = onlyEvent();
        assertThat(event.recipientId()).isEqualTo(request.getRequester().getId());
        assertThat(event.type()).isEqualTo(NotificationType.OVERTHINKING_REVEAL_REQUEST_APPROVED);
        assertThat(event.payload()).containsEntry("authorId", request.getAuthor().getId().toString())
                .doesNotContainKeys("actorId", "authorUsername", "authorAvatarUrl");
        assertValidSnapshot(event);
    }

    @Test void rejectedSnapshotCannotRevealAnonymousAuthor() {
        request.reject();
        service.sendRevealRequestRejectedNotification(request);
        NotificationInboundEvent event = onlyEvent();
        assertThat(event.recipientId()).isEqualTo(request.getRequester().getId());
        assertThat(event.type()).isEqualTo(NotificationType.OVERTHINKING_REVEAL_REQUEST_REJECTED);
        assertThat(event.payload()).doesNotContainKeys("authorId", "actorId", "requesterId", "authorUsername", "authorAvatarUrl");
        assertThat(event.payload().values()).doesNotContain(request.getAuthor().getId().toString(), "private-author");
        assertThat(event.message()).doesNotContain("private-author");
        assertValidSnapshot(event);
    }

    @Test void approvalCannotBeEnqueuedBeforeConsentWasGranted() {
        assertThatThrownBy(() -> service.sendRevealRequestApprovedNotification(request)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(publisher);
    }

    @Test void outboxStorageFailurePropagatesSoTheRevealTransactionCanRollBack() {
        IllegalStateException failure = new IllegalStateException("database failure");
        doThrow(failure).when(publisher).enqueueAll(anyList());
        assertThatThrownBy(() -> service.sendRevealRequestReceivedNotification(request)).isSameAs(failure);
    }

    @Test void outboxRejectsFutureActorOrAuthorPayloadAdditionsOnRejectedNotification() {
        request.reject();
        service.sendRevealRequestRejectedNotification(request);
        NotificationInboundEvent safe = onlyEvent();
        for (String key : List.of("authorId", "actorId", "authorUsername", "authorAvatarUrl")) {
            var unsafePayload = new HashMap<>(safe.payload());
            unsafePayload.put(key, request.getAuthor().getId().toString());
            NotificationInboundEvent unsafe = new NotificationInboundEvent(safe.eventId(), safe.recipientId(), safe.type(),
                    safe.title(), safe.message(), unsafePayload, safe.emailForce(), safe.occurredAt());
            assertThatThrownBy(() -> OverthinkingNotificationOutbox.pending(unsafe, NOW)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    private NotificationInboundEvent onlyEvent() {
        verify(publisher).enqueueAll(events.capture());
        assertThat(events.getValue()).hasSize(1);
        return events.getValue().getFirst();
    }

    private void assertValidSnapshot(NotificationInboundEvent event) {
        assertThat(event.eventId()).isNotNull().isNotEqualTo(request.getId())
                .isNotEqualTo(request.getAuthor().getId()).isNotEqualTo(request.getRequester().getId());
        assertThat(event.occurredAt()).isEqualTo(NOW);
        assertThat(event.emailForce()).isFalse();
        assertThat(event.payload()).containsEntry("module", "OVERTHINKING")
                .containsEntry("postId", request.getPost().getId().toString())
                .containsEntry("revealRequestId", request.getId().toString());
        assertThatCode(() -> OverthinkingNotificationOutbox.pending(event, NOW)).doesNotThrowAnyException();
    }
}

package com.berkayb.soundconnect.modules.overthinking.outbox;

import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.shared.messaging.events.notification.NotificationInboundEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OverthinkingNotificationOutboxServiceTest {
	private static final Instant NOW = Instant.parse("2026-09-04T12:00:00Z");

	@Mock
	private OverthinkingNotificationOutboxRepository repository;

	@Mock
	private OverthinkingNotificationOutboxTimeProvider timeProvider;

	private OverthinkingNotificationOutboxProperties properties;
	private OverthinkingNotificationOutboxService service;

	@BeforeEach
	void setUp() {
		properties = new OverthinkingNotificationOutboxProperties();
		when(timeProvider.now()).thenReturn(NOW);
		service = new OverthinkingNotificationOutboxService(repository, properties, timeProvider);
	}

	@Test
	void enqueuePersistsTheCompleteStableNotificationSnapshot() {
		NotificationInboundEvent notification = notification();

		service.enqueue(notification);

		ArgumentCaptor<OverthinkingNotificationOutbox> captor =
				ArgumentCaptor.forClass(OverthinkingNotificationOutbox.class);
		verify(repository).save(captor.capture());
		OverthinkingNotificationOutbox stored = captor.getValue();
		assertThat(stored.getEventId()).isEqualTo(notification.eventId());
		assertThat(stored.getRecipientId()).isEqualTo(notification.recipientId());
		assertThat(stored.getNotificationType()).isEqualTo(notification.type());
		assertThat(stored.getPayload()).isEqualTo(notification.payload());
		assertThat(stored.getStatus()).isEqualTo(OverthinkingNotificationOutboxStatus.PENDING);
		assertThat(stored.getAttemptCount()).isZero();
		assertThat(stored.getNextAttemptAt()).isEqualTo(NOW);
	}

	@Test
	void failedClaimUsesExponentialBackoffAndSanitizesOperationalError() {
		properties.setRetryInitialDelay(Duration.ofSeconds(5));
		OverthinkingNotificationOutboxClaim claim = claim(3);
		when(repository.reschedule(
				any(), any(), any(), any(), any(), any(), any()
		)).thenReturn(1);

		assertThat(service.markFailed(claim, "java.net.ConnectException: broker-secret"))
				.isEqualTo(OverthinkingNotificationOutboxService.FailureDisposition.RETRY_SCHEDULED);

		verify(repository).reschedule(
				claim.eventId(),
				claim.leaseOwner(),
				OverthinkingNotificationOutboxStatus.IN_FLIGHT,
				OverthinkingNotificationOutboxStatus.PENDING,
				NOW.plusSeconds(20),
				"java.net.ConnectException__broker-secret",
				NOW
		);
	}

	@Test
	void failedClaimAtAttemptLimitBecomesDeadLetter() {
		properties.setMaxAttempts(2);
		OverthinkingNotificationOutboxClaim claim = claim(2);
		when(repository.markDeadLetter(any(), any(), any(), any(), any(), any())).thenReturn(1);

		assertThat(service.markFailed(claim, "BrokerNack"))
				.isEqualTo(OverthinkingNotificationOutboxService.FailureDisposition.DEAD_LETTER);

		verify(repository).markDeadLetter(
				claim.eventId(),
				claim.leaseOwner(),
				OverthinkingNotificationOutboxStatus.IN_FLIGHT,
				OverthinkingNotificationOutboxStatus.DEAD_LETTER,
				"BrokerNack",
				NOW
		);
	}

	private static NotificationInboundEvent notification() {
		return NotificationInboundEvent.builder()
				.eventId(UUID.randomUUID())
				.recipientId(UUID.randomUUID())
				.type(NotificationType.OVERTHINKING_REVEAL_REQUEST_RECEIVED)
				.title("Profil görüntüleme isteği")
				.message("Birisi bu yazıda profilini görüntülemek istiyor.")
				.payload(OverthinkingOutboxTestEvents.receivedPayload())
				.emailForce(false)
				.occurredAt(NOW.minusSeconds(1))
				.build();
	}

	private static OverthinkingNotificationOutboxClaim claim(int attemptCount) {
		NotificationInboundEvent notification = notification();
		return new OverthinkingNotificationOutboxClaim(
				notification.eventId(),
				notification.recipientId(),
				notification.type(),
				notification.title(),
				notification.message(),
				notification.payload(),
				false,
				notification.occurredAt(),
				attemptCount,
				"node:lease"
		);
	}
}

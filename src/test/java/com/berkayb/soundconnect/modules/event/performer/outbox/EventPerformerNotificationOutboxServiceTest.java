package com.berkayb.soundconnect.modules.event.performer.outbox;

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
class EventPerformerNotificationOutboxServiceTest {
	private static final Instant NOW = Instant.parse("2026-09-04T12:00:00Z");

	@Mock
	private EventPerformerNotificationOutboxRepository repository;

	@Mock
	private EventPerformerNotificationOutboxTimeProvider timeProvider;

	private EventPerformerNotificationOutboxProperties properties;
	private EventPerformerNotificationOutboxService service;

	@BeforeEach
	void setUp() {
		properties = new EventPerformerNotificationOutboxProperties();
		when(timeProvider.now()).thenReturn(NOW);
		service = new EventPerformerNotificationOutboxService(repository, properties, timeProvider);
	}

	@Test
	void enqueuePersistsTheCompleteStableNotificationSnapshot() {
		NotificationInboundEvent notification = notification();

		service.enqueue(notification);

		ArgumentCaptor<EventPerformerNotificationOutbox> captor =
				ArgumentCaptor.forClass(EventPerformerNotificationOutbox.class);
		verify(repository).save(captor.capture());
		EventPerformerNotificationOutbox stored = captor.getValue();
		assertThat(stored.getEventId()).isEqualTo(notification.eventId());
		assertThat(stored.getRecipientId()).isEqualTo(notification.recipientId());
		assertThat(stored.getNotificationType()).isEqualTo(notification.type());
		assertThat(stored.getPayload()).isEqualTo(notification.payload());
		assertThat(stored.getStatus()).isEqualTo(EventPerformerNotificationOutboxStatus.PENDING);
		assertThat(stored.getAttemptCount()).isZero();
		assertThat(stored.getNextAttemptAt()).isEqualTo(NOW);
	}

	@Test
	void failedClaimUsesExponentialBackoffAndSanitizesOperationalError() {
		properties.setRetryInitialDelay(Duration.ofSeconds(5));
		EventPerformerNotificationOutboxClaim claim = claim(3);
		when(repository.reschedule(
				any(), any(), any(), any(), any(), any(), any()
		)).thenReturn(1);

		assertThat(service.markFailed(claim, "java.net.ConnectException: broker-secret"))
				.isEqualTo(EventPerformerNotificationOutboxService.FailureDisposition.RETRY_SCHEDULED);

		verify(repository).reschedule(
				claim.eventId(),
				claim.leaseOwner(),
				EventPerformerNotificationOutboxStatus.IN_FLIGHT,
				EventPerformerNotificationOutboxStatus.PENDING,
				NOW.plusSeconds(20),
				"java.net.ConnectException__broker-secret",
				NOW
		);
	}

	@Test
	void failedClaimAtAttemptLimitBecomesDeadLetter() {
		properties.setMaxAttempts(2);
		EventPerformerNotificationOutboxClaim claim = claim(2);
		when(repository.markDeadLetter(any(), any(), any(), any(), any(), any())).thenReturn(1);

		assertThat(service.markFailed(claim, "BrokerNack"))
				.isEqualTo(EventPerformerNotificationOutboxService.FailureDisposition.DEAD_LETTER);

		verify(repository).markDeadLetter(
				claim.eventId(),
				claim.leaseOwner(),
				EventPerformerNotificationOutboxStatus.IN_FLIGHT,
				EventPerformerNotificationOutboxStatus.DEAD_LETTER,
				"BrokerNack",
				NOW
		);
	}

	private static NotificationInboundEvent notification() {
		return NotificationInboundEvent.builder()
				.eventId(UUID.randomUUID())
				.recipientId(UUID.randomUUID())
				.type(NotificationType.EVENT_PERFORMER_APPROVAL_REQUESTED)
				.title("Etkinlik katılım onayı")
				.message("Bir mekân seni etkinliğe eklemek istiyor.")
				.payload(Map.of("module", "EVENT_PERFORMER", "action", "APPROVAL_REQUESTED"))
				.emailForce(false)
				.occurredAt(NOW.minusSeconds(1))
				.build();
	}

	private static EventPerformerNotificationOutboxClaim claim(int attemptCount) {
		NotificationInboundEvent notification = notification();
		return new EventPerformerNotificationOutboxClaim(
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

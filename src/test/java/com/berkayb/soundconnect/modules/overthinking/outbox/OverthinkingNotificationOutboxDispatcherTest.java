package com.berkayb.soundconnect.modules.overthinking.outbox;

import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.shared.messaging.events.notification.NotificationInboundEvent;
import com.berkayb.soundconnect.shared.messaging.events.notification.NotificationProducer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OverthinkingNotificationOutboxDispatcherTest {
	@Mock
	private OverthinkingNotificationOutboxService outboxService;

	@Mock
	private NotificationProducer notificationProducer;

	private OverthinkingNotificationOutboxDispatcher dispatcher;

	@BeforeEach
	void setUp() {
		dispatcher = new OverthinkingNotificationOutboxDispatcher(outboxService, notificationProducer);
	}

	@Test
	void confirmedPublishIsMarkedPublishedWithTheStableEventId() {
		OverthinkingNotificationOutboxClaim claim = claim();
		when(outboxService.claim(any(UUID.class), anyString())).thenReturn(Optional.of(claim));
		when(outboxService.markPublished(claim)).thenReturn(true);

		dispatcher.dispatch(claim.eventId());

		ArgumentCaptor<NotificationInboundEvent> captor =
				ArgumentCaptor.forClass(NotificationInboundEvent.class);
		verify(notificationProducer).publishConfirmed(captor.capture());
		assertThat(captor.getValue().eventId()).isEqualTo(claim.eventId());
		assertThat(captor.getValue().payload()).isEqualTo(claim.payload());
		verify(outboxService).markPublished(claim);
		verify(outboxService, never()).markFailed(any(), anyString());
	}

	@Test
	void brokerFailureSchedulesDurableRetryAndNeverMarksPublished() {
		OverthinkingNotificationOutboxClaim claim = claim();
		when(outboxService.claim(any(UUID.class), anyString())).thenReturn(Optional.of(claim));
		doThrow(new IllegalStateException("broker unavailable"))
				.when(notificationProducer)
				.publishConfirmed(any(NotificationInboundEvent.class));
		when(outboxService.markFailed(claim, IllegalStateException.class.getName()))
				.thenReturn(OverthinkingNotificationOutboxService.FailureDisposition.RETRY_SCHEDULED);

		dispatcher.dispatch(claim.eventId());

		verify(outboxService).markFailed(claim, IllegalStateException.class.getName());
		verify(outboxService, never()).markPublished(any());
	}

	private static OverthinkingNotificationOutboxClaim claim() {
		return new OverthinkingNotificationOutboxClaim(
				UUID.randomUUID(),
				UUID.randomUUID(),
				NotificationType.OVERTHINKING_REVEAL_REQUEST_RECEIVED,
				"Profil görüntüleme isteği",
				"Birisi bu yazıda profilini görüntülemek istiyor.",
				OverthinkingOutboxTestEvents.receivedPayload(),
				false,
				Instant.parse("2026-09-04T12:00:00Z"),
				1,
				"node:lease"
		);
	}
}

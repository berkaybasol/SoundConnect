package com.berkayb.soundconnect.modules.event.performer.outbox;

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
class EventPerformerNotificationOutboxDispatcherTest {
	@Mock
	private EventPerformerNotificationOutboxService outboxService;

	@Mock
	private NotificationProducer notificationProducer;

	private EventPerformerNotificationOutboxDispatcher dispatcher;

	@BeforeEach
	void setUp() {
		dispatcher = new EventPerformerNotificationOutboxDispatcher(outboxService, notificationProducer);
	}

	@Test
	void confirmedPublishIsMarkedPublishedWithTheStableEventId() {
		EventPerformerNotificationOutboxClaim claim = claim();
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
		EventPerformerNotificationOutboxClaim claim = claim();
		when(outboxService.claim(any(UUID.class), anyString())).thenReturn(Optional.of(claim));
		doThrow(new IllegalStateException("broker unavailable"))
				.when(notificationProducer)
				.publishConfirmed(any(NotificationInboundEvent.class));
		when(outboxService.markFailed(claim, IllegalStateException.class.getName()))
				.thenReturn(EventPerformerNotificationOutboxService.FailureDisposition.RETRY_SCHEDULED);

		dispatcher.dispatch(claim.eventId());

		verify(outboxService).markFailed(claim, IllegalStateException.class.getName());
		verify(outboxService, never()).markPublished(any());
	}

	private static EventPerformerNotificationOutboxClaim claim() {
		return new EventPerformerNotificationOutboxClaim(
				UUID.randomUUID(),
				UUID.randomUUID(),
				NotificationType.EVENT_PERFORMER_APPROVAL_REQUESTED,
				"Etkinlik katılım onayı",
				"Bir mekân seni etkinliğe eklemek istiyor.",
				Map.of("module", "EVENT_PERFORMER", "action", "APPROVAL_REQUESTED"),
				false,
				Instant.parse("2026-09-04T12:00:00Z"),
				1,
				"node:lease"
		);
	}
}

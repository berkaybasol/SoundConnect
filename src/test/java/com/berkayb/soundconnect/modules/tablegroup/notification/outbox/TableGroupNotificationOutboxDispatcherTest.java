package com.berkayb.soundconnect.modules.tablegroup.notification.outbox;

import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.shared.messaging.events.notification.NotificationProducer;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TableGroupNotificationOutboxDispatcherTest {

	@Test
	void confirmedPublishIsFencedAsPublished() {
		var service = mock(TableGroupNotificationOutboxService.class);
		var producer = mock(NotificationProducer.class);
		UUID eventId = UUID.randomUUID();
		var claim = claim(eventId);
		when(service.claim(eq(eventId), anyString())).thenReturn(Optional.of(claim));
		when(service.markPublished(claim)).thenReturn(true);

		new TableGroupNotificationOutboxDispatcher(service, producer).dispatch(eventId);

		verify(producer).publishConfirmed(argThat(event -> eventId.equals(event.eventId())));
		verify(service).markPublished(claim);
		verify(service, never()).markFailed(any(), anyString());
	}

	@Test
	void brokerFailureLeavesDurableRetryDisposition() {
		var service = mock(TableGroupNotificationOutboxService.class);
		var producer = mock(NotificationProducer.class);
		UUID eventId = UUID.randomUUID();
		var claim = claim(eventId);
		when(service.claim(eq(eventId), anyString())).thenReturn(Optional.of(claim));
		doThrow(new IllegalStateException("broker down")).when(producer).publishConfirmed(any());
		when(service.markFailed(eq(claim), anyString()))
				.thenReturn(TableGroupNotificationOutboxService.FailureDisposition.RETRY_SCHEDULED);

		new TableGroupNotificationOutboxDispatcher(service, producer).dispatch(eventId);

		verify(service).markFailed(eq(claim), contains("IllegalStateException"));
		verify(service, never()).markPublished(any());
	}

	private static TableGroupNotificationOutboxClaim claim(UUID eventId) {
		return new TableGroupNotificationOutboxClaim(
				eventId, UUID.randomUUID(), NotificationType.TABLE_CANCELLED,
				"title", "message", Map.of("module", "TABLE_GROUP"), false,
				Instant.parse("2026-08-17T12:00:00Z"), 1, "node:token"
		);
	}
}

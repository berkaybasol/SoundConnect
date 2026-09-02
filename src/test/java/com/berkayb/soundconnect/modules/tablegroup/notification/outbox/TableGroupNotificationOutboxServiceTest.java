package com.berkayb.soundconnect.modules.tablegroup.notification.outbox;

import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class TableGroupNotificationOutboxServiceTest {

	@Test
	void enqueuePersistsCompleteStableEventSnapshot() {
		var repository = mock(TableGroupNotificationOutboxRepository.class);
		var properties = new TableGroupNotificationOutboxProperties();
		var timeProvider = mock(TableGroupNotificationOutboxTimeProvider.class);
		Instant now = Instant.parse("2026-08-17T12:00:00Z");
		when(timeProvider.now()).thenReturn(now);
		var service = new TableGroupNotificationOutboxService(repository, properties, timeProvider);
		UUID recipientId = UUID.randomUUID();

		service.enqueue(
				recipientId,
				NotificationType.TABLE_JOIN_REQUEST_APPROVED,
				"Onaylandı",
				"Başvurun onaylandı.",
				Map.of("module", "TABLE_GROUP", "action", "JOIN_REQUEST_APPROVED")
		);

		ArgumentCaptor<TableGroupNotificationOutbox> captor =
				ArgumentCaptor.forClass(TableGroupNotificationOutbox.class);
		verify(repository).save(captor.capture());
		TableGroupNotificationOutbox event = captor.getValue();
		assertThat(event.getEventId()).isNotNull();
		assertThat(event.getRecipientId()).isEqualTo(recipientId);
		assertThat(event.getStatus()).isEqualTo(TableGroupNotificationOutboxStatus.PENDING);
		assertThat(event.getOccurredAt()).isEqualTo(now);
		assertThat(event.getNextAttemptAt()).isEqualTo(now);
	}

	@Test
	void markFailedDeadLettersAtConfiguredAttemptLimit() {
		var repository = mock(TableGroupNotificationOutboxRepository.class);
		var properties = new TableGroupNotificationOutboxProperties();
		properties.setMaxAttempts(2);
		var timeProvider = mock(TableGroupNotificationOutboxTimeProvider.class);
		when(timeProvider.now()).thenReturn(Instant.parse("2026-08-17T12:00:00Z"));
		var service = new TableGroupNotificationOutboxService(repository, properties, timeProvider);
		var claim = new TableGroupNotificationOutboxClaim(
				UUID.randomUUID(), UUID.randomUUID(), NotificationType.TABLE_CANCELLED,
				"title", "message", Map.of("module", "TABLE_GROUP"), false,
				Instant.parse("2026-08-17T11:00:00Z"), 2, "node:token"
		);
		when(repository.markDeadLetter(any(), any(), any(), any(), any(), any())).thenReturn(1);

		assertThat(service.markFailed(claim, "broker.failure"))
				.isEqualTo(TableGroupNotificationOutboxService.FailureDisposition.DEAD_LETTER);
	}
}

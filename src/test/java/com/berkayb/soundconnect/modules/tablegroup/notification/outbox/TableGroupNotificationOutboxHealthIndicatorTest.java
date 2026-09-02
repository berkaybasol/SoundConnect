package com.berkayb.soundconnect.modules.tablegroup.notification.outbox;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class TableGroupNotificationOutboxHealthIndicatorTest {
	@Test
	void repositoryFailureMakesReadinessDependencyDown() {
		TableGroupNotificationOutboxRepository repository =
				mock(TableGroupNotificationOutboxRepository.class);
		when(repository.countByStatus(TableGroupNotificationOutboxStatus.PENDING))
				.thenThrow(new IllegalStateException("schema unavailable"));
		TableGroupNotificationOutboxProperties properties = new TableGroupNotificationOutboxProperties();
		TableGroupNotificationOutboxTimeProvider timeProvider = mock(TableGroupNotificationOutboxTimeProvider.class);
		when(timeProvider.now()).thenReturn(Instant.now());

		var health = new TableGroupNotificationOutboxHealthIndicator(
				repository, properties, timeProvider).health();

		assertThat(health.getStatus()).isEqualTo(Status.DOWN);
	}
}

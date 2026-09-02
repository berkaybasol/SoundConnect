package com.berkayb.soundconnect.modules.tablegroup.notification.outbox;

import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;

class TableGroupNotificationDispatchCoordinatorTest {

	@Test
	void duplicateQueuedEventUsesOneProcessLocalSlotAndReleasesAfterRun() {
		List<Runnable> tasks = new ArrayList<>();
		Executor executor = tasks::add;
		var coordinator = new TableGroupNotificationDispatchCoordinator(executor);
		UUID eventId = UUID.randomUUID();

		assertThat(coordinator.trySchedule(eventId, () -> { })).isTrue();
		assertThat(coordinator.trySchedule(eventId, () -> { })).isFalse();
		assertThat(coordinator.scheduledCount()).isEqualTo(1);

		tasks.getFirst().run();

		assertThat(coordinator.scheduledCount()).isZero();
		assertThat(coordinator.trySchedule(eventId, () -> { })).isTrue();
	}
}

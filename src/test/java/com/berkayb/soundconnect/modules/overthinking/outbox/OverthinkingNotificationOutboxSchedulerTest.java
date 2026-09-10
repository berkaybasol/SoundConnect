package com.berkayb.soundconnect.modules.overthinking.outbox;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OverthinkingNotificationOutboxSchedulerTest {
	@Test
	void scheduledCandidatesCannotStarveNewDueRowsAndTickHonorsBatchSize() {
		OverthinkingNotificationOutboxService outboxService =
				mock(OverthinkingNotificationOutboxService.class);
		OverthinkingNotificationOutboxDispatcher dispatcher =
				mock(OverthinkingNotificationOutboxDispatcher.class);
		OverthinkingNotificationOutboxProperties properties = properties(2);
		QueuedExecutor executor = new QueuedExecutor();
		OverthinkingNotificationDispatchCoordinator coordinator =
				new OverthinkingNotificationDispatchCoordinator(executor);
		OverthinkingNotificationOutboxScheduler scheduler =
				new OverthinkingNotificationOutboxScheduler(
						outboxService, dispatcher, coordinator, properties
				);
		UUID alreadyScheduled = UUID.randomUUID();
		UUID nextDueOne = UUID.randomUUID();
		UUID nextDueTwo = UUID.randomUUID();
		UUID beyondBatch = UUID.randomUUID();
		coordinator.trySchedule(alreadyScheduled, () -> { });
		when(outboxService.findDueEventIds(3)).thenReturn(List.of(
				alreadyScheduled, nextDueOne, nextDueTwo, beyondBatch
		));

		scheduler.dispatchDue();

		verify(outboxService).findDueEventIds(3);
		assertThat(coordinator.scheduledCount()).isEqualTo(3);
		executor.runAll();
		verify(dispatcher).dispatch(nextDueOne);
		verify(dispatcher).dispatch(nextDueTwo);
		verify(dispatcher, never()).dispatch(beyondBatch);
		assertThat(coordinator.scheduledCount()).isZero();
	}

	@Test
	void executorRejectionLeavesRowUnfencedForTheNextTick() {
		OverthinkingNotificationOutboxService outboxService =
				mock(OverthinkingNotificationOutboxService.class);
		OverthinkingNotificationOutboxDispatcher dispatcher =
				mock(OverthinkingNotificationOutboxDispatcher.class);
		OverthinkingNotificationOutboxProperties properties = properties(1);
		RejectOnceExecutor executor = new RejectOnceExecutor();
		OverthinkingNotificationDispatchCoordinator coordinator =
				new OverthinkingNotificationDispatchCoordinator(executor);
		OverthinkingNotificationOutboxScheduler scheduler =
				new OverthinkingNotificationOutboxScheduler(
						outboxService, dispatcher, coordinator, properties
				);
		UUID eventId = UUID.randomUUID();
		when(outboxService.findDueEventIds(1)).thenReturn(List.of(eventId));

		scheduler.dispatchDue();
		assertThat(coordinator.scheduledCount()).isZero();
		verify(dispatcher, never()).dispatch(eventId);

		scheduler.dispatchDue();
		executor.runAll();

		verify(outboxService, times(2)).findDueEventIds(1);
		verify(dispatcher).dispatch(eventId);
		assertThat(coordinator.scheduledCount()).isZero();
	}

	private static OverthinkingNotificationOutboxProperties properties(int batchSize) {
		OverthinkingNotificationOutboxProperties properties =
				new OverthinkingNotificationOutboxProperties();
		properties.setBatchSize(batchSize);
		return properties;
	}

	private static class QueuedExecutor implements Executor {
		private final Queue<Runnable> tasks = new ArrayDeque<>();

		@Override
		public void execute(Runnable command) {
			tasks.add(command);
		}

		void runAll() {
			while (!tasks.isEmpty()) {
				tasks.remove().run();
			}
		}
	}

	private static final class RejectOnceExecutor extends QueuedExecutor {
		private boolean reject = true;

		@Override
		public void execute(Runnable command) {
			if (reject) {
				reject = false;
				throw new RejectedExecutionException("full");
			}
			super.execute(command);
		}
	}
}

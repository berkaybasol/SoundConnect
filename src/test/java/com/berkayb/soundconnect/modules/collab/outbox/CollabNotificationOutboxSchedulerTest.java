package com.berkayb.soundconnect.modules.collab.outbox;

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

class CollabNotificationOutboxSchedulerTest {

    @Test
    void scheduledFirstPageCannotStarveNewDueEventsAndTickAddsAtMostBatchSize() {
        CollabNotificationOutboxService outboxService = mock(CollabNotificationOutboxService.class);
        CollabNotificationOutboxDispatcher dispatcher = mock(CollabNotificationOutboxDispatcher.class);
        CollabNotificationOutboxProperties properties = properties(2);
        QueuedExecutor executor = new QueuedExecutor();
        CollabNotificationDispatchCoordinator coordinator =
                new CollabNotificationDispatchCoordinator(executor);
        CollabNotificationOutboxScheduler scheduler = new CollabNotificationOutboxScheduler(
                outboxService, dispatcher, coordinator, properties);
        UUID alreadyScheduledOne = UUID.randomUUID();
        UUID alreadyScheduledTwo = UUID.randomUUID();
        UUID nextDueOne = UUID.randomUUID();
        UUID nextDueTwo = UUID.randomUUID();
        UUID beyondBatch = UUID.randomUUID();
        coordinator.trySchedule(alreadyScheduledOne, () -> { });
        coordinator.trySchedule(alreadyScheduledTwo, () -> { });
        when(outboxService.findDueEventIds(4)).thenReturn(List.of(
                alreadyScheduledOne, alreadyScheduledTwo, nextDueOne, nextDueTwo, beyondBatch));

        scheduler.dispatchDue();

        verify(outboxService).findDueEventIds(4);
        verify(dispatcher, never()).dispatch(nextDueOne);
        assertThat(coordinator.scheduledCount()).isEqualTo(4);
        assertThat(executor.size()).isEqualTo(4);

        executor.runAll();

        verify(dispatcher).dispatch(nextDueOne);
        verify(dispatcher).dispatch(nextDueTwo);
        verify(dispatcher, never()).dispatch(beyondBatch);
        assertThat(coordinator.scheduledCount()).isZero();
    }

    @Test
    void executorRejectionLeavesIdUnfencedAndNextTickCanRecoverIt() {
        CollabNotificationOutboxService outboxService = mock(CollabNotificationOutboxService.class);
        CollabNotificationOutboxDispatcher dispatcher = mock(CollabNotificationOutboxDispatcher.class);
        CollabNotificationOutboxProperties properties = properties(1);
        RejectOnceExecutor executor = new RejectOnceExecutor();
        CollabNotificationDispatchCoordinator coordinator =
                new CollabNotificationDispatchCoordinator(executor);
        CollabNotificationOutboxScheduler scheduler = new CollabNotificationOutboxScheduler(
                outboxService, dispatcher, coordinator, properties);
        UUID eventId = UUID.randomUUID();
        when(outboxService.findDueEventIds(1)).thenReturn(List.of(eventId));

        scheduler.dispatchDue();

        assertThat(coordinator.scheduledCount()).isZero();
        verify(dispatcher, never()).dispatch(eventId);

        scheduler.dispatchDue();
        assertThat(coordinator.scheduledCount()).isOne();
        executor.runAll();

        verify(outboxService, times(2)).findDueEventIds(1);
        verify(dispatcher).dispatch(eventId);
        assertThat(coordinator.scheduledCount()).isZero();
    }

    private static CollabNotificationOutboxProperties properties(int batchSize) {
        CollabNotificationOutboxProperties properties = new CollabNotificationOutboxProperties();
        properties.setBatchSize(batchSize);
        return properties;
    }

    private static class QueuedExecutor implements Executor {
        private final Queue<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        int size() {
            return tasks.size();
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

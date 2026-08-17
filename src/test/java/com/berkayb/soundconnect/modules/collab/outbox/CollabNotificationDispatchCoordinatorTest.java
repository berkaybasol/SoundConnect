package com.berkayb.soundconnect.modules.collab.outbox;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CollabNotificationDispatchCoordinatorTest {

    @Test
    void duplicateIdIsFencedWhileQueuedAndCanBeScheduledAgainAfterCompletion() {
        QueuedExecutor executor = new QueuedExecutor();
        CollabNotificationDispatchCoordinator coordinator =
                new CollabNotificationDispatchCoordinator(executor);
        UUID eventId = UUID.randomUUID();
        AtomicInteger dispatches = new AtomicInteger();

        assertThat(coordinator.trySchedule(eventId, dispatches::incrementAndGet)).isTrue();
        assertThat(coordinator.trySchedule(eventId, dispatches::incrementAndGet)).isFalse();
        assertThat(coordinator.scheduledCount()).isOne();
        assertThat(executor.size()).isOne();

        executor.runNext();

        assertThat(dispatches).hasValue(1);
        assertThat(coordinator.scheduledCount()).isZero();
        assertThat(coordinator.trySchedule(eventId, dispatches::incrementAndGet)).isTrue();
        executor.runNext();
        assertThat(dispatches).hasValue(2);
    }

    @Test
    void taskFailureAlwaysReleasesTheFence() {
        QueuedExecutor executor = new QueuedExecutor();
        CollabNotificationDispatchCoordinator coordinator =
                new CollabNotificationDispatchCoordinator(executor);
        UUID eventId = UUID.randomUUID();
        coordinator.trySchedule(eventId, () -> {
            throw new IllegalStateException("dispatch failed");
        });

        assertThatThrownBy(executor::runNext)
                .isInstanceOf(IllegalStateException.class);
        assertThat(coordinator.scheduledCount()).isZero();
    }

    @Test
    void executorRejectionReleasesTheFenceForARecoverableRetry() {
        Executor rejectingExecutor = command -> {
            throw new RejectedExecutionException("full");
        };
        CollabNotificationDispatchCoordinator coordinator =
                new CollabNotificationDispatchCoordinator(rejectingExecutor);
        UUID eventId = UUID.randomUUID();

        assertThatThrownBy(() -> coordinator.trySchedule(eventId, () -> { }))
                .isInstanceOf(RejectedExecutionException.class);
        assertThat(coordinator.scheduledCount()).isZero();
        assertThatThrownBy(() -> coordinator.trySchedule(eventId, () -> { }))
                .isInstanceOf(RejectedExecutionException.class);
        assertThat(coordinator.scheduledCount()).isZero();
    }

    private static final class QueuedExecutor implements Executor {
        private final Queue<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        int size() {
            return tasks.size();
        }

        void runNext() {
            tasks.remove().run();
        }
    }
}

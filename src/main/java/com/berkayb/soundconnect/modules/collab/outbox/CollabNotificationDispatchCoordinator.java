package com.berkayb.soundconnect.modules.collab.outbox;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * Process-local fence for outbox dispatch tasks that are queued or running.
 *
 * <p>The database claim remains the cross-node authority and deliberately
 * happens inside the worker. This coordinator only prevents the listener and
 * scheduler on the same node from filling the bounded executor with duplicate
 * work for one event.</p>
 */
@Component
public class CollabNotificationDispatchCoordinator {
    private final Executor executor;
    private final Set<UUID> scheduledEventIds = ConcurrentHashMap.newKeySet();

    public CollabNotificationDispatchCoordinator(
            @Qualifier(CollabNotificationOutboxConfiguration.EXECUTOR_BEAN) Executor executor
    ) {
        this.executor = executor;
    }

    /**
     * @return {@code true} when a new task was accepted, {@code false} when
     * the same event is already queued or running on this node
     * @throws RuntimeException when the executor rejects the task; the fence
     * is removed before the exception is propagated
     */
    public boolean trySchedule(UUID eventId, Runnable task) {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(task, "task");
        if (!scheduledEventIds.add(eventId)) {
            return false;
        }

        try {
            executor.execute(() -> {
                try {
                    task.run();
                } finally {
                    scheduledEventIds.remove(eventId);
                }
            });
            return true;
        } catch (RuntimeException | Error exception) {
            scheduledEventIds.remove(eventId);
            throw exception;
        }
    }

    public int scheduledCount() {
        return scheduledEventIds.size();
    }
}

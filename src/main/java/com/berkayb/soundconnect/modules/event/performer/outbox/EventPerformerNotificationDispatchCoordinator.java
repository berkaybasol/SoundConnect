package com.berkayb.soundconnect.modules.event.performer.outbox;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * Process-local fence for event-performer outbox tasks that are queued or running.
 * The database lease remains the cross-node delivery authority.
 */
@Component
public class EventPerformerNotificationDispatchCoordinator {
	private final Executor executor;
	private final Set<UUID> scheduledEventIds = ConcurrentHashMap.newKeySet();

	public EventPerformerNotificationDispatchCoordinator(
			@Qualifier(EventPerformerNotificationOutboxConfiguration.EXECUTOR_BEAN) Executor executor
	) {
		this.executor = executor;
	}

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

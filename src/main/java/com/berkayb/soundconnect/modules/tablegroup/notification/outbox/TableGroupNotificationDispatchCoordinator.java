package com.berkayb.soundconnect.modules.tablegroup.notification.outbox;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.*;

@Component
public class TableGroupNotificationDispatchCoordinator {
	private final Executor executor;
	private final Set<UUID> scheduledEventIds = ConcurrentHashMap.newKeySet();

	public TableGroupNotificationDispatchCoordinator(
			@Qualifier(TableGroupNotificationOutboxConfiguration.EXECUTOR_BEAN) Executor executor
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

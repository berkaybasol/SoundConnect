package com.berkayb.soundconnect.modules.overthinking.outbox;

import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.shared.messaging.events.notification.NotificationInboundEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Persists notifications in the caller's domain transaction, then makes a
 * best-effort fast dispatch only after that transaction has committed. The
 * scheduler remains the durable fallback for process exit or executor pressure.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OverthinkingNotificationOutboxPublisher {
	private final OverthinkingNotificationOutboxService outboxService;
	private final OverthinkingNotificationOutboxDispatcher dispatcher;
	private final OverthinkingNotificationDispatchCoordinator dispatchCoordinator;

	@Transactional(propagation = Propagation.MANDATORY)
	public void enqueueAll(List<NotificationInboundEvent> notifications) {
		if (notifications == null || notifications.isEmpty()) {
			return;
		}

		List<DispatchKey> dispatchKeys = notifications.stream()
				.map(notification -> {
					Objects.requireNonNull(notification, "notification is required");
					outboxService.enqueue(notification);
					return new DispatchKey(
							Objects.requireNonNull(notification.eventId(), "notification.eventId is required"),
							Objects.requireNonNull(notification.type(), "notification.type is required")
					);
				})
				.toList();

		if (TransactionSynchronizationManager.isActualTransactionActive()
				&& TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					dispatchKeys.forEach(OverthinkingNotificationOutboxPublisher.this::schedule);
				}
			});
		} else {
			// MANDATORY makes this branch defensive only. Never dispatch before a
			// commit boundary; the persisted row remains recoverable by the scheduler.
			log.warn("Overthinking notification outbox has no transaction synchronization; scheduler will dispatch");
		}
	}

	private void schedule(DispatchKey key) {
		try {
			dispatchCoordinator.trySchedule(key.eventId(), () -> {
				try {
					dispatcher.dispatch(key.eventId());
				} catch (RuntimeException exception) {
					log.warn(
							"Overthinking notification immediate dispatch task failed; durable row remains recoverable. eventId={}, type={}, exceptionType={}",
							key.eventId(), key.type(), exception.getClass().getName()
					);
				}
			});
		} catch (RuntimeException exception) {
			log.warn(
					"Overthinking notification immediate dispatch was rejected; scheduler will retry. eventId={}, type={}, exceptionType={}",
					key.eventId(), key.type(), exception.getClass().getName()
			);
		}
	}

	private record DispatchKey(UUID eventId, NotificationType type) {
	}
}

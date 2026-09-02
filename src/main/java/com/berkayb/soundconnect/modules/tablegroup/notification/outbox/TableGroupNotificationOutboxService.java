package com.berkayb.soundconnect.modules.tablegroup.notification.outbox;

import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.shared.messaging.events.notification.NotificationInboundEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.time.*;
import java.util.*;

@Service
@RequiredArgsConstructor
public class TableGroupNotificationOutboxService {
	private static final int MAX_ERROR_TYPE_LENGTH = 200;
	private final TableGroupNotificationOutboxRepository repository;
	private final TableGroupNotificationOutboxProperties properties;
	private final TableGroupNotificationOutboxTimeProvider timeProvider;

	@Transactional(propagation = Propagation.MANDATORY)
	public void enqueue(
			UUID recipientId,
			NotificationType type,
			String title,
			String message,
			Map<String, Object> payload
	) {
		Instant now = timeProvider.now();
		NotificationInboundEvent event = NotificationInboundEvent.builder()
				.eventId(UUID.randomUUID())
				.recipientId(recipientId)
				.type(type)
				.title(title)
				.message(message)
				.payload(Map.copyOf(payload))
				.emailForce(type.isEmailRecommended())
				.occurredAt(now)
				.build();
		repository.save(TableGroupNotificationOutbox.pending(event, now));
	}

	@Transactional(readOnly = true)
	public List<UUID> findDueEventIds(int candidateLimit) {
		return repository.findDispatchCandidates(
				TableGroupNotificationOutboxStatus.PENDING,
				TableGroupNotificationOutboxStatus.IN_FLIGHT,
				timeProvider.now(),
				PageRequest.of(0, Math.max(1, candidateLimit))
		);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public Optional<TableGroupNotificationOutboxClaim> claim(UUID eventId, String leaseOwner) {
		Instant now = timeProvider.now();
		int claimed = repository.claim(
				eventId,
				TableGroupNotificationOutboxStatus.PENDING,
				TableGroupNotificationOutboxStatus.IN_FLIGHT,
				leaseOwner,
				now.plus(properties.getLeaseDuration()),
				now
		);
		if (claimed != 1) return Optional.empty();
		return repository.findByEventIdAndStatusAndLeaseOwner(
				eventId, TableGroupNotificationOutboxStatus.IN_FLIGHT, leaseOwner
		).map(this::toClaim);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public boolean markPublished(TableGroupNotificationOutboxClaim claim) {
		return repository.markPublished(
				claim.eventId(), claim.leaseOwner(),
				TableGroupNotificationOutboxStatus.IN_FLIGHT,
				TableGroupNotificationOutboxStatus.PUBLISHED,
				timeProvider.now()
		) == 1;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public FailureDisposition markFailed(TableGroupNotificationOutboxClaim claim, String errorType) {
		Instant now = timeProvider.now();
		String safeError = sanitize(errorType);
		if (claim.attemptCount() >= properties.getMaxAttempts()) {
			int updated = repository.markDeadLetter(
					claim.eventId(), claim.leaseOwner(),
					TableGroupNotificationOutboxStatus.IN_FLIGHT,
					TableGroupNotificationOutboxStatus.DEAD_LETTER,
					safeError, now
			);
			return updated == 1 ? FailureDisposition.DEAD_LETTER : FailureDisposition.LEASE_LOST;
		}
		Instant retryAt = now.plus(backoffForAttempt(claim.attemptCount()));
		int updated = repository.reschedule(
				claim.eventId(), claim.leaseOwner(),
				TableGroupNotificationOutboxStatus.IN_FLIGHT,
				TableGroupNotificationOutboxStatus.PENDING,
				retryAt, safeError, now
		);
		return updated == 1 ? FailureDisposition.RETRY_SCHEDULED : FailureDisposition.LEASE_LOST;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public int cleanupPublished() {
		return repository.deletePublishedBefore(
				TableGroupNotificationOutboxStatus.PUBLISHED,
				timeProvider.now().minus(properties.getPublishedRetention())
		);
	}

	private TableGroupNotificationOutboxClaim toClaim(TableGroupNotificationOutbox event) {
		return new TableGroupNotificationOutboxClaim(
				event.getEventId(), event.getRecipientId(), event.getNotificationType(),
				event.getTitle(), event.getMessage(), event.getPayload(), event.isEmailForce(),
				event.getOccurredAt(), event.getAttemptCount(), event.getLeaseOwner()
		);
	}

	private Duration backoffForAttempt(int attemptCount) {
		int exponent = Math.max(0, Math.min(attemptCount - 1, 30));
		try {
			Duration candidate = properties.getRetryInitialDelay().multipliedBy(1L << exponent);
			return candidate.compareTo(properties.getRetryMaxDelay()) > 0
					? properties.getRetryMaxDelay() : candidate;
		} catch (ArithmeticException exception) {
			return properties.getRetryMaxDelay();
		}
	}

	private static String sanitize(String value) {
		String safe = value == null || value.isBlank()
				? "UnknownPublishFailure"
				: value.replaceAll("[^A-Za-z0-9_.$-]", "_").strip();
		if (safe.isBlank()) safe = "UnknownPublishFailure";
		return safe.substring(0, Math.min(safe.length(), MAX_ERROR_TYPE_LENGTH));
	}

	public enum FailureDisposition { RETRY_SCHEDULED, DEAD_LETTER, LEASE_LOST }
}

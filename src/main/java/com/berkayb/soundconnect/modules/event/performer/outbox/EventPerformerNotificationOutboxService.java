package com.berkayb.soundconnect.modules.event.performer.outbox;

import com.berkayb.soundconnect.shared.messaging.events.notification.NotificationInboundEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class EventPerformerNotificationOutboxService {
	private static final int MAX_ERROR_TYPE_LENGTH = 200;

	private final EventPerformerNotificationOutboxRepository repository;
	private final EventPerformerNotificationOutboxProperties properties;
	private final EventPerformerNotificationOutboxTimeProvider timeProvider;

	@Transactional(propagation = Propagation.MANDATORY)
	public void enqueue(NotificationInboundEvent event) {
		repository.save(EventPerformerNotificationOutbox.pending(event, timeProvider.now()));
	}

	@Transactional(readOnly = true)
	public List<UUID> findDueEventIds(int candidateLimit) {
		return repository.findDispatchCandidates(
				EventPerformerNotificationOutboxStatus.PENDING,
				EventPerformerNotificationOutboxStatus.IN_FLIGHT,
				timeProvider.now(),
				PageRequest.of(0, Math.max(1, candidateLimit))
		);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public Optional<EventPerformerNotificationOutboxClaim> claim(UUID eventId, String leaseOwner) {
		Instant now = timeProvider.now();
		int claimed = repository.claim(
				eventId,
				EventPerformerNotificationOutboxStatus.PENDING,
				EventPerformerNotificationOutboxStatus.IN_FLIGHT,
				leaseOwner,
				now.plus(properties.getLeaseDuration()),
				now
		);
		if (claimed != 1) {
			return Optional.empty();
		}

		return repository.findByEventIdAndStatusAndLeaseOwner(
					eventId,
					EventPerformerNotificationOutboxStatus.IN_FLIGHT,
					leaseOwner
			)
				.map(this::toClaim);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public boolean markPublished(EventPerformerNotificationOutboxClaim claim) {
		return repository.markPublished(
				claim.eventId(),
				claim.leaseOwner(),
				EventPerformerNotificationOutboxStatus.IN_FLIGHT,
				EventPerformerNotificationOutboxStatus.PUBLISHED,
				timeProvider.now()
		) == 1;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public FailureDisposition markFailed(
			EventPerformerNotificationOutboxClaim claim,
			String errorType
	) {
		Instant now = timeProvider.now();
		String safeErrorType = sanitizeErrorType(errorType);

		if (claim.attemptCount() >= properties.getMaxAttempts()) {
			int updated = repository.markDeadLetter(
					claim.eventId(),
					claim.leaseOwner(),
					EventPerformerNotificationOutboxStatus.IN_FLIGHT,
					EventPerformerNotificationOutboxStatus.DEAD_LETTER,
					safeErrorType,
					now
			);
			return updated == 1 ? FailureDisposition.DEAD_LETTER : FailureDisposition.LEASE_LOST;
		}

		Instant nextAttemptAt = now.plus(backoffForAttempt(claim.attemptCount()));
		int updated = repository.reschedule(
				claim.eventId(),
				claim.leaseOwner(),
				EventPerformerNotificationOutboxStatus.IN_FLIGHT,
				EventPerformerNotificationOutboxStatus.PENDING,
				nextAttemptAt,
				safeErrorType,
				now
		);
		return updated == 1 ? FailureDisposition.RETRY_SCHEDULED : FailureDisposition.LEASE_LOST;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public int cleanupPublished() {
		Instant cutoff = timeProvider.now().minus(properties.getPublishedRetention());
		return repository.deletePublishedBefore(EventPerformerNotificationOutboxStatus.PUBLISHED, cutoff);
	}

	private EventPerformerNotificationOutboxClaim toClaim(EventPerformerNotificationOutbox event) {
		return new EventPerformerNotificationOutboxClaim(
				event.getEventId(),
				event.getRecipientId(),
				event.getNotificationType(),
				event.getTitle(),
				event.getMessage(),
				event.getPayload(),
				event.isEmailForce(),
				event.getOccurredAt(),
				event.getAttemptCount(),
				event.getLeaseOwner()
		);
	}

	private Duration backoffForAttempt(int attemptCount) {
		int exponent = Math.max(0, Math.min(attemptCount - 1, 30));
		try {
			Duration candidate = properties.getRetryInitialDelay().multipliedBy(1L << exponent);
			return candidate.compareTo(properties.getRetryMaxDelay()) > 0
					? properties.getRetryMaxDelay()
					: candidate;
		} catch (ArithmeticException exception) {
			return properties.getRetryMaxDelay();
		}
	}

	private static String sanitizeErrorType(String value) {
		String safe = value == null || value.isBlank()
				? "UnknownPublishFailure"
				: value.replaceAll("[^A-Za-z0-9_.$-]", "_").strip();
		if (safe.isBlank()) {
			safe = "UnknownPublishFailure";
		}
		return safe.substring(0, Math.min(safe.length(), MAX_ERROR_TYPE_LENGTH));
	}

	public enum FailureDisposition {
		RETRY_SCHEDULED,
		DEAD_LETTER,
		LEASE_LOST
	}
}

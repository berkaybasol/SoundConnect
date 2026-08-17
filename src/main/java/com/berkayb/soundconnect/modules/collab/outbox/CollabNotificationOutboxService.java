package com.berkayb.soundconnect.modules.collab.outbox;

import com.berkayb.soundconnect.modules.collab.event.CollabNotificationEvent;
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
public class CollabNotificationOutboxService {
    private static final int MAX_ERROR_TYPE_LENGTH = 200;

    private final CollabNotificationOutboxRepository repository;
    private final CollabNotificationOutboxProperties properties;
    private final CollabNotificationOutboxTimeProvider timeProvider;

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(CollabNotificationEvent event) {
        repository.save(CollabNotificationOutbox.pending(event, timeProvider.now()));
    }

    @Transactional(readOnly = true)
    public List<UUID> findDueEventIds(int candidateLimit) {
        int safeLimit = Math.max(1, candidateLimit);
        return repository.findDispatchCandidates(
                CollabNotificationOutboxStatus.PENDING,
                CollabNotificationOutboxStatus.IN_FLIGHT,
                timeProvider.now(),
                PageRequest.of(0, safeLimit)
        );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<CollabNotificationOutboxClaim> claim(UUID eventId, String leaseOwner) {
        Instant now = timeProvider.now();
        int claimed = repository.claim(
                eventId,
                CollabNotificationOutboxStatus.PENDING,
                CollabNotificationOutboxStatus.IN_FLIGHT,
                leaseOwner,
                now.plus(properties.getLeaseDuration()),
                now
        );
        if (claimed != 1) {
            return Optional.empty();
        }

        return repository.findByEventIdAndStatusAndLeaseOwner(
                        eventId,
                        CollabNotificationOutboxStatus.IN_FLIGHT,
                        leaseOwner
                )
                .map(this::toClaim);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markPublished(CollabNotificationOutboxClaim claim) {
        return repository.markPublished(
                claim.eventId(),
                claim.leaseOwner(),
                CollabNotificationOutboxStatus.IN_FLIGHT,
                CollabNotificationOutboxStatus.PUBLISHED,
                timeProvider.now()
        ) == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public FailureDisposition markFailed(CollabNotificationOutboxClaim claim, String errorType) {
        Instant now = timeProvider.now();
        String safeErrorType = sanitizeErrorType(errorType);

        if (claim.attemptCount() >= properties.getMaxAttempts()) {
            int updated = repository.markDeadLetter(
                    claim.eventId(),
                    claim.leaseOwner(),
                    CollabNotificationOutboxStatus.IN_FLIGHT,
                    CollabNotificationOutboxStatus.DEAD_LETTER,
                    safeErrorType,
                    now
            );
            return updated == 1 ? FailureDisposition.DEAD_LETTER : FailureDisposition.LEASE_LOST;
        }

        Instant nextAttemptAt = now.plus(backoffForAttempt(claim.attemptCount()));
        int updated = repository.reschedule(
                claim.eventId(),
                claim.leaseOwner(),
                CollabNotificationOutboxStatus.IN_FLIGHT,
                CollabNotificationOutboxStatus.PENDING,
                nextAttemptAt,
                safeErrorType,
                now
        );
        return updated == 1 ? FailureDisposition.RETRY_SCHEDULED : FailureDisposition.LEASE_LOST;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int cleanupPublished() {
        Instant cutoff = timeProvider.now().minus(properties.getPublishedRetention());
        return repository.deletePublishedBefore(CollabNotificationOutboxStatus.PUBLISHED, cutoff);
    }

    private CollabNotificationOutboxClaim toClaim(CollabNotificationOutbox event) {
        return new CollabNotificationOutboxClaim(
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
        Duration candidate;
        try {
            candidate = properties.getRetryInitialDelay().multipliedBy(1L << exponent);
        } catch (ArithmeticException exception) {
            candidate = properties.getRetryMaxDelay();
        }
        return candidate.compareTo(properties.getRetryMaxDelay()) > 0
                ? properties.getRetryMaxDelay()
                : candidate;
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

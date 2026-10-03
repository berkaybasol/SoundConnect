package com.berkayb.soundconnect.modules.studio.reservation.outbox;

import com.berkayb.soundconnect.modules.studio.reservation.event.StudioReservationNotificationEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
@Slf4j
@RequiredArgsConstructor
public class StudioReservationNotificationOutboxService {
    private static final int MAX_ERROR_TYPE_LENGTH = 200;

    private final StudioReservationNotificationOutboxRepository repository;
    private final StudioReservationNotificationOutboxProperties properties;
    private final StudioReservationNotificationOutboxTimeProvider timeProvider;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(StudioReservationNotificationEvent event) {
        final String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(event.payload());
        } catch (JsonProcessingException exception) {
            // Propagate a safe domain failure so the reservation also rolls back.
            throw new IllegalArgumentException("Cannot serialize studio reservation notification payload");
        }
        int inserted = repository.insertPending(event.eventId(), event.recipientId(), event.type().name(),
                event.title(), event.message(), payloadJson, event.occurredAt(), timeProvider.now());
        if (inserted == 0) {
            // SQL failures propagate. Zero means a replay or unavailable account,
            // never an assumed success after a database error.
            String reason = repository.existsById(event.eventId()) ? "DUPLICATE" : "ACCOUNT_UNAVAILABLE";
            log.debug("Studio reservation notification enqueue skipped. eventId={}, type={}, reason={}",
                    event.eventId(), event.type(), reason);
        }
    }

    @Transactional(readOnly = true)
    public List<UUID> findDueEventIds(int candidateLimit) {
        int safeLimit = Math.max(1, candidateLimit);
        return repository.findDispatchCandidates(
                StudioReservationNotificationOutboxStatus.PENDING,
                StudioReservationNotificationOutboxStatus.IN_FLIGHT,
                timeProvider.now(),
                PageRequest.of(0, safeLimit)
        );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<StudioReservationNotificationOutboxClaim> claim(UUID eventId, String leaseOwner) {
        Instant now = timeProvider.now();
        if (repository.deadLetterExhausted(eventId, properties.getMaxAttempts(),
                StudioReservationNotificationOutboxStatus.PENDING,
                StudioReservationNotificationOutboxStatus.IN_FLIGHT,
                StudioReservationNotificationOutboxStatus.DEAD_LETTER, now) == 1) {
            log.error("Studio reservation notification outbox reached DEAD_LETTER after lease recovery. eventId={}", eventId);
            return Optional.empty();
        }
        int claimed = repository.claim(
                eventId,
                properties.getMaxAttempts(),
                StudioReservationNotificationOutboxStatus.PENDING,
                StudioReservationNotificationOutboxStatus.IN_FLIGHT,
                leaseOwner,
                now.plus(properties.getLeaseDuration()),
                now
        );
        if (claimed != 1) {
            return Optional.empty();
        }

        return repository.findByEventIdAndStatusAndLeaseOwner(
                        eventId,
                        StudioReservationNotificationOutboxStatus.IN_FLIGHT,
                        leaseOwner
                )
                .map(this::toClaim);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markPublished(StudioReservationNotificationOutboxClaim claim) {
        return repository.markPublished(
                claim.eventId(),
                claim.leaseOwner(),
                StudioReservationNotificationOutboxStatus.IN_FLIGHT,
                StudioReservationNotificationOutboxStatus.PUBLISHED,
                timeProvider.now()
        ) == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public FailureDisposition markFailed(StudioReservationNotificationOutboxClaim claim, String errorType) {
        Instant now = timeProvider.now();
        String safeErrorType = sanitizeErrorType(errorType);

        if (claim.attemptCount() >= properties.getMaxAttempts()) {
            int updated = repository.markDeadLetter(
                    claim.eventId(),
                    claim.leaseOwner(),
                    StudioReservationNotificationOutboxStatus.IN_FLIGHT,
                    StudioReservationNotificationOutboxStatus.DEAD_LETTER,
                    safeErrorType,
                    now
            );
            return updated == 1 ? FailureDisposition.DEAD_LETTER : FailureDisposition.LEASE_LOST;
        }

        Instant nextAttemptAt = now.plus(backoffForAttempt(claim.attemptCount()));
        int updated = repository.reschedule(
                claim.eventId(),
                claim.leaseOwner(),
                StudioReservationNotificationOutboxStatus.IN_FLIGHT,
                StudioReservationNotificationOutboxStatus.PENDING,
                nextAttemptAt,
                safeErrorType,
                now
        );
        return updated == 1 ? FailureDisposition.RETRY_SCHEDULED : FailureDisposition.LEASE_LOST;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int cleanupPublished() {
        Instant cutoff = timeProvider.now().minus(properties.getPublishedRetention());
        return repository.deletePublishedBefore(StudioReservationNotificationOutboxStatus.PUBLISHED, cutoff);
    }

    private StudioReservationNotificationOutboxClaim toClaim(StudioReservationNotificationOutbox event) {
        return new StudioReservationNotificationOutboxClaim(
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

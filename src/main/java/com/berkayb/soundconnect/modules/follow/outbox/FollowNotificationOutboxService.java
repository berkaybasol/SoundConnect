package com.berkayb.soundconnect.modules.follow.outbox;


import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.Objects;
import java.nio.charset.StandardCharsets;

@Service
@Slf4j
@RequiredArgsConstructor
public class FollowNotificationOutboxService {
    private static final int MAX_ERROR_TYPE_LENGTH = 200;

    private final FollowNotificationOutboxRepository repository;
    private final FollowNotificationOutboxProperties properties;
    private final FollowNotificationOutboxTimeProvider timeProvider;


    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(UUID occurrenceId, UUID followerId, UUID recipientId, UUID bandId,
                        Instant occurredAt) {
        Objects.requireNonNull(occurrenceId, "occurrenceId");
        Objects.requireNonNull(followerId, "followerId");
        Objects.requireNonNull(recipientId, "recipientId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        var type = bandId == null
                ? NotificationType.SOCIAL_NEW_FOLLOWER
                : NotificationType.SOCIAL_NEW_BAND_FOLLOWER;
        UUID eventId = eventId(occurrenceId, recipientId, type);
        // SQL/constraint failures propagate through the actual follow transaction.
        repository.insertPending(eventId, occurrenceId, followerId, recipientId, bandId,
                type.name(), occurredAt, timeProvider.now());
    }

    public static UUID eventId(UUID occurrenceId, UUID recipientId,
                              NotificationType type) {
        return UUID.nameUUIDFromBytes(("follow:v1:" + occurrenceId + ":" + recipientId + ":" + type.name())
                .getBytes(StandardCharsets.UTF_8));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markSuppressed(FollowNotificationOutboxClaim claim) {
        return repository.markDeadLetter(claim.eventId(), claim.leaseOwner(),
                FollowNotificationOutboxStatus.IN_FLIGHT, FollowNotificationOutboxStatus.SUPPRESSED,
                "SourceUnavailable", timeProvider.now()) == 1;
    }

    @Transactional(readOnly = true)
    public List<UUID> findDueEventIds(int candidateLimit) {
        int safeLimit = Math.max(1, candidateLimit);
        return repository.findDispatchCandidates(
                FollowNotificationOutboxStatus.PENDING,
                FollowNotificationOutboxStatus.IN_FLIGHT,
                timeProvider.now(),
                PageRequest.of(0, safeLimit)
        );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<FollowNotificationOutboxClaim> claim(UUID eventId, String leaseOwner) {
        Instant now = timeProvider.now();
        if (repository.deadLetterExhausted(eventId, properties.getMaxAttempts(),
                FollowNotificationOutboxStatus.PENDING,
                FollowNotificationOutboxStatus.IN_FLIGHT,
                FollowNotificationOutboxStatus.DEAD_LETTER, now) == 1) {
            log.error("Follow notification outbox reached DEAD_LETTER after lease recovery. eventId={}", eventId);
            return Optional.empty();
        }
        int claimed = repository.claim(
                eventId,
                properties.getMaxAttempts(),
                FollowNotificationOutboxStatus.PENDING,
                FollowNotificationOutboxStatus.IN_FLIGHT,
                leaseOwner,
                now.plus(properties.getLeaseDuration()),
                now
        );
        if (claimed != 1) {
            return Optional.empty();
        }

        return repository.findByEventIdAndStatusAndLeaseOwner(
                        eventId,
                        FollowNotificationOutboxStatus.IN_FLIGHT,
                        leaseOwner
                )
                .map(this::toClaim);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markPublished(FollowNotificationOutboxClaim claim) {
        return repository.markPublished(
                claim.eventId(),
                claim.leaseOwner(),
                FollowNotificationOutboxStatus.IN_FLIGHT,
                FollowNotificationOutboxStatus.PUBLISHED,
                timeProvider.now()
        ) == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public FailureDisposition markFailed(FollowNotificationOutboxClaim claim, String errorType) {
        Instant now = timeProvider.now();
        String safeErrorType = sanitizeErrorType(errorType);

        if (claim.attemptCount() >= properties.getMaxAttempts()) {
            int updated = repository.markDeadLetter(
                    claim.eventId(),
                    claim.leaseOwner(),
                    FollowNotificationOutboxStatus.IN_FLIGHT,
                    FollowNotificationOutboxStatus.DEAD_LETTER,
                    safeErrorType,
                    now
            );
            return updated == 1 ? FailureDisposition.DEAD_LETTER : FailureDisposition.LEASE_LOST;
        }

        Instant nextAttemptAt = now.plus(backoffForAttempt(claim.attemptCount()));
        int updated = repository.reschedule(
                claim.eventId(),
                claim.leaseOwner(),
                FollowNotificationOutboxStatus.IN_FLIGHT,
                FollowNotificationOutboxStatus.PENDING,
                nextAttemptAt,
                safeErrorType,
                now
        );
        return updated == 1 ? FailureDisposition.RETRY_SCHEDULED : FailureDisposition.LEASE_LOST;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int cleanupPublished() {
        Instant cutoff = timeProvider.now().minus(properties.getPublishedRetention());
        return repository.deletePublishedBefore(FollowNotificationOutboxStatus.PUBLISHED, cutoff);
    }

    private FollowNotificationOutboxClaim toClaim(FollowNotificationOutbox event) {
        return new FollowNotificationOutboxClaim(event.getEventId(), event.getOccurrenceId(),
                event.getFollowerId(), event.getRecipientId(), event.getBandId(), event.getNotificationType(),
                event.getOccurredAt(), event.getAttemptCount(), event.getLeaseOwner());
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

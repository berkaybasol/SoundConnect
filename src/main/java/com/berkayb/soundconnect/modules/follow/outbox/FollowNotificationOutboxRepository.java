package com.berkayb.soundconnect.modules.follow.outbox;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FollowNotificationOutboxRepository
        extends JpaRepository<FollowNotificationOutbox, UUID> {
    /** Immutable occurrence/recipient intent; duplicate enqueue never resets retry or lease state. */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            insert into tbl_follow_notification_outbox
              (event_id, occurrence_id, follower_id, recipient_id, band_id, notification_type,
               occurred_at, status, attempt_count, next_attempt_at, created_at, updated_at)
            values (:eventId, :occurrenceId, :followerId, :recipientId, :bandId, :type,
                    :occurredAt, 'PENDING', 0, :now, :now, :now)
            on conflict (event_id) do nothing
            """, nativeQuery = true)
    int insertPending(@Param("eventId") UUID eventId, @Param("occurrenceId") UUID occurrenceId,
                      @Param("followerId") UUID followerId, @Param("recipientId") UUID recipientId,
                      @Param("bandId") UUID bandId, @Param("type") String type,
                      @Param("occurredAt") Instant occurredAt, @Param("now") Instant now);

    /** Crashes and expired leases also consume the durable attempt budget. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update FollowNotificationOutbox event
               set event.status = :deadLetter, event.leaseOwner = null, event.leaseUntil = null,
                   event.lastErrorType = 'AttemptBudgetExhausted', event.updatedAt = :now
             where event.eventId = :eventId and event.attemptCount >= :maxAttempts
               and ((event.status = :pending and event.nextAttemptAt <= :now)
                 or (event.status = :inFlight and (event.leaseUntil is null or event.leaseUntil <= :now)))
            """)
    int deadLetterExhausted(@Param("eventId") UUID eventId, @Param("maxAttempts") int maxAttempts,
                           @Param("pending") FollowNotificationOutboxStatus pending,
                           @Param("inFlight") FollowNotificationOutboxStatus inFlight,
                           @Param("deadLetter") FollowNotificationOutboxStatus deadLetter,
                           @Param("now") Instant now);

    @Query("""
            select event.eventId
            from FollowNotificationOutbox event
            where (event.status = :pending and event.nextAttemptAt <= :now)
               or (event.status = :inFlight and (event.leaseUntil is null or event.leaseUntil <= :now))
            order by event.nextAttemptAt asc, event.createdAt asc, event.eventId asc
            """)
    List<UUID> findDispatchCandidates(
            @Param("pending") FollowNotificationOutboxStatus pending,
            @Param("inFlight") FollowNotificationOutboxStatus inFlight,
            @Param("now") Instant now,
            Pageable pageable
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update FollowNotificationOutbox event
               set event.status = :inFlight,
                   event.leaseOwner = :leaseOwner,
                   event.leaseUntil = :leaseUntil,
                   event.attemptCount = event.attemptCount + 1,
                   event.updatedAt = :now
             where event.eventId = :eventId
               and event.attemptCount < :maxAttempts
               and ((event.status = :pending and event.nextAttemptAt <= :now)
                 or (event.status = :inFlight and (event.leaseUntil is null or event.leaseUntil <= :now)))
            """)
    int claim(
            @Param("eventId") UUID eventId,
            @Param("maxAttempts") int maxAttempts,
            @Param("pending") FollowNotificationOutboxStatus pending,
            @Param("inFlight") FollowNotificationOutboxStatus inFlight,
            @Param("leaseOwner") String leaseOwner,
            @Param("leaseUntil") Instant leaseUntil,
            @Param("now") Instant now
    );

    Optional<FollowNotificationOutbox> findByEventIdAndStatusAndLeaseOwner(
            UUID eventId,
            FollowNotificationOutboxStatus status,
            String leaseOwner
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update FollowNotificationOutbox event
               set event.status = :published,
                   event.publishedAt = :now,
                   event.leaseOwner = null,
                   event.leaseUntil = null,
                   event.lastErrorType = null,
                   event.updatedAt = :now
             where event.eventId = :eventId
               and event.status = :inFlight
               and event.leaseOwner = :leaseOwner
               and event.leaseUntil > :now
            """)
    int markPublished(
            @Param("eventId") UUID eventId,
            @Param("leaseOwner") String leaseOwner,
            @Param("inFlight") FollowNotificationOutboxStatus inFlight,
            @Param("published") FollowNotificationOutboxStatus published,
            @Param("now") Instant now
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update FollowNotificationOutbox event
               set event.status = :pending,
                   event.nextAttemptAt = :nextAttemptAt,
                   event.leaseOwner = null,
                   event.leaseUntil = null,
                   event.lastErrorType = :errorType,
                   event.updatedAt = :now
             where event.eventId = :eventId
               and event.status = :inFlight
               and event.leaseOwner = :leaseOwner
               and event.leaseUntil > :now
            """)
    int reschedule(
            @Param("eventId") UUID eventId,
            @Param("leaseOwner") String leaseOwner,
            @Param("inFlight") FollowNotificationOutboxStatus inFlight,
            @Param("pending") FollowNotificationOutboxStatus pending,
            @Param("nextAttemptAt") Instant nextAttemptAt,
            @Param("errorType") String errorType,
            @Param("now") Instant now
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update FollowNotificationOutbox event
               set event.status = :deadLetter,
                   event.leaseOwner = null,
                   event.leaseUntil = null,
                   event.lastErrorType = :errorType,
                   event.updatedAt = :now
             where event.eventId = :eventId
               and event.status = :inFlight
               and event.leaseOwner = :leaseOwner
               and event.leaseUntil > :now
            """)
    int markDeadLetter(
            @Param("eventId") UUID eventId,
            @Param("leaseOwner") String leaseOwner,
            @Param("inFlight") FollowNotificationOutboxStatus inFlight,
            @Param("deadLetter") FollowNotificationOutboxStatus deadLetter,
            @Param("errorType") String errorType,
            @Param("now") Instant now
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            delete from FollowNotificationOutbox event
             where event.status = :published
               and event.publishedAt < :cutoff
            """)
    int deletePublishedBefore(
            @Param("published") FollowNotificationOutboxStatus published,
            @Param("cutoff") Instant cutoff
    );

    long countByStatus(FollowNotificationOutboxStatus status);

    @Query("select min(event.createdAt) from FollowNotificationOutbox event where event.status in :statuses")
    Optional<Instant> findOldestCreatedAtByStatusIn(
            @Param("statuses") Collection<FollowNotificationOutboxStatus> statuses
    );
}

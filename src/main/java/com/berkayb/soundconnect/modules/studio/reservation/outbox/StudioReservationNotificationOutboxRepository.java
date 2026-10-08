package com.berkayb.soundconnect.modules.studio.reservation.outbox;

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

public interface StudioReservationNotificationOutboxRepository
        extends JpaRepository<StudioReservationNotificationOutbox, UUID> {
    /** Never merge a replay over PUBLISHED, retry or lease state. */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            insert into tbl_studio_reservation_notification_outbox
                (event_id, recipient_id, notification_type, title, message, payload, email_force,
                 occurred_at, status, attempt_count, next_attempt_at, created_at, updated_at)
            select :eventId, :recipientId, :type, :title, :message, cast(:payloadJson as jsonb), false,
                   :occurredAt, 'PENDING', 0, :now, :now, :now
             where exists (select 1 from tbl_user recipient where recipient.id = :recipientId and recipient.erased_at is null)
               and (cast(:payloadJson as jsonb)->>'requesterId' is null or exists (
                   select 1 from tbl_user requester
                    where requester.id = cast(cast(:payloadJson as jsonb)->>'requesterId' as uuid)
                      and requester.erased_at is null))
            on conflict (event_id) do nothing
            """, nativeQuery = true)
    int insertPending(@Param("eventId") UUID eventId, @Param("recipientId") UUID recipientId,
                      @Param("type") String type, @Param("title") String title,
                      @Param("message") String message, @Param("payloadJson") String payloadJson,
                      @Param("occurredAt") Instant occurredAt, @Param("now") Instant now);

    /** Crashes and expired leases also consume the durable attempt budget. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update StudioReservationNotificationOutbox event
               set event.status = :deadLetter, event.leaseOwner = null, event.leaseUntil = null,
                   event.lastErrorType = 'AttemptBudgetExhausted', event.updatedAt = :now
             where event.eventId = :eventId and event.attemptCount >= :maxAttempts
               and ((event.status = :pending and event.nextAttemptAt <= :now)
                 or (event.status = :inFlight and (event.leaseUntil is null or event.leaseUntil <= :now)))
            """)
    int deadLetterExhausted(@Param("eventId") UUID eventId, @Param("maxAttempts") int maxAttempts,
                           @Param("pending") StudioReservationNotificationOutboxStatus pending,
                           @Param("inFlight") StudioReservationNotificationOutboxStatus inFlight,
                           @Param("deadLetter") StudioReservationNotificationOutboxStatus deadLetter,
                           @Param("now") Instant now);

    @Query("""
            select event.eventId
            from StudioReservationNotificationOutbox event
            where (event.status = :pending and event.nextAttemptAt <= :now)
               or (event.status = :inFlight and (event.leaseUntil is null or event.leaseUntil <= :now))
            order by event.nextAttemptAt asc, event.createdAt asc, event.eventId asc
            """)
    List<UUID> findDispatchCandidates(
            @Param("pending") StudioReservationNotificationOutboxStatus pending,
            @Param("inFlight") StudioReservationNotificationOutboxStatus inFlight,
            @Param("now") Instant now,
            Pageable pageable
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update StudioReservationNotificationOutbox event
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
            @Param("pending") StudioReservationNotificationOutboxStatus pending,
            @Param("inFlight") StudioReservationNotificationOutboxStatus inFlight,
            @Param("leaseOwner") String leaseOwner,
            @Param("leaseUntil") Instant leaseUntil,
            @Param("now") Instant now
    );

    Optional<StudioReservationNotificationOutbox> findByEventIdAndStatusAndLeaseOwner(
            UUID eventId,
            StudioReservationNotificationOutboxStatus status,
            String leaseOwner
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update StudioReservationNotificationOutbox event
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
            @Param("inFlight") StudioReservationNotificationOutboxStatus inFlight,
            @Param("published") StudioReservationNotificationOutboxStatus published,
            @Param("now") Instant now
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update StudioReservationNotificationOutbox event
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
            @Param("inFlight") StudioReservationNotificationOutboxStatus inFlight,
            @Param("pending") StudioReservationNotificationOutboxStatus pending,
            @Param("nextAttemptAt") Instant nextAttemptAt,
            @Param("errorType") String errorType,
            @Param("now") Instant now
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update StudioReservationNotificationOutbox event
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
            @Param("inFlight") StudioReservationNotificationOutboxStatus inFlight,
            @Param("deadLetter") StudioReservationNotificationOutboxStatus deadLetter,
            @Param("errorType") String errorType,
            @Param("now") Instant now
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            delete from StudioReservationNotificationOutbox event
             where event.status = :published
               and event.publishedAt < :cutoff
            """)
    int deletePublishedBefore(
            @Param("published") StudioReservationNotificationOutboxStatus published,
            @Param("cutoff") Instant cutoff
    );

    long countByStatus(StudioReservationNotificationOutboxStatus status);

    @Query("select min(event.createdAt) from StudioReservationNotificationOutbox event where event.status in :statuses")
    Optional<Instant> findOldestCreatedAtByStatusIn(
            @Param("statuses") Collection<StudioReservationNotificationOutboxStatus> statuses
    );
}

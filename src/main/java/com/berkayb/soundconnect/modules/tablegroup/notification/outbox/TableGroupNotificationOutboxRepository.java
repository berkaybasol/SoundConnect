package com.berkayb.soundconnect.modules.tablegroup.notification.outbox;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.*;

public interface TableGroupNotificationOutboxRepository extends JpaRepository<TableGroupNotificationOutbox, UUID> {

	@Query("""
			select event.eventId from TableGroupNotificationOutbox event
			where (event.status = :pending and event.nextAttemptAt <= :now)
			   or (event.status = :inFlight and (event.leaseUntil is null or event.leaseUntil <= :now))
			order by event.nextAttemptAt asc, event.createdAt asc, event.eventId asc
			""")
	List<UUID> findDispatchCandidates(
			@Param("pending") TableGroupNotificationOutboxStatus pending,
			@Param("inFlight") TableGroupNotificationOutboxStatus inFlight,
			@Param("now") Instant now,
			Pageable pageable
	);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update TableGroupNotificationOutbox event
			set event.status = :inFlight, event.leaseOwner = :leaseOwner,
			    event.leaseUntil = :leaseUntil, event.attemptCount = event.attemptCount + 1,
			    event.updatedAt = :now
			where event.eventId = :eventId
			  and ((event.status = :pending and event.nextAttemptAt <= :now)
			    or (event.status = :inFlight and (event.leaseUntil is null or event.leaseUntil <= :now)))
			""")
	int claim(
			@Param("eventId") UUID eventId,
			@Param("pending") TableGroupNotificationOutboxStatus pending,
			@Param("inFlight") TableGroupNotificationOutboxStatus inFlight,
			@Param("leaseOwner") String leaseOwner,
			@Param("leaseUntil") Instant leaseUntil,
			@Param("now") Instant now
	);

	Optional<TableGroupNotificationOutbox> findByEventIdAndStatusAndLeaseOwner(
			UUID eventId, TableGroupNotificationOutboxStatus status, String leaseOwner);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update TableGroupNotificationOutbox event
			set event.status = :published, event.publishedAt = :now,
			    event.leaseOwner = null, event.leaseUntil = null,
			    event.lastErrorType = null, event.updatedAt = :now
			where event.eventId = :eventId and event.status = :inFlight and event.leaseOwner = :leaseOwner
			""")
	int markPublished(
			@Param("eventId") UUID eventId,
			@Param("leaseOwner") String leaseOwner,
			@Param("inFlight") TableGroupNotificationOutboxStatus inFlight,
			@Param("published") TableGroupNotificationOutboxStatus published,
			@Param("now") Instant now
	);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update TableGroupNotificationOutbox event
			set event.status = :pending, event.nextAttemptAt = :nextAttemptAt,
			    event.leaseOwner = null, event.leaseUntil = null,
			    event.lastErrorType = :errorType, event.updatedAt = :now
			where event.eventId = :eventId and event.status = :inFlight and event.leaseOwner = :leaseOwner
			""")
	int reschedule(
			@Param("eventId") UUID eventId,
			@Param("leaseOwner") String leaseOwner,
			@Param("inFlight") TableGroupNotificationOutboxStatus inFlight,
			@Param("pending") TableGroupNotificationOutboxStatus pending,
			@Param("nextAttemptAt") Instant nextAttemptAt,
			@Param("errorType") String errorType,
			@Param("now") Instant now
	);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update TableGroupNotificationOutbox event
			set event.status = :deadLetter, event.leaseOwner = null,
			    event.leaseUntil = null, event.lastErrorType = :errorType, event.updatedAt = :now
			where event.eventId = :eventId and event.status = :inFlight and event.leaseOwner = :leaseOwner
			""")
	int markDeadLetter(
			@Param("eventId") UUID eventId,
			@Param("leaseOwner") String leaseOwner,
			@Param("inFlight") TableGroupNotificationOutboxStatus inFlight,
			@Param("deadLetter") TableGroupNotificationOutboxStatus deadLetter,
			@Param("errorType") String errorType,
			@Param("now") Instant now
	);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("delete from TableGroupNotificationOutbox event where event.status = :published and event.publishedAt < :cutoff")
	int deletePublishedBefore(
			@Param("published") TableGroupNotificationOutboxStatus published,
			@Param("cutoff") Instant cutoff
	);

	long countByStatus(TableGroupNotificationOutboxStatus status);

	@Query("select min(event.createdAt) from TableGroupNotificationOutbox event where event.status in :statuses")
	Optional<Instant> findOldestCreatedAtByStatusIn(
			@Param("statuses") Collection<TableGroupNotificationOutboxStatus> statuses
	);
}

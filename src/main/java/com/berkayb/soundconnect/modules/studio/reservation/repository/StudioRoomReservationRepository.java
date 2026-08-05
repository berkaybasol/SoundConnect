package com.berkayb.soundconnect.modules.studio.reservation.repository;

import com.berkayb.soundconnect.modules.studio.reservation.entity.StudioRoomReservation;
import com.berkayb.soundconnect.modules.studio.reservation.enums.StudioReservationStatus;
import com.berkayb.soundconnect.modules.studio.reservation.repository.projection.StudioDailyReservationCountProjection;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StudioRoomReservationRepository extends JpaRepository<StudioRoomReservation, UUID> {

    @Query("""
            select reservation.room.id as roomId, count(reservation.id) as reservationCount
            from StudioRoomReservation reservation
            where reservation.room.id in :roomIds
              and reservation.status in :statuses
              and reservation.startsAt < :endsAt
              and reservation.endsAt > :startsAt
			  and (reservation.status <> :pendingStatus
			       or reservation.startsAt > :now)
            group by reservation.room.id
            """)
    List<StudioDailyReservationCountProjection> countDailyByRooms(
            @Param("roomIds") Collection<UUID> roomIds,
            @Param("statuses") Collection<StudioReservationStatus> statuses,
			@Param("pendingStatus") StudioReservationStatus pendingStatus,
            @Param("startsAt") Instant startsAt,
			@Param("endsAt") Instant endsAt,
			@Param("now") Instant now
    );

    Optional<StudioRoomReservation> findByRequesterIdAndClientRequestId(UUID requesterId, UUID clientRequestId);

    @Query("""
            select reservation from StudioRoomReservation reservation
            where reservation.id = :reservationId
              and reservation.requester.id = :requesterId
            """)
    Optional<StudioRoomReservation> findByIdAndRequesterId(
            @Param("reservationId") UUID reservationId,
            @Param("requesterId") UUID requesterId
    );

    @Modifying(flushAutomatically = true)
    @Query("""
            update StudioRoomReservation reservation
               set reservation.status = :expiredStatus,
                   reservation.updatedAt = CURRENT_TIMESTAMP,
                   reservation.version = reservation.version + 1
             where reservation.requester.id = :requesterId
               and reservation.room.id = :roomId
               and reservation.status = :pendingStatus
               and reservation.startsAt <= :now
            """)
    int expireStartedPendingRequests(
            @Param("requesterId") UUID requesterId,
            @Param("roomId") UUID roomId,
            @Param("pendingStatus") StudioReservationStatus pendingStatus,
            @Param("expiredStatus") StudioReservationStatus expiredStatus,
            @Param("now") Instant now
    );

    @Query("""
            select (count(reservation) > 0) from StudioRoomReservation reservation
            where reservation.requester.id = :requesterId
              and reservation.room.id = :roomId
              and reservation.status in :statuses
              and reservation.startsAt < :endsAt
              and reservation.endsAt > :startsAt
              and (reservation.status <> :pendingStatus or reservation.startsAt > :now)
            """)
    boolean existsActiveRequesterOverlap(
            @Param("requesterId") UUID requesterId,
            @Param("roomId") UUID roomId,
            @Param("statuses") Collection<StudioReservationStatus> statuses,
            @Param("pendingStatus") StudioReservationStatus pendingStatus,
            @Param("startsAt") Instant startsAt,
            @Param("endsAt") Instant endsAt,
            @Param("now") Instant now
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select reservation from StudioRoomReservation reservation
            where reservation.id = :reservationId
              and reservation.room.id = :roomId
            """)
    Optional<StudioRoomReservation> findByIdAndRoomIdForUpdate(
            @Param("reservationId") UUID reservationId,
            @Param("roomId") UUID roomId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select reservation from StudioRoomReservation reservation
            where reservation.id = :reservationId
              and reservation.requester.id = :requesterId
            """)
    Optional<StudioRoomReservation> findByIdAndRequesterIdForUpdate(
            @Param("reservationId") UUID reservationId,
            @Param("requesterId") UUID requesterId
    );

    @Query(
            value = """
                    select reservation from StudioRoomReservation reservation
                    join fetch reservation.room room
                    join fetch room.studioProfile
                    where reservation.requester.id = :requesterId
                    """,
            countQuery = """
                    select count(reservation) from StudioRoomReservation reservation
                    where reservation.requester.id = :requesterId
                    """
    )
    Page<StudioRoomReservation> findByRequesterId(
            @Param("requesterId") UUID requesterId,
            Pageable pageable
    );

    @Query(
            value = """
                    select reservation from StudioRoomReservation reservation
                    join fetch reservation.room room
                    join fetch room.studioProfile
                    where reservation.requester.id = :requesterId
                      and room.id = :roomId
                      and reservation.startsAt < :endsAt
                      and reservation.endsAt > :startsAt
                    order by reservation.startsAt asc, reservation.id asc
                    """,
            countQuery = """
                    select count(reservation) from StudioRoomReservation reservation
                    where reservation.requester.id = :requesterId
                      and reservation.room.id = :roomId
                      and reservation.startsAt < :endsAt
                      and reservation.endsAt > :startsAt
                    """
    )
    Page<StudioRoomReservation> findCustomerRoomReservationsInRange(
            @Param("requesterId") UUID requesterId,
            @Param("roomId") UUID roomId,
            @Param("startsAt") Instant startsAt,
            @Param("endsAt") Instant endsAt,
            Pageable pageable
    );

    @Query("""
            select count(reservation) from StudioRoomReservation reservation
            where reservation.room.id = :roomId
              and reservation.status = :pendingStatus
              and reservation.startsAt > :now
              and reservation.startsAt < :endsAt
              and reservation.endsAt > :startsAt
            """)
    long countPendingOverlap(
            @Param("roomId") UUID roomId,
            @Param("pendingStatus") StudioReservationStatus pendingStatus,
            @Param("now") Instant now,
            @Param("startsAt") Instant startsAt,
            @Param("endsAt") Instant endsAt
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select reservation from StudioRoomReservation reservation
            where reservation.room.id = :roomId
              and reservation.id <> :excludedReservationId
              and reservation.status = :pendingStatus
              and reservation.startsAt > :now
              and reservation.startsAt < :endsAt
              and reservation.endsAt > :startsAt
            order by reservation.createdAt asc, reservation.id asc
            """)
    List<StudioRoomReservation> findOverlappingPendingForUpdate(
            @Param("roomId") UUID roomId,
            @Param("excludedReservationId") UUID excludedReservationId,
            @Param("pendingStatus") StudioReservationStatus pendingStatus,
            @Param("now") Instant now,
            @Param("startsAt") Instant startsAt,
            @Param("endsAt") Instant endsAt
    );

    @Query(
            value = """
                    select reservation from StudioRoomReservation reservation
                    join fetch reservation.requester
                    where reservation.room.id = :roomId
                      and reservation.startsAt < :endsAt
                      and reservation.endsAt > :startsAt
                    order by reservation.startsAt asc, reservation.createdAt asc, reservation.id asc
                    """,
            countQuery = """
                    select count(reservation) from StudioRoomReservation reservation
                    where reservation.room.id = :roomId
                      and reservation.startsAt < :endsAt
                      and reservation.endsAt > :startsAt
                    """
    )
    Page<StudioRoomReservation> findRoomReservationsInRange(
            @Param("roomId") UUID roomId,
            @Param("startsAt") Instant startsAt,
            @Param("endsAt") Instant endsAt,
            Pageable pageable
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select reservation from StudioRoomReservation reservation
            where reservation.room.id = :roomId
              and reservation.status in :statuses
              and reservation.startsAt > :now
            order by reservation.startsAt asc, reservation.id asc
            """)
    List<StudioRoomReservation> findFutureByRoomAndStatusesForUpdate(
            @Param("roomId") UUID roomId,
            @Param("statuses") Collection<StudioReservationStatus> statuses,
            @Param("now") Instant now
    );
}

package com.berkayb.soundconnect.modules.studio.reservation.repository;

import com.berkayb.soundconnect.modules.studio.reservation.entity.StudioRoomOccupancy;
import com.berkayb.soundconnect.modules.studio.reservation.repository.projection.StudioDailyOccupancyHoursProjection;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface StudioRoomOccupancyRepository extends JpaRepository<StudioRoomOccupancy, UUID> {

    @Query(value = """
            select occupancy.room_id as "roomId",
                   cast(sum(
                       extract(epoch from (
                           least(occupancy.ends_at, :endsAt)
                           - greatest(occupancy.starts_at, :startsAt)
                       )) / 3600
                   ) as integer) as "occupiedHours"
            from tbl_studio_room_occupancy occupancy
            where occupancy.room_id in (:roomIds)
              and occupancy.active = true
              and occupancy.starts_at < :endsAt
              and occupancy.ends_at > :startsAt
            group by occupancy.room_id
            """, nativeQuery = true)
    List<StudioDailyOccupancyHoursProjection> sumDailyOccupiedHoursByRooms(
            @Param("roomIds") Collection<UUID> roomIds,
            @Param("startsAt") Instant startsAt,
            @Param("endsAt") Instant endsAt
    );

    Optional<StudioRoomOccupancy> findByCreatedByAndClientRequestId(UUID createdBy, UUID clientRequestId);

    @Query("""
            select (count(occupancy) > 0) from StudioRoomOccupancy occupancy
            where occupancy.room.id = :roomId
              and occupancy.active = true
              and occupancy.startsAt < :endsAt
              and occupancy.endsAt > :startsAt
            """)
    boolean existsActiveOverlap(
            @Param("roomId") UUID roomId,
            @Param("startsAt") Instant startsAt,
            @Param("endsAt") Instant endsAt
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select occupancy from StudioRoomOccupancy occupancy
            where occupancy.reservation.id = :reservationId and occupancy.active = true
            """)
    Optional<StudioRoomOccupancy> findActiveByReservationIdForUpdate(
            @Param("reservationId") UUID reservationId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select occupancy from StudioRoomOccupancy occupancy where occupancy.id = :occupancyId")
    Optional<StudioRoomOccupancy> findByIdForUpdate(@Param("occupancyId") UUID occupancyId);

    @Query("""
            select occupancy from StudioRoomOccupancy occupancy
            where occupancy.room.id = :roomId
              and occupancy.active = true
              and occupancy.startsAt < :endsAt
              and occupancy.endsAt > :startsAt
            order by occupancy.startsAt asc, occupancy.id asc
            """)
    List<StudioRoomOccupancy> findActiveByRoomInRange(
            @Param("roomId") UUID roomId,
            @Param("startsAt") Instant startsAt,
            @Param("endsAt") Instant endsAt
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select occupancy from StudioRoomOccupancy occupancy
            where occupancy.room.id = :roomId
              and occupancy.active = true
              and occupancy.endsAt > :now
            order by occupancy.startsAt asc, occupancy.id asc
            """)
    List<StudioRoomOccupancy> findFutureActiveByRoomForUpdate(
            @Param("roomId") UUID roomId,
            @Param("now") Instant now
    );
}

package com.berkayb.soundconnect.modules.studio.room.repository;

import com.berkayb.soundconnect.modules.studio.room.entity.StudioRoom;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StudioRoomRepository extends JpaRepository<StudioRoom, UUID> {

    boolean existsByStudioProfileId(UUID studioProfileId);

    Optional<StudioRoom> findByStudioProfileIdAndClientRequestId(UUID studioProfileId, UUID clientRequestId);

    @Query("""
            select room.slotIndex from StudioRoom room
            where room.studioProfile.id = :studioProfileId and room.archivedAt is null
            order by room.slotIndex asc
            """)
    List<Integer> findActiveSlotIndexes(@Param("studioProfileId") UUID studioProfileId);

    Page<StudioRoom> findByStudioProfileIdAndArchivedAtIsNull(UUID studioProfileId, Pageable pageable);

    Optional<StudioRoom> findByIdAndArchivedAtIsNull(UUID roomId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select room from StudioRoom room where room.id = :roomId")
    Optional<StudioRoom> findByIdForUpdate(@Param("roomId") UUID roomId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select room from StudioRoom room where room.id = :roomId and room.archivedAt is null")
    Optional<StudioRoom> findActiveByIdForUpdate(@Param("roomId") UUID roomId);
}

package com.berkayb.soundconnect.modules.studio.equipment.repository;

import com.berkayb.soundconnect.modules.studio.equipment.entity.StudioEquipmentDay;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Collection;
import java.util.UUID;

public interface StudioEquipmentDayRepository extends JpaRepository<StudioEquipmentDay, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select d from StudioEquipmentDay d
            where d.equipment.id = :equipmentId and d.localDate between :startDate and :endDate
            order by d.localDate asc
            """)
    List<StudioEquipmentDay> findRangeForUpdate(
            @Param("equipmentId") UUID equipmentId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate
    );

    @Query("""
            select d from StudioEquipmentDay d
            where d.equipment.id = :equipmentId and d.localDate between :startDate and :endDate
            order by d.localDate asc
            """)
    List<StudioEquipmentDay> findRange(
            @Param("equipmentId") UUID equipmentId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate
    );

    @Query("""
            select d from StudioEquipmentDay d
            where d.equipment.id in :equipmentIds and d.localDate = :availabilityDate
            """)
    List<StudioEquipmentDay> findForEquipmentIdsOnDate(
            @Param("equipmentIds") Collection<UUID> equipmentIds,
            @Param("availabilityDate") LocalDate availabilityDate
    );

    @Query("""
            select coalesce(max(d.busyQuantity + d.maintenanceQuantity), 0)
            from StudioEquipmentDay d
            where d.equipment.id = :equipmentId and d.localDate >= :minimumDate
            """)
    int findMaximumAllocatedQuantityFromDate(
            @Param("equipmentId") UUID equipmentId,
            @Param("minimumDate") LocalDate minimumDate
    );

    /**
     * Deletes materialized calendar state that can no longer be queried or changed.
     * Immutable availability command rows remain the audit source of truth.
     */
    @Modifying
    @Query("""
            delete from StudioEquipmentDay d
            where d.equipment.id = :equipmentId and d.localDate < :minimumDate
            """)
    int deleteBeforeDate(
            @Param("equipmentId") UUID equipmentId,
            @Param("minimumDate") LocalDate minimumDate
    );
}

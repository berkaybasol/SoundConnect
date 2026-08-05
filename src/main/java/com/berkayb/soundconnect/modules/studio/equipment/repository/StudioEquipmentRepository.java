package com.berkayb.soundconnect.modules.studio.equipment.repository;

import com.berkayb.soundconnect.modules.studio.equipment.entity.StudioEquipment;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.time.LocalDate;
import java.util.UUID;

public interface StudioEquipmentRepository extends JpaRepository<StudioEquipment, UUID> {

    interface InventorySummaryProjection {
        long getTotalQuantity();
        long getAvailableQuantity();
        long getBusyQuantity();
        long getMaintenanceQuantity();
    }

    @Query("""
            select
                coalesce(sum(e.totalQuantity), 0) as totalQuantity,
                coalesce(sum(e.totalQuantity - coalesce(day.busyQuantity, 0)
                             - coalesce(day.maintenanceQuantity, 0)), 0) as availableQuantity,
                coalesce(sum(coalesce(day.busyQuantity, 0)), 0) as busyQuantity,
                coalesce(sum(coalesce(day.maintenanceQuantity, 0)), 0) as maintenanceQuantity
            from StudioEquipment e
            left join StudioEquipmentDay day
              on day.equipment = e and day.localDate = :availabilityDate
            where e.studioProfile.id = :studioProfileId
              and e.archivedAt is null
            """)
    InventorySummaryProjection summarizeActiveInventory(
            @Param("studioProfileId") UUID studioProfileId,
            @Param("availabilityDate") LocalDate availabilityDate
    );

    @Query("""
            select equipment from StudioEquipment equipment
            where equipment.id = :equipmentId
              and equipment.studioProfile.user.id = :ownerUserId
              and equipment.archivedAt is null
            """)
    Optional<StudioEquipment> findOwnedActiveById(
            @Param("equipmentId") UUID equipmentId,
            @Param("ownerUserId") UUID ownerUserId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select e from StudioEquipment e
            where e.id = :equipmentId
              and e.studioProfile.user.id = :ownerUserId
            """)
    Optional<StudioEquipment> findOwnedByIdForUpdate(
            @Param("equipmentId") UUID equipmentId,
            @Param("ownerUserId") UUID ownerUserId
    );

    @EntityGraph(attributePaths = {"leafCategory", "leafCategory.parent"})
    @Query(value = """
            select e from StudioEquipment e
            join e.leafCategory category
            join category.parent parent
            where e.studioProfile.id = :studioProfileId
              and e.archivedAt is null
              and (lower(coalesce(e.name, '')) like :queryPattern escape '!'
                   or lower(coalesce(e.brand, '')) like :queryPattern escape '!'
                   or lower(coalesce(e.model, '')) like :queryPattern escape '!'
                   or lower(coalesce(category.name, '')) like :queryPattern escape '!'
                   or lower(coalesce(parent.name, '')) like :queryPattern escape '!')
              and (:categoryId is null
                   or category.id = :categoryId
                   or parent.id = :categoryId)
              and (
                   :availabilityBucket = 'ALL'
                   or (
                       :availabilityBucket = 'AVAILABLE'
                       and (
                           not exists (
                               select 1 from StudioEquipmentDay availableDay
                               where availableDay.equipment = e
                                 and availableDay.localDate = :availabilityDate
                           )
                           or exists (
                               select 1 from StudioEquipmentDay availableDay
                               where availableDay.equipment = e
                                 and availableDay.localDate = :availabilityDate
                                 and e.totalQuantity > availableDay.busyQuantity + availableDay.maintenanceQuantity
                           )
                       )
                   )
                   or (
                       :availabilityBucket = 'BUSY'
                       and exists (
                           select 1 from StudioEquipmentDay busyDay
                           where busyDay.equipment = e
                             and busyDay.localDate = :availabilityDate
                             and busyDay.busyQuantity > 0
                       )
                   )
                   or (
                       :availabilityBucket = 'MAINTENANCE'
                       and exists (
                           select 1 from StudioEquipmentDay maintenanceDay
                           where maintenanceDay.equipment = e
                             and maintenanceDay.localDate = :availabilityDate
                             and maintenanceDay.maintenanceQuantity > 0
                       )
                   )
              )
            """, countQuery = """
            select count(e) from StudioEquipment e
            join e.leafCategory category
            join category.parent parent
            where e.studioProfile.id = :studioProfileId
              and e.archivedAt is null
              and (lower(coalesce(e.name, '')) like :queryPattern escape '!'
                   or lower(coalesce(e.brand, '')) like :queryPattern escape '!'
                   or lower(coalesce(e.model, '')) like :queryPattern escape '!'
                   or lower(coalesce(category.name, '')) like :queryPattern escape '!'
                   or lower(coalesce(parent.name, '')) like :queryPattern escape '!')
              and (:categoryId is null
                   or category.id = :categoryId
                   or parent.id = :categoryId)
              and (
                   :availabilityBucket = 'ALL'
                   or (
                       :availabilityBucket = 'AVAILABLE'
                       and (
                           not exists (
                               select 1 from StudioEquipmentDay availableDay
                               where availableDay.equipment = e
                                 and availableDay.localDate = :availabilityDate
                           )
                           or exists (
                               select 1 from StudioEquipmentDay availableDay
                               where availableDay.equipment = e
                                 and availableDay.localDate = :availabilityDate
                                 and e.totalQuantity > availableDay.busyQuantity + availableDay.maintenanceQuantity
                           )
                       )
                   )
                   or (
                       :availabilityBucket = 'BUSY'
                       and exists (
                           select 1 from StudioEquipmentDay busyDay
                           where busyDay.equipment = e
                             and busyDay.localDate = :availabilityDate
                             and busyDay.busyQuantity > 0
                       )
                   )
                   or (
                       :availabilityBucket = 'MAINTENANCE'
                       and exists (
                           select 1 from StudioEquipmentDay maintenanceDay
                           where maintenanceDay.equipment = e
                             and maintenanceDay.localDate = :availabilityDate
                             and maintenanceDay.maintenanceQuantity > 0
                       )
                   )
              )
            """)
    Page<StudioEquipment> findActiveByStudio(
            @Param("studioProfileId") UUID studioProfileId,
            @Param("queryPattern") String queryPattern,
            @Param("categoryId") UUID categoryId,
            @Param("availabilityBucket") String availabilityBucket,
            @Param("availabilityDate") LocalDate availabilityDate,
            Pageable pageable
    );

    @EntityGraph(attributePaths = {"leafCategory", "leafCategory.parent"})
    Optional<StudioEquipment> findByIdAndStudioProfileIdAndArchivedAtIsNull(UUID id, UUID studioProfileId);

    Optional<StudioEquipment> findByStudioProfileIdAndCreationClientRequestId(
            UUID studioProfileId,
            UUID creationClientRequestId
    );
}

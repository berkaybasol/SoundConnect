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

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from StudioEquipment e where e.id = :equipmentId")
    Optional<StudioEquipment> findByIdForUpdate(@Param("equipmentId") UUID equipmentId);

    @EntityGraph(attributePaths = {"leafCategory", "leafCategory.parent"})
    @Query(value = """
            select e from StudioEquipment e
            where e.studioProfile.id = :studioProfileId
              and e.archivedAt is null
              and (:query = ''
                   or e.name ilike concat('%', :query, '%')
                   or coalesce(e.brand, '') ilike concat('%', :query, '%')
                   or coalesce(e.model, '') ilike concat('%', :query, '%')
                   or e.leafCategory.name ilike concat('%', :query, '%')
                   or e.leafCategory.parent.name ilike concat('%', :query, '%'))
              and (:categoryId is null
                   or e.leafCategory.id = :categoryId
                   or e.leafCategory.parent.id = :categoryId)
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
            where e.studioProfile.id = :studioProfileId
              and e.archivedAt is null
              and (:query = ''
                   or e.name ilike concat('%', :query, '%')
                   or coalesce(e.brand, '') ilike concat('%', :query, '%')
                   or coalesce(e.model, '') ilike concat('%', :query, '%')
                   or e.leafCategory.name ilike concat('%', :query, '%')
                   or e.leafCategory.parent.name ilike concat('%', :query, '%'))
              and (:categoryId is null
                   or e.leafCategory.id = :categoryId
                   or e.leafCategory.parent.id = :categoryId)
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
            @Param("query") String query,
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

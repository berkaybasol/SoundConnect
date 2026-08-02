package com.berkayb.soundconnect.modules.studio.equipment.repository;

import com.berkayb.soundconnect.modules.studio.equipment.entity.StudioEquipmentAvailabilityCommand;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface StudioEquipmentAvailabilityCommandRepository
        extends JpaRepository<StudioEquipmentAvailabilityCommand, UUID> {

    Optional<StudioEquipmentAvailabilityCommand> findByEquipmentIdAndClientRequestId(
            UUID equipmentId,
            UUID clientRequestId
    );
}

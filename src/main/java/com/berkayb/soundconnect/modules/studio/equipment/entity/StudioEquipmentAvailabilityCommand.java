package com.berkayb.soundconnect.modules.studio.equipment.entity;

import com.berkayb.soundconnect.modules.studio.equipment.model.EquipmentAvailabilityBucket;
import com.berkayb.soundconnect.shared.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Check;

import java.time.LocalDate;
import java.util.UUID;

@Getter
@NoArgsConstructor
@Entity
@Table(
        name = "tbl_studio_equipment_availability_change",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_studio_equipment_availability_request",
                columnNames = {"equipment_id", "client_request_id"}
        ),
        indexes = @Index(name = "idx_studio_equipment_change_history", columnList = "equipment_id,created_at")
)
@Check(constraints = "start_date <= end_date and quantity between 1 and 999 and source_bucket <> target_bucket")
public class StudioEquipmentAvailabilityCommand extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "equipment_id", nullable = false, updatable = false)
    private StudioEquipment equipment;

    @Column(name = "actor_id", nullable = false, updatable = false, columnDefinition = "uuid")
    private UUID actingUserId;

    @Column(name = "client_request_id", nullable = false, updatable = false, columnDefinition = "uuid")
    private UUID clientRequestId;

    @Column(name = "start_date", nullable = false, updatable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false, updatable = false)
    private LocalDate endDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_bucket", nullable = false, updatable = false, length = 16)
    private EquipmentAvailabilityBucket sourceBucket;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_bucket", nullable = false, updatable = false, length = 16)
    private EquipmentAvailabilityBucket targetBucket;

    @Column(nullable = false, updatable = false)
    private int quantity;

    public static StudioEquipmentAvailabilityCommand create(
            StudioEquipment equipment,
            UUID actingUserId,
            UUID clientRequestId,
            LocalDate startDate,
            LocalDate endDate,
            EquipmentAvailabilityBucket sourceBucket,
            EquipmentAvailabilityBucket targetBucket,
            int quantity
    ) {
        StudioEquipmentAvailabilityCommand command = new StudioEquipmentAvailabilityCommand();
        command.equipment = equipment;
        command.actingUserId = actingUserId;
        command.clientRequestId = clientRequestId;
        command.startDate = startDate;
        command.endDate = endDate;
        command.sourceBucket = sourceBucket;
        command.targetBucket = targetBucket;
        command.quantity = quantity;
        return command;
    }

    public boolean matches(
            LocalDate requestedStartDate,
            LocalDate requestedEndDate,
            EquipmentAvailabilityBucket requestedSource,
            EquipmentAvailabilityBucket requestedTarget,
            int requestedQuantity
    ) {
        return startDate.equals(requestedStartDate)
                && endDate.equals(requestedEndDate)
                && sourceBucket == requestedSource
                && targetBucket == requestedTarget
                && quantity == requestedQuantity;
    }
}

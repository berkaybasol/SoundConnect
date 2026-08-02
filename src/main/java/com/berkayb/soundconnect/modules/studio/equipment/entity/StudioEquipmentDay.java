package com.berkayb.soundconnect.modules.studio.equipment.entity;

import com.berkayb.soundconnect.modules.studio.equipment.model.EquipmentAvailabilityBucket;
import com.berkayb.soundconnect.shared.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Check;

import java.time.LocalDate;

@Getter
@NoArgsConstructor
@Entity
@Table(
        name = "tbl_studio_equipment_day",
        uniqueConstraints = @UniqueConstraint(name = "uk_studio_equipment_day", columnNames = {"equipment_id", "availability_date"}),
        indexes = @Index(name = "idx_studio_equipment_day_range", columnList = "equipment_id,availability_date")
)
@Check(constraints = "busy_count >= 0 and maintenance_count >= 0 and busy_count + maintenance_count > 0")
public class StudioEquipmentDay extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "equipment_id", nullable = false, updatable = false)
    private StudioEquipment equipment;

    @Column(name = "availability_date", nullable = false, updatable = false)
    private LocalDate localDate;

    @Column(name = "busy_count", nullable = false)
    private int busyQuantity;

    @Column(name = "maintenance_count", nullable = false)
    private int maintenanceQuantity;

    @Version
    @Column(nullable = false)
    private long version;

    public StudioEquipmentDay(StudioEquipment equipment, LocalDate localDate) {
        this.equipment = equipment;
        this.localDate = localDate;
    }

    public int allocatedQuantity() {
        return busyQuantity + maintenanceQuantity;
    }

    public int availableQuantity(int totalQuantity) {
        return totalQuantity - allocatedQuantity();
    }

    public int quantityFor(EquipmentAvailabilityBucket bucket, int totalQuantity) {
        return switch (bucket) {
            case AVAILABLE -> availableQuantity(totalQuantity);
            case BUSY -> busyQuantity;
            case MAINTENANCE -> maintenanceQuantity;
        };
    }

    public void move(
            EquipmentAvailabilityBucket source,
            EquipmentAvailabilityBucket target,
            int quantity,
            int totalQuantity
    ) {
        if (quantityFor(source, totalQuantity) < quantity) {
            throw new IllegalArgumentException("Source bucket does not contain the requested quantity");
        }
        adjust(source, -quantity);
        adjust(target, quantity);
        if (busyQuantity < 0 || maintenanceQuantity < 0 || allocatedQuantity() > totalQuantity) {
            throw new IllegalStateException("Equipment allocation invariant violated");
        }
    }

    private void adjust(EquipmentAvailabilityBucket bucket, int delta) {
        switch (bucket) {
            case AVAILABLE -> {
                // AVAILABLE is derived and therefore never persisted directly.
            }
            case BUSY -> busyQuantity += delta;
            case MAINTENANCE -> maintenanceQuantity += delta;
        }
    }
}

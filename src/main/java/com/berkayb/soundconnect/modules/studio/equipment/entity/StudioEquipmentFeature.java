package com.berkayb.soundconnect.modules.studio.equipment.entity;

import com.berkayb.soundconnect.shared.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Check;

@Getter
@NoArgsConstructor
@Entity
@Table(
        name = "tbl_studio_equipment_feature",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_studio_equipment_feature_position", columnNames = {"equipment_id", "position"}),
                @UniqueConstraint(name = "uk_studio_equipment_feature_label", columnNames = {"equipment_id", "label"})
        },
        indexes = @Index(name = "idx_studio_equipment_feature_equipment", columnList = "equipment_id")
)
@Check(constraints = "position between 0 and 11")
public class StudioEquipmentFeature extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "equipment_id", nullable = false, updatable = false)
    private StudioEquipment equipment;

    @Column(nullable = false)
    private int position;

    @Column(nullable = false, length = 60)
    private String label;

    StudioEquipmentFeature(StudioEquipment equipment, int position, String label) {
        this.equipment = equipment;
        this.position = position;
        this.label = label;
    }
}

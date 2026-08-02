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

import java.util.UUID;

@Getter
@NoArgsConstructor
@Entity
@Table(
        name = "tbl_studio_equipment_photo",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_studio_equipment_photo_position", columnNames = {"equipment_id", "position"}),
                @UniqueConstraint(name = "uk_studio_equipment_photo_asset", columnNames = {"equipment_id", "media_asset_id"})
        },
        indexes = {
                @Index(name = "idx_studio_equipment_photo_equipment", columnList = "equipment_id"),
                @Index(name = "idx_studio_equipment_photo_media", columnList = "media_asset_id")
        }
)
@Check(constraints = "position between 0 and 4")
public class StudioEquipmentPhoto extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "equipment_id", nullable = false, updatable = false)
    private StudioEquipment equipment;

    @Column(nullable = false)
    private int position;

    @Column(name = "media_asset_id", nullable = false, columnDefinition = "uuid")
    private UUID mediaAssetId;

    StudioEquipmentPhoto(StudioEquipment equipment, int position, UUID mediaAssetId) {
        this.equipment = equipment;
        this.position = position;
        this.mediaAssetId = mediaAssetId;
    }
}

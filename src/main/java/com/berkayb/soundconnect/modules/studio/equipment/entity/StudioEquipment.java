package com.berkayb.soundconnect.modules.studio.equipment.entity;

import com.berkayb.soundconnect.modules.backline.catalog.entity.BacklineCategory;
import com.berkayb.soundconnect.modules.profile.StudioProfile.entity.StudioProfile;
import com.berkayb.soundconnect.shared.entity.BaseEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.Check;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Getter
@NoArgsConstructor
@Entity
@Table(
        name = "tbl_studio_equipment",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_studio_equipment_create_request",
                columnNames = {"studio_profile_id", "creation_client_request_id"}
        ),
        indexes = {
                @Index(name = "idx_studio_equipment_studio_active", columnList = "studio_profile_id,archived_at"),
                @Index(name = "idx_studio_equipment_category", columnList = "category_id")
        }
)
@Check(constraints = "total_quantity between 1 and 999")
public class StudioEquipment extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "studio_profile_id", nullable = false, updatable = false)
    private StudioProfile studioProfile;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private BacklineCategory leafCategory;

    @Column(name = "creation_client_request_id", nullable = false, updatable = false, columnDefinition = "uuid")
    private UUID creationClientRequestId;

    @Column(name = "creation_payload_hash", nullable = false, updatable = false, length = 64)
    private String creationPayloadHash;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 60)
    private String brand;

    @Column(length = 60)
    private String model;

    @Column(length = 300)
    private String description;

    @Column(name = "total_quantity", nullable = false)
    private int totalQuantity;

    @OneToMany(mappedBy = "equipment", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position ASC")
    @BatchSize(size = 50)
    private List<StudioEquipmentFeature> features = new ArrayList<>();

    @OneToMany(mappedBy = "equipment", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position ASC")
    @BatchSize(size = 50)
    private List<StudioEquipmentPhoto> photos = new ArrayList<>();

    @Column(name = "archived_at")
    private Instant archivedAt;

    @Version
    @Column(nullable = false)
    private long version;

    public static StudioEquipment create(
            StudioProfile studioProfile,
            BacklineCategory leafCategory,
            UUID creationClientRequestId,
            String creationPayloadHash,
            String name,
            String brand,
            String model,
            String description,
            int totalQuantity,
            List<String> features,
            List<UUID> photoIds
    ) {
        StudioEquipment equipment = new StudioEquipment();
        equipment.studioProfile = studioProfile;
        equipment.creationClientRequestId = creationClientRequestId;
        equipment.creationPayloadHash = creationPayloadHash;
        equipment.applyDetails(leafCategory, name, brand, model, description, totalQuantity, features, photoIds);
        return equipment;
    }

    public void update(
            BacklineCategory leafCategory,
            String name,
            String brand,
            String model,
            String description,
            int totalQuantity,
            List<String> features,
            List<UUID> photoIds
    ) {
        applyDetails(leafCategory, name, brand, model, description, totalQuantity, features, photoIds);
    }

    public void clearAttachmentsForReplacement() {
        features.clear();
        photos.clear();
    }

    public void archive(Instant archivedAt) {
        this.archivedAt = archivedAt;
        // Archived inventory must not retain a media reference indefinitely.
        // Textual features remain as lightweight audit context.
        photos.clear();
    }

    public boolean isArchived() {
        return archivedAt != null;
    }

    private void applyDetails(
            BacklineCategory leafCategory,
            String name,
            String brand,
            String model,
            String description,
            int totalQuantity,
            List<String> featureLabels,
            List<UUID> photoIds
    ) {
        this.leafCategory = leafCategory;
        this.name = name;
        this.brand = brand;
        this.model = model;
        this.description = description;
        this.totalQuantity = totalQuantity;

        features.clear();
        for (int position = 0; position < featureLabels.size(); position++) {
            features.add(new StudioEquipmentFeature(this, position, featureLabels.get(position)));
        }

        photos.clear();
        for (int position = 0; position < photoIds.size(); position++) {
            photos.add(new StudioEquipmentPhoto(this, position, photoIds.get(position)));
        }
    }
}

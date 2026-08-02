package com.berkayb.soundconnect.modules.studio.room.entity;

import com.berkayb.soundconnect.modules.profile.StudioProfile.entity.StudioProfile;
import com.berkayb.soundconnect.shared.entity.BaseEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.BatchSize;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(
        name = "tbl_studio_room",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_studio_room_profile_client_request",
                columnNames = {"studio_profile_id", "client_request_id"}
        )
)
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class StudioRoom extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "studio_profile_id", nullable = false)
    private StudioProfile studioProfile;

    @Column(name = "slot_index", nullable = false)
    private int slotIndex;

    @Column(name = "client_request_id", nullable = false, updatable = false, columnDefinition = "uuid")
    private java.util.UUID clientRequestId;

    @Column(name = "creation_payload_hash", nullable = false, updatable = false, length = 64)
    private String creationPayloadHash;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "short_description", length = 60)
    private String shortDescription;

    @Column(nullable = false)
    private int capacity;

    @Column(name = "minimum_capacity")
    private Integer minimumCapacity;

    @Column(name = "hourly_price_minor")
    private Long hourlyPriceMinor;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "reservation_approval_required", nullable = false)
    private boolean reservationApprovalRequired;

    @Column(name = "pending_reservation_approval_required")
    private Boolean pendingReservationApprovalRequired;

    @Column(name = "reservation_approval_policy_effective_at")
    private Instant reservationApprovalPolicyEffectiveAt;

    @Column(name = "archived_at")
    private Instant archivedAt;

    @Version
    @Column(nullable = false)
    private long version;

    @OneToMany(mappedBy = "room", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("orderIndex ASC")
    @BatchSize(size = 20)
    @Builder.Default
    private List<StudioRoomFeature> features = new ArrayList<>();

    @OneToMany(mappedBy = "room", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("orderIndex ASC")
    @BatchSize(size = 20)
    @Builder.Default
    private List<StudioRoomPhoto> photos = new ArrayList<>();

    public boolean isActive() {
        return archivedAt == null;
    }

    public boolean effectiveReservationApprovalRequired(Instant now) {
        if (pendingReservationApprovalRequired != null
                && reservationApprovalPolicyEffectiveAt != null
                && !now.isBefore(reservationApprovalPolicyEffectiveAt)) {
            return pendingReservationApprovalRequired;
        }
        return reservationApprovalRequired;
    }

    public Boolean futureReservationApprovalRequired(Instant now) {
        if (pendingReservationApprovalRequired == null
                || reservationApprovalPolicyEffectiveAt == null
                || !now.isBefore(reservationApprovalPolicyEffectiveAt)) {
            return null;
        }
        return pendingReservationApprovalRequired;
    }

    public Instant futureReservationApprovalPolicyEffectiveAt(Instant now) {
        return futureReservationApprovalRequired(now) == null
                ? null
                : reservationApprovalPolicyEffectiveAt;
    }

    public void materializeReservationApprovalPolicy(Instant now) {
        if (pendingReservationApprovalRequired != null
                && reservationApprovalPolicyEffectiveAt != null
                && !now.isBefore(reservationApprovalPolicyEffectiveAt)) {
            reservationApprovalRequired = pendingReservationApprovalRequired;
            pendingReservationApprovalRequired = null;
            reservationApprovalPolicyEffectiveAt = null;
        }
    }

    public int effectiveMinimumCapacity() {
        return minimumCapacity == null ? capacity : minimumCapacity;
    }

    public void replaceFeatures(List<String> labels) {
        features.clear();
        for (int index = 0; index < labels.size(); index++) {
            features.add(StudioRoomFeature.builder()
                    .room(this)
                    .label(labels.get(index))
                    .orderIndex(index)
                    .build());
        }
    }

    public void replacePhotos(List<java.util.UUID> mediaAssetIds) {
        photos.clear();
        for (int index = 0; index < mediaAssetIds.size(); index++) {
            photos.add(StudioRoomPhoto.builder()
                    .room(this)
                    .mediaAssetId(mediaAssetIds.get(index))
                    .orderIndex(index)
                    .build());
        }
    }
}

package com.berkayb.soundconnect.modules.studio.reservation.entity;

import com.berkayb.soundconnect.modules.studio.reservation.enums.StudioOccupancyType;
import com.berkayb.soundconnect.modules.studio.room.entity.StudioRoom;
import com.berkayb.soundconnect.shared.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.time.Instant;
import java.time.Duration;
import java.util.UUID;

import static com.berkayb.soundconnect.modules.studio.reservation.support.StudioReservationTimeProvider.MAX_DURATION_HOURS;
import static com.berkayb.soundconnect.modules.studio.reservation.support.StudioReservationTimeProvider.MAX_MANUAL_BLOCK_DURATION_HOURS;

@Entity
@Table(
        name = "tbl_studio_room_occupancy",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_studio_occupancy_reservation",
                columnNames = "reservation_id"
        )
)
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class StudioRoomOccupancy extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "room_id", nullable = false, updatable = false)
    private StudioRoom room;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reservation_id", updatable = false)
    private StudioRoomReservation reservation;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24, updatable = false)
    private StudioOccupancyType type;

    @Column(name = "starts_at", nullable = false, updatable = false)
    private Instant startsAt;

    @Column(name = "ends_at", nullable = false, updatable = false)
    private Instant endsAt;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_by", nullable = false, updatable = false, columnDefinition = "uuid")
    private UUID createdBy;

    @Column(name = "client_request_id", updatable = false, columnDefinition = "uuid")
    private UUID clientRequestId;

    @Column(name = "released_at")
    private Instant releasedAt;

    @Column(name = "released_by", columnDefinition = "uuid")
    private UUID releasedBy;

    @Column(name = "release_reason", length = 200)
    private String releaseReason;

    @Version
    @Column(nullable = false)
    private long version;

    /**
     * Mirrors the authoritative PostgreSQL check so non-production schemas and
     * any future persistence path cannot accidentally apply the four-hour
     * customer limit to an owner-created full-day block.
     */
    @PrePersist
    @PreUpdate
    void validateDurationInvariant() {
        if (type == null || startsAt == null || endsAt == null) {
            return;
        }
        Duration duration = Duration.between(startsAt, endsAt);
        int maximumHours = type == StudioOccupancyType.MANUAL_BLOCK
                ? MAX_MANUAL_BLOCK_DURATION_HOURS
                : MAX_DURATION_HOURS;
        if (duration.compareTo(Duration.ofHours(1)) < 0
                || duration.compareTo(Duration.ofHours(maximumHours)) > 0
                || !duration.equals(Duration.ofHours(duration.toHours()))) {
            throw new IllegalStateException(
                    "Studio occupancy duration is invalid for type " + type
            );
        }
    }
}

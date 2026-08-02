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
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.time.Instant;
import java.util.UUID;

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
    @JoinColumn(name = "room_id", nullable = false)
    private StudioRoom room;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reservation_id")
    private StudioRoomReservation reservation;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private StudioOccupancyType type;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_by", nullable = false, columnDefinition = "uuid")
    private UUID createdBy;

    @Column(name = "client_request_id", columnDefinition = "uuid")
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
}

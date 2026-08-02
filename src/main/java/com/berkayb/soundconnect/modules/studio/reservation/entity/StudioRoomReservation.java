package com.berkayb.soundconnect.modules.studio.reservation.entity;

import com.berkayb.soundconnect.modules.studio.reservation.enums.StudioReservationStatus;
import com.berkayb.soundconnect.modules.studio.room.entity.StudioRoom;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.shared.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
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
        name = "tbl_studio_room_reservation",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_studio_room_reservation_client_request",
                columnNames = {"requester_id", "client_request_id"}
        )
)
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class StudioRoomReservation extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "room_id", nullable = false)
    private StudioRoom room;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "requester_id", nullable = false)
    private User requester;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private StudioReservationStatus status;

    @Column(name = "approval_required_snapshot", nullable = false)
    private boolean approvalRequiredSnapshot;

    @Column(name = "hourly_price_minor_snapshot")
    private Long hourlyPriceMinorSnapshot;

    @Column(name = "total_price_minor_snapshot")
    private Long totalPriceMinorSnapshot;

    @Column(name = "currency_snapshot", nullable = false, length = 3)
    private String currencySnapshot;

    /**
     * Booking-time contact number. It is intentionally a snapshot so later
     * profile changes cannot alter the studio's reservation record.
     *
     * Nullable at schema level for backwards compatibility with reservations
     * created before this field existed; every new request requires it.
     */
    @Column(name = "contact_phone_snapshot", length = 16)
    private String contactPhoneSnapshot;

    @Column(name = "client_request_id", nullable = false, columnDefinition = "uuid")
    private UUID clientRequestId;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "decided_by", columnDefinition = "uuid")
    private UUID decidedBy;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancelled_by", columnDefinition = "uuid")
    private UUID cancelledBy;

    @Version
    @Column(nullable = false)
    private long version;
}

package com.berkayb.soundconnect.modules.collab.entity;

import com.berkayb.soundconnect.modules.collab.enums.CollabReportReason;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.shared.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "tbl_collab_report", uniqueConstraints = {
        @UniqueConstraint(name = "uk_collab_report_user_listing", columnNames = {"reporter_user_id", "collab_id"}),
        @UniqueConstraint(name = "uk_collab_report_request", columnNames = {"reporter_user_id", "client_request_id"})
})
public class CollabReport extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "collab_id", nullable = false)
    private Collab listing;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reporter_user_id", nullable = false)
    private User reporterUser;

    @Column(name = "client_request_id", nullable = false, columnDefinition = "uuid")
    private UUID clientRequestId;

    @Column(name = "request_payload_hash", nullable = false, length = 64, updatable = false)
    private String requestPayloadHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private CollabReportReason reason;

    @Column(length = 500)
    private String details;

    @Column(name = "reported_at", nullable = false)
    private Instant reportedAt;
}

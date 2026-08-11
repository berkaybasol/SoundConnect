package com.berkayb.soundconnect.modules.collab.entity;

import com.berkayb.soundconnect.modules.collab.enums.CollabApplicationStatus;
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
@Table(name = "tbl_collab_application", uniqueConstraints = {
        @UniqueConstraint(name = "uk_collab_application_actor", columnNames = {"collab_id", "applicant_actor_id"}),
        @UniqueConstraint(name = "uk_collab_application_user", columnNames = {"collab_id", "applicant_user_id"}),
        @UniqueConstraint(name = "uk_collab_application_request", columnNames = {"applicant_user_id", "client_request_id"}),
        @UniqueConstraint(name = "uk_collab_application_job_reference",
                columnNames = {"id", "collab_id", "applicant_actor_id", "applicant_user_id"})
}, indexes = {
        @Index(name = "idx_collab_application_listing", columnList = "collab_id,status,submitted_at,id"),
        @Index(name = "idx_collab_application_user", columnList = "applicant_user_id,submitted_at,id")
})
public class CollabApplication extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "collab_id", nullable = false)
    private Collab listing;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "applicant_actor_id", nullable = false)
    private CollabActor applicantActor;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "applicant_user_id", nullable = false)
    private User applicantUser;

    @Column(name = "client_request_id", nullable = false, columnDefinition = "uuid")
    private UUID clientRequestId;

    @Column(name = "request_payload_hash", nullable = false, length = 64, updatable = false)
    private String requestPayloadHash;

    @Column(name = "phone_snapshot", nullable = false, length = 32)
    private String phoneSnapshot;

    @Column(length = 500)
    private String message;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 48)
    @Builder.Default
    private CollabApplicationStatus status = CollabApplicationStatus.PENDING;

    @Column(name = "submitted_at", nullable = false)
    private Instant submittedAt;

    @Column(name = "status_changed_at", nullable = false)
    private Instant statusChangedAt;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Version
    @Builder.Default
    @Column(nullable = false)
    private long version = 0;
}

package com.berkayb.soundconnect.modules.collab.entity;

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
@Table(name = "tbl_collab_review", uniqueConstraints = {
        @UniqueConstraint(name = "uk_collab_review_side", columnNames = {"job_id", "reviewer_actor_id"}),
        @UniqueConstraint(name = "uk_collab_review_request", columnNames = {"reviewer_user_id", "client_request_id"})
}, indexes = @Index(name = "idx_collab_review_target", columnList = "target_actor_id,submitted_at,id"))
public class CollabReview extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_id", nullable = false)
    private CollabJob job;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reviewer_actor_id", nullable = false)
    private CollabActor reviewerActor;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "target_actor_id", nullable = false)
    private CollabActor targetActor;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reviewer_user_id", nullable = false)
    private User reviewerUser;

    @Column(name = "client_request_id", nullable = false, columnDefinition = "uuid")
    private UUID clientRequestId;

    @Column(name = "request_payload_hash", nullable = false, length = 64, updatable = false)
    private String requestPayloadHash;

    @Column(nullable = false)
    private int rating;

    @Column(length = 500)
    private String comment;

    @Column(name = "submitted_at", nullable = false)
    private Instant submittedAt;
}

package com.berkayb.soundconnect.modules.collab.entity;

import com.berkayb.soundconnect.modules.collab.enums.CollabJobStatus;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.shared.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.Instant;

@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "tbl_collab_job", uniqueConstraints = {
        @UniqueConstraint(name = "uk_collab_job_listing", columnNames = "collab_id"),
        @UniqueConstraint(name = "uk_collab_job_application", columnNames = "application_id")
}, indexes = {
        @Index(name = "idx_collab_job_publisher", columnList = "publisher_user_id,status,id"),
        @Index(name = "idx_collab_job_applicant", columnList = "applicant_user_id,status,id")
})
public class CollabJob extends BaseEntity {
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "collab_id", nullable = false)
    private Collab listing;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "application_id", nullable = false)
    private CollabApplication application;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "publisher_actor_id", nullable = false)
    private CollabActor publisherActor;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "applicant_actor_id", nullable = false)
    private CollabActor applicantActor;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "publisher_user_id", nullable = false)
    private User publisherUser;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "applicant_user_id", nullable = false)
    private User applicantUser;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private CollabJobStatus status = CollabJobStatus.ACTIVE;

    @Column(name = "publisher_confirmed_at")
    private Instant publisherConfirmedAt;

    @Column(name = "applicant_confirmed_at")
    private Instant applicantConfirmedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Version
    @Builder.Default
    @Column(nullable = false)
    private long version = 0;
}

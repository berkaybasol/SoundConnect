package com.berkayb.soundconnect.modules.collab.entity;

import com.berkayb.soundconnect.modules.profile.shared.media.enums.ProfileType;
import com.berkayb.soundconnect.shared.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "tbl_collab_actor", uniqueConstraints =
        @UniqueConstraint(name = "uk_collab_actor_profile", columnNames = {"profile_type", "source_profile_id"}))
public class CollabActor extends BaseEntity {
    @Enumerated(EnumType.STRING)
    @Column(name = "profile_type", nullable = false, length = 24)
    private ProfileType profileType;

    @Column(name = "source_profile_id", nullable = false, columnDefinition = "uuid")
    private java.util.UUID sourceProfileId;

    @Column(name = "display_name", nullable = false, length = 120)
    private String displayName;

    @Column(name = "avatar_url", length = 1024)
    private String avatarUrl;

    @Builder.Default
    @Column(name = "rating_sum", nullable = false)
    private long ratingSum = 0;

    @Builder.Default
    @Column(name = "review_count", nullable = false)
    private long reviewCount = 0;

    @Builder.Default
    @Column(name = "completed_job_count", nullable = false)
    private long completedJobCount = 0;

    @Builder.Default
    @Column(nullable = false)
    private boolean active = true;

    @Version
    @Builder.Default
    @Column(nullable = false)
    private long version = 0;

    public void recordReview(int rating) {
        ratingSum += rating;
        reviewCount++;
    }

    public void recordCompletedJob() {
        completedJobCount++;
    }
}

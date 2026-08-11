package com.berkayb.soundconnect.modules.collab.repository;

import com.berkayb.soundconnect.modules.collab.entity.CollabReview;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;

import java.util.*;

public interface CollabReviewRepository extends JpaRepository<CollabReview, UUID> {
    @EntityGraph(attributePaths = {"job", "job.publisherUser", "job.applicantUser", "reviewerActor", "targetActor", "reviewerUser"})
    Optional<CollabReview> findByReviewerUserIdAndClientRequestId(UUID reviewerUserId, UUID clientRequestId);
    Optional<CollabReview> findByJobIdAndReviewerActorId(UUID jobId, UUID reviewerActorId);

    @Query("select r.job.id from CollabReview r where r.reviewerUser.id = :userId and r.job.id in :jobIds")
    Set<UUID> findReviewedJobIds(@org.springframework.data.repository.query.Param("userId") UUID userId,
                                 @org.springframework.data.repository.query.Param("jobIds") Collection<UUID> jobIds);

    @EntityGraph(attributePaths = {"job", "job.publisherUser", "job.applicantUser", "reviewerActor", "targetActor", "reviewerUser"})
    Page<CollabReview> findByTargetActorId(UUID targetActorId, Pageable pageable);
}

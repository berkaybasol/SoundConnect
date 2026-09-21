package com.berkayb.soundconnect.modules.event.plan;

import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.*;
import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.*;

public interface EventPlanRepository extends JpaRepository<EventPlan, UUID> {
    @Query(value = "select id from tbl_user where id = :id for update", nativeQuery = true)
    Optional<UUID> lockOwner(@Param("id") UUID id);
    @Query("select new com.berkayb.soundconnect.modules.event.plan.EventPlanAuthority(p.organizerUserId,p.venueId,p.bandId,p.musicianProfileId) from EventPlan p where p.id=:id")
    Optional<EventPlanAuthority> findAuthority(@Param("id") UUID id);
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select p from EventPlan p where p.id = :id")
    Optional<EventPlan> findByIdForUpdate(@Param("id") UUID id);
    @Query("select p.bandId from EventPlan p where p.id = :id")
    Optional<UUID> findBandId(@Param("id") UUID id);
    Optional<EventPlan> findByOrganizerUserIdAndClientRequestId(UUID userId, UUID requestId);
    @Query("select p from EventPlan p where p.status=com.berkayb.soundconnect.modules.event.plan.EventPlanStatus.ACTIVE"
            + " and (:ownerId is null or p.organizerUserId=:ownerId) and (:venueId is null or p.venueId=:venueId)"
            + " and (p.untilDate is null or p.untilDate>=:today) and (:after is null or p.id>:after) order by p.id")
    List<EventPlan> findPotentiallyActiveAfter(@Param("ownerId") UUID ownerId, @Param("venueId") UUID venueId,
            @Param("today") LocalDate today, @Param("after") UUID after, Pageable pageable);
    @Query(value = "select p from EventPlan p where p.venueId=:venueId"
            + " order by case when p.id in :activePlanIds then 0 else 1 end,"
            + " p.updatedAt desc nulls last, p.id desc",
            countQuery = "select count(p) from EventPlan p where p.venueId=:venueId")
    Page<EventPlan> findOwnerPlans(@Param("venueId") UUID venueId, @Param("activePlanIds") Collection<UUID> activePlanIds,
                                  Pageable pageable);
    Page<EventPlan> findByMusicianProfileIdOrderByCreatedAtDescIdDesc(UUID profileId, Pageable pageable);
    Page<EventPlan> findByBandIdOrderByCreatedAtDescIdDesc(UUID bandId, Pageable pageable);
    @Query("select p.id from EventPlan p where p.status = com.berkayb.soundconnect.modules.event.plan.EventPlanStatus.ACTIVE"
            + " and p.startDate <= :through and (p.generatedThrough is null or p.generatedThrough < :through) order by p.id")
    List<UUID> findGenerationCandidates(@Param("through") LocalDate through, Pageable pageable);
    @Query("select p.id from EventPlan p where p.status = com.berkayb.soundconnect.modules.event.plan.EventPlanStatus.ACTIVE"
            + " and p.startDate <= :through and (p.generatedThrough is null or p.generatedThrough < :through)"
            + " and (:after is null or p.id > :after) order by p.id")
    List<UUID> findGenerationCandidatesAfter(@Param("through") LocalDate through, @Param("after") UUID after, Pageable pageable);
}

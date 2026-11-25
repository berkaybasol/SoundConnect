package com.berkayb.soundconnect.modules.application.studioapplication.repository;

import com.berkayb.soundconnect.modules.application.studioapplication.entity.StudioApplication;
import com.berkayb.soundconnect.modules.application.venueapplication.enums.ApplicationStatus;
import com.berkayb.soundconnect.modules.user.entity.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface StudioApplicationRepository extends JpaRepository<StudioApplication, UUID> {
    // Match erasure's account -> application lock order. The applicant is immutable.
    @Query(value = """
            with applicant as materialized (
                select u.id from tbl_user u join tbl_studio_applications a on a.applicant_id=u.id
                where a.id=:id and u.erased_at is null for update of u
            ) select a.* from tbl_studio_applications a join applicant on applicant.id=a.applicant_id
                where a.id=:id for update of a
            """, nativeQuery = true)
	Optional<StudioApplication> findByIdForUpdate(@Param("id") UUID id);

	Optional<StudioApplication> findByApplicantAndStatus(User applicant, ApplicationStatus status);

	@EntityGraph(attributePaths = {"applicant", "city", "district", "neighborhood"})
	Page<StudioApplication> findAllByApplicant(User applicant, Pageable pageable);

	@EntityGraph(attributePaths = {"applicant", "city", "district", "neighborhood", "reviewedBy"})
	Page<StudioApplication> findAllByStatus(ApplicationStatus status, Pageable pageable);

    Optional<StudioApplication> findFirstByApplicantIdAndStatusOrderByDecisionDateDescIdDesc(UUID applicantId, ApplicationStatus status);

	long countByStatus(ApplicationStatus status);
}

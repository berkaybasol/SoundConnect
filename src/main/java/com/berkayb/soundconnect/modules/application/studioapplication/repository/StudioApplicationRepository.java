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
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select application from StudioApplication application where application.id = :id")
	Optional<StudioApplication> findByIdForUpdate(@Param("id") UUID id);

	Optional<StudioApplication> findByApplicantAndStatus(User applicant, ApplicationStatus status);

	@EntityGraph(attributePaths = {"applicant", "city", "district", "neighborhood", "reviewedBy"})
	Page<StudioApplication> findAllByApplicant(User applicant, Pageable pageable);

	@EntityGraph(attributePaths = {"applicant", "city", "district", "neighborhood", "reviewedBy"})
	Page<StudioApplication> findAllByStatus(ApplicationStatus status, Pageable pageable);

	long countByStatus(ApplicationStatus status);
}

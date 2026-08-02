package com.berkayb.soundconnect.modules.application.studioapplication.repository;

import com.berkayb.soundconnect.modules.application.studioapplication.entity.StudioApplication;
import com.berkayb.soundconnect.modules.application.venueapplication.enums.ApplicationStatus;
import com.berkayb.soundconnect.modules.user.entity.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StudioApplicationRepository extends JpaRepository<StudioApplication, UUID> {
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select application from StudioApplication application where application.id = :id")
	Optional<StudioApplication> findByIdForUpdate(@Param("id") UUID id);

	Optional<StudioApplication> findByApplicantAndStatus(User applicant, ApplicationStatus status);

	List<StudioApplication> findAllByApplicantOrderByApplicationDateDesc(User applicant);

	List<StudioApplication> findAllByStatusOrderByApplicationDateAsc(ApplicationStatus status);

	long countByStatus(ApplicationStatus status);
}

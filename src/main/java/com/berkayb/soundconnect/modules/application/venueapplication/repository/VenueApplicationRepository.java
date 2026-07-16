package com.berkayb.soundconnect.modules.application.venueapplication.repository;

import com.berkayb.soundconnect.modules.application.venueapplication.entity.VenueApplication;
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

public interface VenueApplicationRepository extends JpaRepository<VenueApplication, UUID> {
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select application from VenueApplication application where application.id = :id")
	Optional<VenueApplication> findByIdForUpdate(@Param("id") UUID id);

	// basvurulari getir (kullanici)
	List<VenueApplication> findAllByApplicant(User applicant);
	
	// basvuru var mi sorgula
	Optional<VenueApplication> findByApplicantAndStatus(User applicant, ApplicationStatus status);
	
	// basvurulari getir(admin)
	List<VenueApplication> findAllByStatus(ApplicationStatus status);

	long countByStatus(ApplicationStatus status);
	
}

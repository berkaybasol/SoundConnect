package com.berkayb.soundconnect.modules.application.venueapplication.repository;

import com.berkayb.soundconnect.modules.application.venueapplication.entity.VenueApplication;
import com.berkayb.soundconnect.modules.application.venueapplication.enums.ApplicationStatus;
import com.berkayb.soundconnect.modules.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VenueApplicationRepository extends JpaRepository<VenueApplication, UUID> {
	// basvurulari getir (kullanici)
	List<VenueApplication> findAllByApplicant(User applicant);
	
	// basvuru var mi sorgula
	Optional<VenueApplication> findByApplicantAndStatus(User applicant, ApplicationStatus status);
	
	// basvurulari getir(admin)
	List<VenueApplication> findAllByStatus(ApplicationStatus status);
	
}
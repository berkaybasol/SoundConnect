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
    Optional<VenueApplication> findByIdAndApplicant_Id(UUID id, UUID applicantId);
    Optional<VenueApplication> findFirstByApplicant_IdOrderByApplicationDateDescCreatedAtDescIdDesc(UUID applicantId);

    @Query(value = """
            with applicant as materialized (
                select u.id from tbl_user u join tbl_venue_applications a on a.user_id=u.id
                where a.id=:id and u.id=:applicantId and u.erased_at is null for update of u
            ) select a.* from tbl_venue_applications a join applicant on applicant.id=a.user_id
                where a.id=:id for update of a
            """, nativeQuery = true)
    Optional<VenueApplication> findOwnedForUpdate(@Param("id") UUID id, @Param("applicantId") UUID applicantId);

    // Match erasure's account -> application lock order. The applicant is immutable.
    @Query(value = """
            with applicant as materialized (
                select u.id from tbl_user u join tbl_venue_applications a on a.user_id=u.id
                where a.id=:id and u.erased_at is null for update of u
            ) select a.* from tbl_venue_applications a join applicant on applicant.id=a.user_id
                where a.id=:id for update of a
            """, nativeQuery = true)
	Optional<VenueApplication> findByIdForUpdate(@Param("id") UUID id);

	// basvurulari getir (kullanici)
	List<VenueApplication> findAllByApplicant(User applicant);
	
	// basvuru var mi sorgula
	Optional<VenueApplication> findByApplicantAndStatus(User applicant, ApplicationStatus status);
	
	// basvurulari getir(admin)
	List<VenueApplication> findAllByStatus(ApplicationStatus status);

	long countByStatus(ApplicationStatus status);
	
}

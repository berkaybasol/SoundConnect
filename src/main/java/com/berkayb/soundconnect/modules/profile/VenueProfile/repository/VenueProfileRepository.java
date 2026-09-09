package com.berkayb.soundconnect.modules.profile.VenueProfile.repository;

import com.berkayb.soundconnect.modules.profile.VenueProfile.entity.VenueProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.Optional;
import java.util.UUID;

public interface VenueProfileRepository extends JpaRepository<VenueProfile, UUID> {
	Optional<VenueProfile> findByVenueId(UUID venueId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select profile from VenueProfile profile where profile.venue.id = :venueId")
	Optional<VenueProfile> findByVenueIdForUpdate(@Param("venueId") UUID venueId);
}

package com.berkayb.soundconnect.modules.profile.repository;

import com.berkayb.soundconnect.modules.profile.entity.VenueProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface VenueProfileRepository extends JpaRepository<VenueProfile, UUID> {
	Optional<VenueProfile> findByVenueId(UUID venueId);
	
}
package com.berkayb.soundconnect.modules.profile.StudioProfile.repository;

import com.berkayb.soundconnect.modules.profile.StudioProfile.entity.StudioProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface StudioProfileRepository extends JpaRepository<StudioProfile, UUID> {
	// belirli bir kullaniciya ait studio profilini id ile getir
	Optional<StudioProfile> findByUserId(UUID userID);
	
	// belirli bir kullaniciya ait studio profilini studioname ile getir.
	Optional<StudioProfile> findStudioProfileByName(String name);
}
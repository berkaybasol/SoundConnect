package com.berkayb.soundconnect.modules.profile.OrganizerProfile.repository;

import com.berkayb.soundconnect.modules.profile.OrganizerProfile.entity.OrganizerProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface OrganizerProfileRepository extends JpaRepository<OrganizerProfile, UUID> {
	
	// belirli bir kullaniciya ait organizer profilini id ye gore getir
	Optional<OrganizerProfile> findByUserId(UUID userId);
	
	// name ile getir
	Optional<OrganizerProfile> findOrganizerProfileByName(String name);
	
	//FIXME eklemeler yapilabilir, yapilir hatta :D
}
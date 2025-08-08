package com.berkayb.soundconnect.modules.profile.ProducerProfile.repository;

import com.berkayb.soundconnect.modules.profile.ProducerProfile.entity.ProducerProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;


public interface ProducerProfileRepository extends JpaRepository<ProducerProfile, UUID> {
	Optional<ProducerProfile> findByUserId(UUID userId);
	
	Optional<ProducerProfile> findByName(String name);
}
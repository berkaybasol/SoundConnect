package com.berkayb.soundconnect.modules.profile.repository;

import com.berkayb.soundconnect.modules.profile.entity.MusicianProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface MusicianProfileRepository extends JpaRepository<MusicianProfile, UUID> {
	Optional<MusicianProfile> findByUserId(UUID userId);
}
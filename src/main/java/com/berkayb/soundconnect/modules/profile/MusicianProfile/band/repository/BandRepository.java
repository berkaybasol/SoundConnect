package com.berkayb.soundconnect.modules.profile.MusicianProfile.band.repository;

import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity.Band;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface BandRepository extends JpaRepository<Band, UUID> {
	
	Optional<Band> findByName(String name);
}
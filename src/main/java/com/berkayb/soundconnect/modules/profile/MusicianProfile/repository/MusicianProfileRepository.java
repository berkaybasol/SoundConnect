package com.berkayb.soundconnect.modules.profile.MusicianProfile.repository;

import com.berkayb.soundconnect.modules.profile.MusicianProfile.entity.MusicianProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MusicianProfileRepository extends JpaRepository<MusicianProfile, UUID> {
	
	Optional<MusicianProfile> findByUserId(UUID userId);
	
	Optional<MusicianProfile> findBySpotifyArtistId(String spotifyArtistId);
	
	List<MusicianProfile> findBySpotifyArtistIdIn(List<String> artistIds);
}
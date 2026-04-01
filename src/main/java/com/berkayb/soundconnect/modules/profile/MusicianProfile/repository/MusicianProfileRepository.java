package com.berkayb.soundconnect.modules.profile.MusicianProfile.repository;

import com.berkayb.soundconnect.modules.profile.MusicianProfile.entity.MusicianProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MusicianProfileRepository extends JpaRepository<MusicianProfile, UUID> {
	
	Optional<MusicianProfile> findByUserId(UUID userId);
	
	Optional<MusicianProfile> findBySpotifyArtistId(String spotifyArtistId);
	
	List<MusicianProfile> findBySpotifyArtistIdIn(List<String> artistIds);
	
	@Query("""
		select mp
		from MusicianProfile mp
		where
			(:q is null or trim(:q) = '')
			or lower(coalesce(mp.stageName, '')) like lower(concat('%', :q, '%'))
			or lower(coalesce(mp.user.username, '')) like lower(concat('%', :q, '%'))
	""")
	List<MusicianProfile> searchByStageNameOrUsername(@Param("q") String q);
}
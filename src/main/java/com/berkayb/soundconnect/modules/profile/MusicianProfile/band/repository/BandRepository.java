package com.berkayb.soundconnect.modules.profile.MusicianProfile.band.repository;

import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity.Band;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.entity.MusicianProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BandRepository extends JpaRepository<Band, UUID> {
	
	Optional<Band> findByName(String name);
	
	Optional<Band> findBySpotifyArtistId(String spotifyArtistId);
	
	List<Band> findBySpotifyArtistIdIn(List<String> artistIds);
	
	@Query("""
		select b
		from Band b
		where
			(:q is null or trim(:q) = '')
			or lower(coalesce(b.name, '')) like lower(concat('%', :q, '%'))
	""")
	List<Band> searchByName(@Param("q") String q);
}
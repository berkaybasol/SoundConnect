package com.berkayb.soundconnect.modules.profile.MusicianProfile.repository;

import com.berkayb.soundconnect.modules.profile.MusicianProfile.entity.MusicianProfile;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
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
	
	@EntityGraph(attributePaths = "user")
	@Query("""
		select mp
		from MusicianProfile mp
		where
			(:q is null or trim(:q) = '')
			or locate(lower(:q), lower(coalesce(mp.stageName, ''))) > 0
			or (:usernameQuery <> ''
				and locate(:usernameQuery, coalesce(mp.user.username, '')) > 0)
		order by
			case
				when lower(coalesce(mp.stageName, mp.user.username, '')) = lower(:q) then 0
				when locate(lower(:q), lower(coalesce(mp.stageName, mp.user.username, ''))) = 1 then 1
				else 2
			end,
			lower(coalesce(mp.stageName, mp.user.username, '')),
			mp.id
	""")
	List<MusicianProfile> searchByStageNameOrUsername(
			@Param("q") String q,
			@Param("usernameQuery") String usernameQuery,
			Pageable pageable
	);
}

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

	/**
	 * Locks the concrete connection row until the surrounding event-creation
	 * transaction completes. A concurrent disconnect therefore cannot make an
	 * authorization decision from a stale join-table snapshot.
	 */
	@Query(value = """
			select 1
			from musician_profile_venues connection
			where connection.musician_profile_id = :musicianProfileId
			  and connection.venue_id = :venueId
			for key share
			""", nativeQuery = true)
	Optional<Integer> lockActiveVenueConnection(
			@Param("musicianProfileId") UUID musicianProfileId,
			@Param("venueId") UUID venueId
	);
	
	List<MusicianProfile> findBySpotifyArtistIdIn(List<String> artistIds);
	
	@EntityGraph(attributePaths = "user")
	@Query("""
		select mp
		from MusicianProfile mp
		where
			(:q is null or trim(:q) = '')
			or locate(
				lower(function('translate', :q, 'ÇĞİIÖŞÜçğıöşü', 'CGIIOSUcgiosu')),
				lower(function('translate', coalesce(mp.stageName, ''), 'ÇĞİIÖŞÜçğıöşü', 'CGIIOSUcgiosu'))
			) > 0
			or (:usernameQuery <> ''
				and locate(
					lower(function('translate', :usernameQuery, 'ÇĞİIÖŞÜçğıöşü', 'CGIIOSUcgiosu')),
					lower(function('translate', coalesce(mp.user.username, ''), 'ÇĞİIÖŞÜçğıöşü', 'CGIIOSUcgiosu'))
				) > 0)
		order by
			case
				when lower(function('translate', coalesce(mp.stageName, mp.user.username, ''), 'ÇĞİIÖŞÜçğıöşü', 'CGIIOSUcgiosu'))
					= lower(function('translate', :q, 'ÇĞİIÖŞÜçğıöşü', 'CGIIOSUcgiosu')) then 0
				when locate(
					lower(function('translate', :q, 'ÇĞİIÖŞÜçğıöşü', 'CGIIOSUcgiosu')),
					lower(function('translate', coalesce(mp.stageName, mp.user.username, ''), 'ÇĞİIÖŞÜçğıöşü', 'CGIIOSUcgiosu'))
				) = 1 then 1
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

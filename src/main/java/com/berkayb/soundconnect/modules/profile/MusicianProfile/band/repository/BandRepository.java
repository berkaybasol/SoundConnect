package com.berkayb.soundconnect.modules.profile.MusicianProfile.band.repository;

import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity.Band;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.entity.MusicianProfile;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;

public interface BandRepository extends JpaRepository<Band, UUID> {
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select b from Band b where b.id = :bandId")
	Optional<Band> findByIdForUpdate(@Param("bandId") UUID bandId);
	
	Optional<Band> findByName(String name);
	
	Optional<Band> findBySpotifyArtistId(String spotifyArtistId);
	
	List<Band> findBySpotifyArtistIdIn(List<String> artistIds);
	
	@Query("""
		select b
		from Band b
		where
			(:q is null or trim(:q) = '')
			or locate(lower(:q), lower(coalesce(b.name, ''))) > 0
		order by
			case
				when lower(coalesce(b.name, '')) = lower(:q) then 0
				when locate(lower(:q), lower(coalesce(b.name, ''))) = 1 then 1
				else 2
			end,
			lower(coalesce(b.name, '')),
			b.id
	""")
	List<Band> searchByName(@Param("q") String q, Pageable pageable);
}

package com.berkayb.soundconnect.modules.profile.MusicianProfile.band.repository;

import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity.Band;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import jakarta.persistence.LockModeType;

public interface BandRepository extends JpaRepository<Band, UUID> {

	@Query(value = """
			select distinct m.band_id from tbl_band_member m join tbl_user u on u.id=m.user_id
			where m.band_id in (:bandIds) and m.status='ACTIVE' and m.band_role='FOUNDER'
			  and u.status='ACTIVE' and u.email_verified and u.erased_at is null and not u.access_suspended
			""", nativeQuery = true)
	Set<UUID> findContactableBandIds(@Param("bandIds") List<UUID> bandIds);

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
			or locate(
				lower(function('translate', :q, 'ÇĞİIÖŞÜçğıöşü', 'CGIIOSUcgiosu')),
				lower(function('translate', coalesce(b.name, ''), 'ÇĞİIÖŞÜçğıöşü', 'CGIIOSUcgiosu'))
			) > 0
		order by
			case
				when lower(function('translate', coalesce(b.name, ''), 'ÇĞİIÖŞÜçğıöşü', 'CGIIOSUcgiosu'))
					= lower(function('translate', :q, 'ÇĞİIÖŞÜçğıöşü', 'CGIIOSUcgiosu')) then 0
				when locate(
					lower(function('translate', :q, 'ÇĞİIÖŞÜçğıöşü', 'CGIIOSUcgiosu')),
					lower(function('translate', coalesce(b.name, ''), 'ÇĞİIÖŞÜçğıöşü', 'CGIIOSUcgiosu'))
				) = 1 then 1
				else 2
			end,
			lower(coalesce(b.name, '')),
			b.id
	""")
	List<Band> searchByName(@Param("q") String q, Pageable pageable);
}

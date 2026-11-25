package com.berkayb.soundconnect.modules.profile.MusicianProfile.band.repository;

import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity.Band;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import jakarta.persistence.LockModeType;

public interface BandRepository extends JpaRepository<Band, UUID> {

	// A creation-time USER image may be displayed by multiple bands. Keep the object and
	// assign its management to one surviving band; the same handoff runs if that band closes.
	@Modifying(flushAutomatically = true)
	@Query(value = """
			update tbl_media_asset a set owner_type='BAND', owner_id=c.band_id,
			    title=null, description=null
			from (select distinct on (b.profile_picture_media_id) b.profile_picture_media_id as asset_id, b.id as band_id
			      from tbl_band b join tbl_media_asset asset on asset.id=b.profile_picture_media_id
			      where asset.owner_type=:ownerType and asset.owner_id=:ownerId
			          and (:ownerType <> 'BAND' or b.id<>:ownerId)
			      order by b.profile_picture_media_id,b.id) c
			where a.id=c.asset_id and a.owner_type=:ownerType and a.owner_id=:ownerId
			""", nativeQuery = true)
	int adoptReferencedCovers(@Param("ownerType") String ownerType, @Param("ownerId") UUID ownerId);

	@Modifying(flushAutomatically = true)
	@Query(value = "delete from tbl_profile_media where profile_type='BAND' and profile_id=:bandId", nativeQuery = true)
	void deleteMediaAttachments(@Param("bandId") UUID bandId);

	@Query(value = "select id from tbl_media_asset where owner_type='BAND' and owner_id=:bandId order by id for update", nativeQuery = true)
	List<UUID> lockOwnedMedia(@Param("bandId") UUID bandId);

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

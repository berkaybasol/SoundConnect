package com.berkayb.soundconnect.modules.media.repository;

import com.berkayb.soundconnect.modules.media.entity.MediaAsset;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

/**
 * Read-only reference index for destructive media operations.
 *
 * <p>Most of the legacy relations intentionally store a raw UUID rather than a
 * JPA association. Keeping the checks together makes it harder for a new
 * destructive path to forget one of those logical foreign keys.</p>
 */
public interface MediaAssetReferenceRepository extends Repository<MediaAsset, UUID> {

	@Query("select count(p) from Promotion p where p.mediaAsset.id = :assetId")
	long countPromotionReferences(@Param("assetId") UUID assetId);

	@Query("select count(t) from Track t where t.mediaAssetId = :assetId")
	long countTrackReferences(@Param("assetId") UUID assetId);

	@Query("select count(pm) from ProfileMedia pm where pm.mediaAssetId = :assetId")
	long countProfileMediaReferences(@Param("assetId") UUID assetId);

	@Query("select count(p) from MusicianProfile p where p.profilePictureMediaId = :assetId")
	long countMusicianProfilePictureReferences(@Param("assetId") UUID assetId);

	@Query("select count(p) from ListenerProfile p where p.profilePictureMediaId = :assetId")
	long countListenerProfilePictureReferences(@Param("assetId") UUID assetId);

	@Query("select count(p) from ProducerProfile p where p.profilePictureMediaId = :assetId")
	long countProducerProfilePictureReferences(@Param("assetId") UUID assetId);

	@Query("select count(p) from OrganizerProfile p where p.profilePictureMediaId = :assetId")
	long countOrganizerProfilePictureReferences(@Param("assetId") UUID assetId);

	@Query("select count(p) from StudioProfile p where p.profilePictureMediaId = :assetId")
	long countStudioProfilePictureReferences(@Param("assetId") UUID assetId);

	@Query("select count(p) from VenueProfile p where p.profilePictureMediaId = :assetId")
	long countVenueProfilePictureReferences(@Param("assetId") UUID assetId);

	@Query("select count(b) from Band b where b.profilePictureMediaId = :assetId")
	long countBandProfilePictureReferences(@Param("assetId") UUID assetId);

	@Query("""
			select count(e) from Event e
			where lower(trim(e.posterImage)) = :assetIdText
			""")
	long countEventPosterReferences(@Param("assetIdText") String assetIdText);

}

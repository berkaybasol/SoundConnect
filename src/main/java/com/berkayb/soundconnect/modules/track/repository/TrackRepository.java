package com.berkayb.soundconnect.modules.track.repository;

import com.berkayb.soundconnect.modules.track.entity.Track;
import com.berkayb.soundconnect.modules.track.enums.TrackOwnerType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TrackRepository extends JpaRepository<Track, UUID> {
	@Query(value = """
			select id, owner_id as "ownerId", owner_type as "ownerType", media_asset_id as "mediaAssetId"
			from tbl_tracks where id=:id for share
			""", nativeQuery = true)
	Optional<LockedTrack> lockForReference(@Param("id") UUID id);

	interface LockedTrack {
		UUID getId(); UUID getOwnerId(); String getOwnerType(); UUID getMediaAssetId();
	}
	
	// bir ownerin tum tracklerini getir
	Page<Track> findByOwnerIdAndOwnerType(UUID ownerId, TrackOwnerType ownerType, Pageable pageable);
	
	List<Track> findAllByOwnerIdAndOwnerType(UUID ownerId, TrackOwnerType ownerType);

	Optional<Track> findByOwnerTypeAndOwnerIdAndMediaAssetId(
			TrackOwnerType ownerType,
			UUID ownerId,
			UUID mediaAssetId
	);

	void deleteAllByOwnerIdAndOwnerType(UUID ownerId, TrackOwnerType ownerType);
}

package com.berkayb.soundconnect.modules.media.repository;

import com.berkayb.soundconnect.modules.media.entity.MediaAsset;
import com.berkayb.soundconnect.modules.media.enums.MediaKind;
import com.berkayb.soundconnect.modules.media.enums.MediaOwnerType;
import com.berkayb.soundconnect.modules.media.enums.MediaStatus;
import com.berkayb.soundconnect.modules.media.enums.MediaVisibility;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface MediaAssetRepository extends JpaRepository<MediaAsset, UUID> {

	// sahibin tum medyasi
	Page<MediaAsset> findByOwnerTypeAndOwnerId(MediaOwnerType ownerType, UUID ownerId, Pageable pageable);
	
	// sahibin belirli ture gore medyasi
	Page<MediaAsset> findByOwnerTypeAndOwnerIdAndKind(
			MediaOwnerType ownerType,
			UUID ownerId,
			MediaKind kind,
			Pageable pageable
	);
	
	// sahibin public + ready medyalari
	Page<MediaAsset> findByOwnerTypeAndOwnerIdAndVisibilityAndStatus(
			MediaOwnerType ownerType,
			UUID ownerId,
			MediaVisibility visibility,
			MediaStatus status,
			Pageable pageable
	);
	
	//sahibin public + ready + tur bazli medyalari
	Page<MediaAsset> findByOwnerTypeAndOwnerIdAndKindAndVisibilityAndStatus(
			MediaOwnerType ownerType,
			UUID ownerId,
			MediaKind kind,
			MediaVisibility visibility,
			MediaStatus status,
			Pageable pageable
	);
	
	// kota/istatistik icin
	long countByOwnerTypeAndOwnerId(MediaOwnerType ownerType, UUID ownerId);
	
	// sistemdeki public + ready medyalar
	Page<MediaAsset> findByVisibilityAndStatus(
			MediaVisibility visibility,
			MediaStatus status,
			Pageable pageable
	);
	
	// sistemdeki public + ready + tur bazli medyalar
	Page<MediaAsset> findByVisibilityAndStatusAndKind(
			MediaVisibility visibility,
			MediaStatus status,
			MediaKind kind,
			Pageable pageable
	);
}
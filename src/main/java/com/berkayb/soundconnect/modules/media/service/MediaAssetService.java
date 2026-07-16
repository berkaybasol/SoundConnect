package com.berkayb.soundconnect.modules.media.service;


import com.berkayb.soundconnect.modules.media.dto.response.MediaAccessUrlResponseDto;
import com.berkayb.soundconnect.modules.media.dto.response.UploadInitResultResponseDto;
import com.berkayb.soundconnect.modules.media.entity.MediaAsset;
import com.berkayb.soundconnect.modules.media.enums.MediaKind;
import com.berkayb.soundconnect.modules.media.enums.MediaOwnerType;
import com.berkayb.soundconnect.modules.media.enums.MediaVisibility;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface MediaAssetService {
	
	// upload baslatma metodu.
	// bu metod istemcinin medya yuklemesine baslamasi icin gerekli bilgileri uretir.
	// presigned url olusturulur(S3/R2 gibi storage'a dogrudan yukleme icin.
	// veritabanina draft(taslak) bir MediaAsset kaydi eklenir.
	// bu sayede dosya henuz gelmeden sistemde varligini tanitmis oluruz.
	UploadInitResultResponseDto initUpload(
			UUID actingUserId,
			MediaOwnerType ownerType, // medya sahibinini tanimlar.
			UUID ownerId, // medya sahibinin id
			MediaKind kind, // medya turu (image,video,auidio)
			MediaVisibility visibility, // medya gorunurluk (public, private, unlisted)
			String mimeType,  // dosyain mime type'i (image/png, video/mp4 vs.)
			long sizeBytes, // byte cinsinden dosya boyutu
			String originalFileName // dosyanin orjinal adi
	);
	
	
	// upload tamamlandiktan sonra cagirilan metod
	// istemci puut islemlerini bitirdikten sonra bu metod cagirilir.
	// video ise "processing" durumuna alinir, diger turler dogrudan "ready" yapilir.
	MediaAsset completeUpload(UUID actingUserId, UUID assetId);
	
	
	// owner'a ait tum medya varliklarini listeler
	Page<MediaAsset> listByOwner(UUID actingUserId, MediaOwnerType ownerType, UUID ownerId, Pageable pageable);
	
	// owner'a ait bel
	Page<MediaAsset> listByOwnerAndKind(UUID actingUserId, MediaOwnerType ownerType, UUID ownerId, MediaKind kind, Pageable pageable);
	Page<MediaAsset> listPublicByOwner(MediaOwnerType ownerType, UUID ownerId, Pageable pageable);
	Page<MediaAsset> listPublicByOwnerAndKind(MediaOwnerType ownerType, UUID ownerId, MediaKind kind, Pageable pageable);
	
	
	// silme
	void delete(UUID assetId, UUID actingUserId, MediaOwnerType actingAsType, UUID actingAsId);
	
	// var mi check
	boolean exists(UUID mediaAssetId);
	
	String getPlaybackUrl(UUID mediaAssetId);

	/**
	 * Returns the lightweight visual representation for a public READY asset.
	 * Images and videos prefer their thumbnail; progressive/HLS playback is the
	 * compatibility fallback.
	 */
	String getDisplayUrl(UUID mediaAssetId);
	
	MediaAsset getById(UUID mediaAssetId);

	MediaAsset getPublicReadyById(UUID mediaAssetId);

	/**
	 * Returns a short-lived origin URL for a READY PRIVATE/UNLISTED progressive
	 * asset after verifying that the principal can act for its owner.
	 */
	MediaAccessUrlResponseDto createOwnerAccessUrl(UUID actingUserId, UUID mediaAssetId);

	void validateAssignableMedia(
			UUID actingUserId,
			UUID mediaAssetId,
			MediaOwnerType ownerType,
			UUID ownerId,
			MediaKind expectedKind
	);
	
	Map<UUID, String> getPlaybackUrlMap(List<UUID> mediaAssetIds);
}

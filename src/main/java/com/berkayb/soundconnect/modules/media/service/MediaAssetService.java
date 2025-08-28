package com.berkayb.soundconnect.modules.media.service;


import com.berkayb.soundconnect.modules.media.dto.response.UploadInitResultResponseDto;
import com.berkayb.soundconnect.modules.media.entity.MediaAsset;
import com.berkayb.soundconnect.modules.media.enums.MediaKind;
import com.berkayb.soundconnect.modules.media.enums.MediaOwnerType;
import com.berkayb.soundconnect.modules.media.enums.MediaVisibility;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface MediaAssetService {
	
	// upload baslatma metodu.
	// bu metod istemcinin medya yuklemesine baslamasi icin gerekli bilgileri uretir.
	// presigned url olusturulur(S3/R2 gibi storage'a dogrudan yukleme icin.
	// veritabanina draft(taslak) bir MediaAsset kaydi eklenir.
	// bu sayede dosya henuz gelmeden sistemde varligini tanitmis oluruz.
	UploadInitResultResponseDto initUpload(
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
	MediaAsset completeUpload(UUID assetId);
	
	
	// owner'a ait tum medya varliklarini listeler
	Page<MediaAsset> listByOwner(MediaOwnerType ownerType, UUID ownerId, Pageable pageable);
	
	// owner'a ait bel
	Page<MediaAsset> listByOwnerAndKind(MediaOwnerType ownerType, UUID ownerId, MediaKind kind, Pageable pageable);
	Page<MediaAsset> listPublicByOwner(MediaOwnerType ownerType, UUID ownerId, Pageable pageable);
	Page<MediaAsset> listPublicByOwnerAndKind(MediaOwnerType ownerType, UUID ownerId, MediaKind kind, Pageable pageable);
	
	
	// silme
	void delete(UUID assetId, UUID actingUserId, MediaOwnerType actingAsType, UUID actingAsId);
	
}
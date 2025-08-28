package com.berkayb.soundconnect.modules.media.service;

import com.berkayb.soundconnect.modules.media.dto.response.UploadInitResultResponseDto;
import com.berkayb.soundconnect.modules.media.entity.MediaAsset;
import com.berkayb.soundconnect.modules.media.enums.*;
import com.berkayb.soundconnect.modules.media.repository.MediaAssetRepository;
import com.berkayb.soundconnect.modules.media.storage.MediaPolicy;
import com.berkayb.soundconnect.modules.media.storage.StorageClient;
import com.berkayb.soundconnect.modules.media.storage.TranscodePublisher;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


//------------------------------TAKILDIGIN NOKTADA MediaModule.md DOSYASINA BAK!----------------------------------------


import java.util.UUID;
@Service
@RequiredArgsConstructor
@Slf4j
public class MediaAssetServiceImpl implements MediaAssetService {
	
	private final MediaAssetRepository mediaAssetRepository;
	
	// dosya depolama (S3/R2) islemleri icin storage client
	private final StorageClient storageClient;
	
	// dosya turu, mimeType, boyut namin kurallarini yoneten policy katmani
	private final MediaPolicy mediaPolicy;
	
	// video icin upload sonrasi transcode/HLS islemleri icin arka plan isi(event, kuyruk vs.)
	private final TranscodePublisher transcodePublisher;
	
	/**
	 * Kullanici medya yukleme istegi gonderdiginde bu metod calsiir.
	 * Islem akisi:
	 * Yukleme kurallari check edilir (boyut/mimeType/tur)
	 * DB'ye taslak (draft) bir MediaAsset kaydi olusturulur(status: UPLOADING).
	 * Dosya depolama icin unique bir storage key uretilir (orn: "media/{assetId}-originalname.
	 * Storage servisinden (S3/R2) dosyayi dogrudan yuklemek icin bbir presigned PUT URL alinir.
	 * DB'deki asset kaydi guncellenir (storageKey, sourceUrl atanir.)
	 * Client'a assetId ve yukleme URL'si donulur.
	 */
	@Override
	@Transactional
	public UploadInitResultResponseDto initUpload(MediaOwnerType ownerType, UUID ownerId, MediaKind kind, MediaVisibility visibility, String mimeType, long sizeBytes, String originalFileName) {
		
		// yukleme politikalarini dogrula
		// (mime, boyut, tur kurallarini kontrol et
		mediaPolicy.validate(kind, mimeType, sizeBytes);
		
		// taslak media asset kaydi olustur
		// ilk olarak sadece temel bilgilerle (status: UPLOADING) bir kayit aciyoruz.
		MediaAsset draft = MediaAsset.builder()
				.kind(kind)
				.status(MediaStatus.UPLOADING)
				.visibility(visibility)
				.ownerType(ownerType)
				.mimeType(mimeType)
				.size(sizeBytes)
				.streamingProtocol(
						kind == MediaKind.VIDEO
								? MediaStreamingProtocol.HLS // videolar icin HLS kullaniyoruz
								: MediaStreamingProtocol.PROGRESSIVE // gorsel ve auidio icin progressive
				)
				.build();
		
		// DB'ye taslak kaydi ekle
		// assetId otomatik olarak burada uretilir.
		draft = mediaAssetRepository.save(draft);
		
		// dosya storage key ve url'lerini olustur
		// dosyanin depolamadiki anahtarini uret (orn: media/uuid-filename.mp4)
		String sourceKey = mediaPolicy.buildSourceKey(draft.getId(), originalFileName);
		
		// S3/R2'den client'in dosya yuklemesi icin presigned PUT URL al
		String uploadUrl = storageClient.createPresignedPutUrl(sourceKey, mimeType);
		
		// dosya yuklendikten sonra erisilebilecek (CDN) public url al
		String sourceUrl = storageClient.publicUrl(sourceKey);
		
		// taslak kaydi guncelle (storageKey ve sourceUrl'i set et)
		draft.setStorageKey(sourceKey);
		draft.setSourceUrl(sourceUrl);
		mediaAssetRepository.save(draft);
		
		log.info("[media] initUpload assetId={} ownerType={} ownerId={} kind={} size={} mime={}",
		         draft.getId(), ownerType, ownerId, kind, sizeBytes, mimeType);
		
		// Client'a assetId ve uploadUrl'i gonder
		return UploadInitResultResponseDto.builder()
				.assetId(draft.getId())
				.uploadUrl(uploadUrl)
				.build();
	}
	
	
	@Override
	@Transactional
	public MediaAsset completeUpload(UUID assetId) {
		// Asseti db'den bul yoksa hata firlat
		MediaAsset asset = mediaAssetRepository.findById(assetId)
		                                       .orElseThrow(() -> new SoundConnectException(ErrorType.MEDIA_ASSET_NOT_FOUND));
		
		// eger asset turu video ise status processinge cek(yuklemenin bittigi, islenmenin oldugu surec)
		if (asset.getKind() == MediaKind.VIDEO) {
			asset.setStatus(MediaStatus.PROCESSING);
			mediaAssetRepository.save(asset);
			
			// - HLS transcode islemi icin kuyruga job ekle (async yapilcak)
			String hlsPrefix = mediaPolicy.buildHlsPrefix(asset.getId());
			transcodePublisher.publishVideoHls(asset.getId(), asset.getStorageKey(), hlsPrefix);
			log.info("[media] completeUpload VIDEO queued for HLS assetId={}", assetId);
		} else {
			// gorsel ve audio'da statusu direkt ready yap
			asset.setStatus(MediaStatus.READY);
			asset.setPlaybackUrl(asset.getSourceUrl()); // proressive
			mediaAssetRepository.save(asset);
			
			log.info("[media] completeUpload READY assetId={} kind={}", assetId, asset.getKind());
		}
		return asset;
		
	}
	
	// belirli bir owner'a ait tum assetleri her statu ve gorunlurlukte sayfali olarak dondurur.
	@Override
	@Transactional (readOnly = true)
	public Page<MediaAsset> listByOwner(MediaOwnerType ownerType, UUID ownerId, Pageable pageable) {
		return mediaAssetRepository.findByOwnerTypeAndOwnerId(ownerType, ownerId, pageable);
	}
	
	// belirli bir owner ve belirli bir media turune (auidio, video, image) sahip asset'leri dondurur.
	@Transactional (readOnly = true)
	@Override
	public Page<MediaAsset> listByOwnerAndKind(MediaOwnerType ownerType, UUID ownerId, MediaKind kind, Pageable pageable) {
		return mediaAssetRepository.findByOwnerTypeAndOwnerIdAndKind(ownerType, ownerId, kind, pageable);
	}
	
	// sadece public ve ready assetleri dondur
	@Transactional (readOnly = true)
	@Override
	public Page<MediaAsset> listPublicByOwner(MediaOwnerType ownerType, UUID ownerId, Pageable pageable) {
		return mediaAssetRepository.findByOwnerTypeAndOwnerIdAndVisibilityAndStatus(
				ownerType, ownerId, MediaVisibility.PUBLIC, MediaStatus.READY, pageable
		);
	}
	
	// belirli bir ownerin belirli bir turdeki public ve ready assetlerini dondurur.
	@Transactional (readOnly = true)
	@Override
	public Page<MediaAsset> listPublicByOwnerAndKind(MediaOwnerType ownerType, UUID ownerId, MediaKind kind, Pageable pageable) {
		return mediaAssetRepository.findByOwnerTypeAndOwnerIdAndKindAndVisibilityAndStatus(
				ownerType, ownerId, kind, MediaVisibility.PUBLIC, MediaStatus.READY, pageable);
	}
	
	
	@Override
	@Transactional
	public void delete(UUID assetId, UUID actingUserId, MediaOwnerType actingAsType, UUID actingAsId) {
	MediaAsset asset = mediaAssetRepository.findById(assetId)
			.orElseThrow(() -> new SoundConnectException(ErrorType.MEDIA_ASSET_NOT_FOUND));
	
	boolean ownerMatch = asset.getOwnerType() == actingAsType && asset.getOwnerId().equals(actingAsId);
	
	if (!ownerMatch) {
		log.warn("[media] delete denied assetId={} actingAsType={} actingAsId={}", assetId, actingAsType, actingAsId);
		throw new SoundConnectException(ErrorType.MEDIA_ASSET_DELETE_FORBIDDEN);
	}
	try {
		storageClient.deleteObject(asset.getStorageKey());
		if (asset.getKind() == MediaKind.VIDEO) {
			storageClient.deleteFolder(mediaPolicy.buildHlsPrefix(asset.getId()));
		}
	} catch (Exception e) {
		log.error("[media] storage delete failed assetId={} err={}", assetId, e.getMessage());
	}
	mediaAssetRepository.deleteById(assetId);
		log.info("[media] deleted assetId={} by actingAsType={} actingAsId={}", assetId, actingAsType, actingAsId);
	}
}
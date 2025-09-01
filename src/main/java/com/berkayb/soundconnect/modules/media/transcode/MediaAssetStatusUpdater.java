package com.berkayb.soundconnect.modules.media.transcode;

import com.berkayb.soundconnect.modules.media.entity.MediaAsset;
import com.berkayb.soundconnect.modules.media.enums.MediaKind;
import com.berkayb.soundconnect.modules.media.enums.MediaStatus;
import com.berkayb.soundconnect.modules.media.enums.MediaStreamingProtocol;
import com.berkayb.soundconnect.modules.media.repository.MediaAssetRepository;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.UUID;

/**
 * Transcode sonrasi MediaAsset durum/alan guncellemeleri
 * SUCCESS: HLS master URL + thumbnail + opsiyonel olarak metadata set edilir, status-READY
 * FAILED: status=FAILED
 * PROCESSING: uzun suren isler icin ara durum.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MediaAssetStatusUpdater {
	private final MediaAssetRepository mediaAssetRepository;
	
	// uzun suren is baslarken processing'e al
	@Transactional
	public MediaAsset markProcessing(UUID assetId) {
		MediaAsset asset = getOrThrow(assetId);
		
		// Terminal durumsa (READY/FAILED) elleme
		if (asset.getStatus().isTerminal()) {
			log.info("[asset] markProcessing ignored. terminal state assetId={} status={}", assetId, asset.getStatus());
			return asset;
		}
		asset.setStatus(MediaStatus.PROCESSING);
		// video isi icinde cagrilsa sorun olmaz ama biz video olarak kullanicaz
		return mediaAssetRepository.save(asset);
	}
	
	
	/**
	 * basarili HLS uretimi sonrasi:
	 * - playbackUrl = master.m3u8 (CDN URL)
	 * - thumbnailUrl (varsa)
	 * - streamingProtocol = HLS
	 * - (opsiyonel) duration/width/height
	 * - status =  READY
	 */
	@Transactional
	public MediaAsset markReadyHls(
			UUID assetId,
			String playbackUrl,
			String thumbnailUrl,
			Integer durationSeconds,
			Integer width,
			Integer height
	) {
		MediaAsset asset = getOrThrow(assetId);
		
		// Terminal durumsa idempotent davran
		if (asset.getStatus().isTerminal()) {
			log.info("[asset] markReadyHls ignored; terminal state assetId={} status={}", assetId, asset.getStatus());
			return asset;
		}
		
		if (!StringUtils.hasText(playbackUrl)){
			throw new SoundConnectException(ErrorType.INTERNAL_ERROR);
		}
		if (asset.getKind() != MediaKind.VIDEO) {
			// HLS video içindir; yine de alanları setleyip loglayarak ilerleyebiliriz.
			log.warn("[asset] markReadyHls on non-video assetId={} kind={}", assetId, asset.getKind());
		}
		// alanlari doldur
		asset.setPlaybackUrl(playbackUrl);
		if (StringUtils.hasText(thumbnailUrl)){
			asset.setThumbnailUrl(thumbnailUrl);
		}
		if (durationSeconds != null && durationSeconds > 0) {
			asset.setDurationSeconds(durationSeconds);
		}
		if (width != null && width > 0) {
			asset.setWidth(width);
		}
		if (height != null && height > 0) {
			asset.setHeight(height);
		}
		
		asset.setStreamingProtocol(MediaStreamingProtocol.HLS);
		asset.setStatus(MediaStatus.READY);
		
		MediaAsset saved = mediaAssetRepository.save(asset);
		log.info("[asset] READY (HLS) assetId={} playbackUrl={}", assetId, playbackUrl);
		return saved;
	}
	
	@Transactional
	public MediaAsset markFailed(UUID assetId) {
		MediaAsset asset = getOrThrow(assetId);
		
		if (asset.getStatus().isTerminal()) {
			log.info("[asset] markFailed ignored; terminal state assetId={} status={}", assetId, asset.getStatus());
			return asset;
		}
		asset.setStatus(MediaStatus.FAILED);
		MediaAsset saved = mediaAssetRepository.save(asset);
		log.warn("[asset] FAILED assetId={}", assetId);
		return saved;
	}
	
	// ---- yardimci methodlar ----
	private MediaAsset getOrThrow(UUID assetId) {
		return mediaAssetRepository.findById(assetId)
		                           .orElseThrow(() -> new SoundConnectException(ErrorType.MEDIA_ASSET_NOT_FOUND));
	}
	
}
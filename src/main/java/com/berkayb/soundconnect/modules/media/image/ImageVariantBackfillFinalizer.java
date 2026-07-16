package com.berkayb.soundconnect.modules.media.image;

import com.berkayb.soundconnect.modules.media.entity.MediaAsset;
import com.berkayb.soundconnect.modules.media.enums.MediaKind;
import com.berkayb.soundconnect.modules.media.enums.MediaStatus;
import com.berkayb.soundconnect.modules.media.enums.MediaVisibility;
import com.berkayb.soundconnect.modules.media.repository.MediaAssetRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.UUID;
import java.util.Objects;

@Service
@ConditionalOnImageVariantWorker
@RequiredArgsConstructor
public class ImageVariantBackfillFinalizer {

	private final MediaAssetRepository mediaAssetRepository;

	@Transactional
	public boolean attachIfStillEligible(
			UUID assetId,
			String expectedSourceKey,
			ImageThumbnailResult result
	) {
		int attached = mediaAssetRepository.attachImageThumbnailIfEligible(
				assetId,
				MediaKind.IMAGE,
				MediaVisibility.PUBLIC,
				MediaStatus.READY,
				expectedSourceKey,
				result.thumbnailUrl(),
				result.sourceWidth(),
				result.sourceHeight()
		);
		if (attached == 1) {
			return true;
		}

		MediaAsset asset = mediaAssetRepository.findById(assetId).orElse(null);
		if (isSameVariantAlreadyAttached(asset, expectedSourceKey, result)) {
			// Cross-node duplicate work is harmless. Returning success prevents the
			// losing worker from deleting the deterministic object now referenced by
			// the winning transaction.
			return true;
		}
		return false;
	}

	private static boolean isSameVariantAlreadyAttached(
			MediaAsset asset,
			String expectedSourceKey,
			ImageThumbnailResult result
	) {
		return asset != null
				&& asset.getKind() == MediaKind.IMAGE
				&& asset.getVisibility() == MediaVisibility.PUBLIC
				&& asset.getStatus() == MediaStatus.READY
				&& expectedSourceKey.equals(asset.getStorageKey())
				&& Objects.equals(result.thumbnailUrl(), asset.getThumbnailUrl());
	}

	static boolean isEligible(MediaAsset asset) {
		return asset != null
				&& asset.getKind() == MediaKind.IMAGE
				&& asset.getVisibility() == MediaVisibility.PUBLIC
				&& asset.getStatus() == MediaStatus.READY
				&& !StringUtils.hasText(asset.getThumbnailUrl())
				&& StringUtils.hasText(asset.getStorageKey());
	}
}

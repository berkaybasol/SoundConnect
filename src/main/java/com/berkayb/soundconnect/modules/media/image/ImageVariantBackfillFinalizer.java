package com.berkayb.soundconnect.modules.media.image;

import com.berkayb.soundconnect.modules.media.entity.MediaAsset;
import com.berkayb.soundconnect.modules.media.enums.MediaKind;
import com.berkayb.soundconnect.modules.media.enums.MediaStatus;
import com.berkayb.soundconnect.modules.media.enums.MediaVisibility;
import com.berkayb.soundconnect.modules.media.repository.MediaAssetRepository;
import com.berkayb.soundconnect.modules.media.storage.StorageObjectKeys;
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
		boolean protectedImage = StorageObjectKeys.isPrivateVerified(expectedSourceKey);
		if (protectedImage && (result.thumbnailUrl() != null
				|| !ImageThumbnailService.protectedThumbnailKeyFor(expectedSourceKey).equals(result.thumbnailKey()))) {
			throw new IllegalArgumentException("Protected thumbnail must belong to the immutable source");
		}
		int attached = protectedImage
				? mediaAssetRepository.attachProtectedImageThumbnailIfEligible(
						assetId, expectedSourceKey, result.thumbnailKey(), result.sourceWidth(), result.sourceHeight())
				: mediaAssetRepository.attachImageThumbnailIfEligible(
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
				&& (asset.getVisibility() == MediaVisibility.PUBLIC
						&& !StorageObjectKeys.isProtected(expectedSourceKey)
						&& Objects.equals(result.thumbnailUrl(), asset.getThumbnailUrl())
					|| asset.getVisibility() == MediaVisibility.PRIVATE
						&& StorageObjectKeys.isPrivateVerified(expectedSourceKey)
						&& Objects.equals(result.thumbnailKey(), asset.getThumbnailStorageKey()))
				&& asset.getStatus() == MediaStatus.READY
				&& expectedSourceKey.equals(asset.getStorageKey());
	}

	static boolean isEligible(MediaAsset asset) {
		return asset != null
				&& asset.getKind() == MediaKind.IMAGE
				&& asset.getStatus() == MediaStatus.READY
				&& StringUtils.hasText(asset.getStorageKey())
				&& (asset.getVisibility() == MediaVisibility.PUBLIC
						&& !StorageObjectKeys.isProtected(asset.getStorageKey())
						&& !StringUtils.hasText(asset.getThumbnailUrl())
						|| asset.getVisibility() == MediaVisibility.PRIVATE
						&& StorageObjectKeys.isPrivateVerified(asset.getStorageKey())
						&& asset.getThumbnailStorageKey() == null);
	}
}

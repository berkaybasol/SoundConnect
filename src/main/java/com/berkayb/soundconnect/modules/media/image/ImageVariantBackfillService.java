package com.berkayb.soundconnect.modules.media.image;

import com.berkayb.soundconnect.modules.media.entity.MediaAsset;
import com.berkayb.soundconnect.modules.media.repository.MediaAssetRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Objects;
import java.util.UUID;

@Service
@ConditionalOnImageVariantWorker
@RequiredArgsConstructor
public class ImageVariantBackfillService {

	private final MediaAssetRepository mediaAssetRepository;
	private final ImageThumbnailService imageThumbnailService;
	private final ImageVariantBackfillFinalizer finalizer;

	public boolean backfillOne(UUID assetId) throws IOException, InterruptedException {
		// Spring Data closes this read transaction before any storage/process I/O.
		MediaAsset snapshot = mediaAssetRepository.findById(assetId).orElse(null);
		if (!ImageVariantBackfillFinalizer.isEligible(snapshot)) {
			return false;
		}
		String expectedSourceKey = snapshot.getStorageKey();
		ImageThumbnailResult result = Objects.requireNonNull(
				imageThumbnailService.generateAndStoreDetached(snapshot.getId(), expectedSourceKey),
				"Image thumbnail generation returned no result");
		// The finalizer owns the only pessimistic lock, for the brief DB recheck and
		// update after all slow work has completed.
		// Do not delete on an ambiguous database exception: another node may have
		// committed the same deterministic URL while this transaction lost its
		// connection. READY + missing URL remains a durable retry marker, while an
		// asset delete always removes the deterministic derivative unconditionally.
		boolean attached = finalizer.attachIfStillEligible(assetId, expectedSourceKey, result);
		if (!attached) {
			// Generation raced with deletion, replacement, or another state change.
			// Never leave its deterministic public derivative orphaned.
			imageThumbnailService.deleteStoredVariant(result.thumbnailKey());
		}
		return attached;
	}
}

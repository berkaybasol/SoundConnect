package com.berkayb.soundconnect.modules.media.image;

import com.berkayb.soundconnect.modules.media.enums.MediaKind;
import com.berkayb.soundconnect.modules.media.enums.MediaStatus;
import com.berkayb.soundconnect.modules.media.enums.MediaVisibility;
import com.berkayb.soundconnect.modules.media.repository.MediaAssetRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@RequiredArgsConstructor
@ConditionalOnImageVariantWorker
@ConditionalOnProperty(
		prefix = "media.image-variants.backfill",
		name = "enabled",
		havingValue = "true",
        matchIfMissing = false
)
@Slf4j
public class ImageVariantBackfillScheduler {

	private final MediaAssetRepository mediaAssetRepository;
	private final ImageVariantJobDispatcher dispatcher;
	private final MediaImageVariantProperties properties;
	private final AtomicInteger nextPage = new AtomicInteger();

	@Scheduled(
			initialDelayString = "${media.image-variants.backfill.initial-delay:PT2M}",
			fixedDelayString = "${media.image-variants.backfill.fixed-delay:PT10M}"
	)
	public void backfillMissingThumbnails() {
		int batchSize = Math.max(1, Math.min(properties.getBackfill().getBatchSize(), 500));
		int pageIndex = Math.max(0, nextPage.get());
		var candidateIds = findCandidates(pageIndex, batchSize);
		if (candidateIds.isEmpty() && pageIndex > 0) {
			// A preceding pass may have completed enough rows to shrink the result
			// set. Wrap safely instead of letting an offset cursor run past the end.
			pageIndex = 0;
			nextPage.set(0);
			candidateIds = findCandidates(pageIndex, batchSize);
		}

		int submitted = 0;
		for (UUID assetId : candidateIds) {
			if (dispatcher.submit(assetId)) {
				submitted++;
			} else {
				log.warn("[media-image] thumbnail worker queue saturated submitted={} candidates={}",
						submitted, candidateIds.size());
				break;
			}
		}

		// Rotate full pages even if their jobs fail. Missing-thumbnail state is
		// durable, and wrapping later retries those rows without starving newer
		// candidates behind a permanently malformed legacy image.
		if (candidateIds.isEmpty() || candidateIds.size() < batchSize) {
			nextPage.set(0);
		} else {
			nextPage.set(pageIndex + 1);
		}
		if (!candidateIds.isEmpty()) {
			log.info("[media-image] thumbnail backfill page={} candidates={} submitted={}",
					pageIndex, candidateIds.size(), submitted);
		}
	}

	private java.util.List<UUID> findCandidates(int pageIndex, int batchSize) {
		return mediaAssetRepository.findIdsMissingThumbnail(
				MediaKind.IMAGE,
				MediaVisibility.PUBLIC,
				MediaStatus.READY,
				PageRequest.of(pageIndex, batchSize)
		);
	}
}

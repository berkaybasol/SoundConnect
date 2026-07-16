package com.berkayb.soundconnect.modules.media.deletion;

import com.berkayb.soundconnect.modules.media.enums.MediaStatus;
import com.berkayb.soundconnect.modules.media.repository.MediaAssetRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

@Component
@RequiredArgsConstructor
@Slf4j
public class MediaDeletionRecoveryScheduler {

	private final MediaAssetRepository mediaAssetRepository;
	private final MediaDeletionDispatcher dispatcher;
	private final MediaDeletionProperties properties;
	private final AtomicInteger nextPage = new AtomicInteger();

	@Scheduled(
			initialDelayString = "${media.deletion.initial-delay-ms:30000}",
			fixedDelayString = "${media.deletion.fixed-delay-ms:60000}"
	)
	public void recover() {
		if (!properties.isEnabled()) return;
		int batchSize = Math.max(1, Math.min(properties.getBatchSize(), 500));
		int page = Math.max(0, nextPage.get());
		var candidates = findPage(page, batchSize);
		if (candidates.isEmpty() && page > 0) {
			page = 0;
			nextPage.set(0);
			candidates = findPage(page, batchSize);
		}

		int submitted = 0;
		boolean saturated = false;
		for (var asset : candidates) {
			if (!dispatcher.submit(asset.getId())) {
				saturated = true;
				break;
			}
			submitted++;
		}
		if (saturated) {
			nextPage.set(page);
			log.warn("[media-delete] recovery queue saturated page={} submitted={}", page, submitted);
		} else if (candidates.size() < batchSize) {
			nextPage.set(0);
		} else {
			nextPage.set(page + 1);
		}
	}

	private java.util.List<com.berkayb.soundconnect.modules.media.entity.MediaAsset> findPage(
			int page, int batchSize
	) {
		return mediaAssetRepository.findByStatusOrderByUpdatedAtAsc(
				MediaStatus.DELETION_PENDING, PageRequest.of(page, batchSize));
	}
}

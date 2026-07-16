package com.berkayb.soundconnect.modules.media.image;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Bounded, in-process executor for idempotent image-variant jobs. */
@Component
@ConditionalOnImageVariantWorker
@Slf4j
public class ImageVariantJobDispatcher {

	private final ImageVariantBackfillService backfillService;
	private final TaskExecutor executor;
	private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();

	public ImageVariantJobDispatcher(
			ImageVariantBackfillService backfillService,
			@Qualifier("imageVariantExecutor") TaskExecutor executor
	) {
		this.backfillService = backfillService;
		this.executor = executor;
	}

	/**
	 * @return true when the id is queued or already in flight; false when the
	 * bounded queue is saturated and the durable scan must retry it later.
	 */
	public boolean submit(UUID assetId) {
		if (assetId == null) return false;
		if (!inFlight.add(assetId)) return true;
		try {
			executor.execute(() -> process(assetId));
			return true;
		} catch (TaskRejectedException saturated) {
			inFlight.remove(assetId);
			return false;
		}
	}

	private void process(UUID assetId) {
		try {
			backfillService.backfillOne(assetId);
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			log.warn("[media-image] thumbnail worker interrupted assetId={}", assetId);
		} catch (IOException | RuntimeException failure) {
			// No state transition is needed: READY + missing thumbnailUrl is the
			// durable retry marker consumed by the scheduled recovery scan.
			log.warn("[media-image] thumbnail worker deferred assetId={} exceptionType={}",
					assetId, failure.getClass().getSimpleName());
		} finally {
			inFlight.remove(assetId);
		}
	}
}

package com.berkayb.soundconnect.modules.media.abuse;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Dispatches durable upload cleanup without blocking request or scheduler threads. */
@Component
@Slf4j
public class MediaUploadCleanupDispatcher {

	private final MediaUploadCleanupWorker cleanupWorker;
	private final TaskExecutor executor;
	private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();

	public MediaUploadCleanupDispatcher(
			MediaUploadCleanupWorker cleanupWorker,
			@Qualifier("mediaUploadCleanupExecutor") TaskExecutor executor
	) {
		this.cleanupWorker = cleanupWorker;
		this.executor = executor;
	}

	/** @return false only when bounded capacity is exhausted. */
	public boolean submit(UUID assetId) {
		if (assetId == null) return true;
		if (!inFlight.add(assetId)) return true;
		try {
			executor.execute(() -> {
				try {
					cleanupWorker.clean(assetId);
				} finally {
					inFlight.remove(assetId);
				}
			});
			return true;
		} catch (TaskRejectedException saturated) {
			inFlight.remove(assetId);
			log.warn("[media] upload cleanup queue saturated; durable work retained");
			return false;
		} catch (RuntimeException submissionFailure) {
			inFlight.remove(assetId);
			throw submissionFailure;
		}
	}
}

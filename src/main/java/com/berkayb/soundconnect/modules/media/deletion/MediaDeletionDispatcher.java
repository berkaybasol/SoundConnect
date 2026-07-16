package com.berkayb.soundconnect.modules.media.deletion;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Slf4j
public class MediaDeletionDispatcher {

	private final MediaDeletionWorker worker;
	private final TaskExecutor executor;
	private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();

	public MediaDeletionDispatcher(
			MediaDeletionWorker worker,
			@Qualifier("mediaDeletionExecutor") TaskExecutor executor
	) {
		this.worker = worker;
		this.executor = executor;
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onRequested(MediaDeletionRequestedEvent event) {
		if (event != null && event.assetId() != null && !submit(event.assetId())) {
			log.warn("[media-delete] queue saturated; durable retry retained assetId={}", event.assetId());
		}
	}

	/** True means queued/already running; false leaves durable recovery responsible. */
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
			worker.delete(assetId);
		} catch (RuntimeException failure) {
			// DELETION_PENDING remains the durable retry marker.
			log.warn("[media-delete] worker deferred assetId={} exceptionType={}",
					assetId, failure.getClass().getSimpleName());
		} finally {
			inFlight.remove(assetId);
		}
	}
}

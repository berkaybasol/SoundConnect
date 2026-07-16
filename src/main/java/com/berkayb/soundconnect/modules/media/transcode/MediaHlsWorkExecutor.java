package com.berkayb.soundconnect.modules.media.transcode;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;

/**
 * Zero-queue native-work admission. Rabbit listeners reserve a real worker slot
 * before claiming a row, hand the durable claim off, ACK the signal, and return.
 */
@Component
@Slf4j
public class MediaHlsWorkExecutor {
	private final VideoHlsWorkflow workflow;
	private final TaskExecutor executor;
	private final Semaphore admission;

	public MediaHlsWorkExecutor(
			VideoHlsWorkflow workflow,
			@Qualifier("mediaHlsWorkerTaskExecutor") TaskExecutor executor,
			@Value("${media.transcode.worker-threads:1}") int workerThreads
	) {
		if (workerThreads != 1) {
			throw new IllegalStateException(
					"media.transcode.worker-threads must remain 1 until temp-disk admission is weighted");
		}
		this.workflow = workflow;
		this.executor = executor;
		this.admission = new Semaphore(1, true);
	}

	public boolean tryReserve() {
		return admission.tryAcquire();
	}

	public void releaseReservation() {
		admission.release();
	}

	/**
	 * Consumes a reservation. A rejected handoff releases it; the caller must move
	 * the exact claim into durable retry cleanup before acknowledging Rabbit.
	 */
	public boolean submitReserved(VideoHlsWorkflow.ClaimedVideoHlsWork work) {
		try {
			executor.execute(() -> {
				try {
					workflow.processClaimed(work);
				} catch (Exception failure) {
					log.error("[media-transcode] background HLS work failed assetId={} attempt={} type={}",
							work.assetId(), work.attemptNumber(),
							failure.getClass().getSimpleName());
				} finally {
					admission.release();
				}
			});
			return true;
		} catch (TaskRejectedException rejected) {
			admission.release();
			log.warn("[media-transcode] reserved HLS worker rejected handoff; caller must return claim");
			return false;
		} catch (RuntimeException submissionFailure) {
			admission.release();
			throw submissionFailure;
		}
	}

	int availablePermits() {
		return admission.availablePermits();
	}
}

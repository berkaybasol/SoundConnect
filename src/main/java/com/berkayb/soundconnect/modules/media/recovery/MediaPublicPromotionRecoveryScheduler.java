package com.berkayb.soundconnect.modules.media.recovery;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Bounded rotating sweep. The cursor advances only past accepted work, wraps
 * after a complete pass and does not pin the queue behind one failing object.
 */
@Component
@Slf4j
public class MediaPublicPromotionRecoveryScheduler {

	private final MediaPublicPromotionRecoveryRepository repository;
	private final MediaPublicPromotionRecoveryWorker worker;
	private final MediaPublicPromotionRecoveryProperties properties;
	private final TaskExecutor executor;
	private int recoveryOffset;

	public MediaPublicPromotionRecoveryScheduler(
			MediaPublicPromotionRecoveryRepository repository,
			MediaPublicPromotionRecoveryWorker worker,
			MediaPublicPromotionRecoveryProperties properties,
			@Qualifier("mediaPublicPromotionRecoveryExecutor") TaskExecutor executor
	) {
		this.repository = repository;
		this.worker = worker;
		this.properties = properties;
		this.executor = executor;
	}

	@Scheduled(
			initialDelayString = "${media.public-promotion-recovery.initial-delay-ms:600000}",
			fixedDelayString = "${media.public-promotion-recovery.fixed-delay-ms:900000}"
	)
	public synchronized void recover() {
		if (!properties.isEnabled()) return;

		int batchSize = properties.getBatchSize();
		var candidates = repository.findReadyBatch(recoveryOffset, batchSize);
		if (candidates.isEmpty()) {
			recoveryOffset = 0;
			return;
		}

		int submitted = 0;
		for (var candidate : candidates) {
			try {
				executor.execute(() -> worker.recover(candidate));
				submitted++;
			} catch (TaskRejectedException saturated) {
				log.warn("[media] public promotion recovery queue is saturated; cursor retained");
				break;
			}
		}

		recoveryOffset += submitted;
		if (submitted == candidates.size() && candidates.size() < batchSize) {
			recoveryOffset = 0;
		}
		if (submitted > 0) {
			log.info("[media] public promotion recovery submitted={} nextOffset={}",
					submitted, recoveryOffset);
		}
	}
}

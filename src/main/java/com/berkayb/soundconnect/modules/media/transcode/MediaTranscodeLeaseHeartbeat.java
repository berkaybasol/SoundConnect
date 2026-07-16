package com.berkayb.soundconnect.modules.media.transcode;

import com.berkayb.soundconnect.modules.media.transcode.config.MediaTranscodeLeaseProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/** Renews attempt ownership independently while native and storage calls block. */
@Component
@Slf4j
public class MediaTranscodeLeaseHeartbeat {

	private final MediaAssetStatusUpdater statusUpdater;
	private final MediaTranscodeLeaseProperties properties;
	private final TaskScheduler scheduler;

	public MediaTranscodeLeaseHeartbeat(
			MediaAssetStatusUpdater statusUpdater,
			MediaTranscodeLeaseProperties properties,
			@Qualifier("mediaTranscodeLeaseScheduler") TaskScheduler scheduler
	) {
		this.statusUpdater = statusUpdater;
		this.properties = properties;
		this.scheduler = scheduler;
	}

	public TranscodeLease start(UUID assetId, UUID attemptToken) {
		HeartbeatLease lease = new HeartbeatLease(assetId, attemptToken);
		ScheduledFuture<?> future = scheduler.scheduleWithFixedDelay(
				lease::renewInBackground,
				Instant.now().plus(properties.getHeartbeatInterval()),
				properties.getHeartbeatInterval()
		);
		if (future == null) {
			throw new IllegalStateException("Transcode lease heartbeat could not be scheduled");
		}
		lease.attach(future);
		return lease;
	}

	private final class HeartbeatLease implements TranscodeLease {
		private final UUID assetId;
		private final UUID attemptToken;
		private final AtomicBoolean owned = new AtomicBoolean(true);
		private volatile ScheduledFuture<?> future;

		private HeartbeatLease(UUID assetId, UUID attemptToken) {
			this.assetId = assetId;
			this.attemptToken = attemptToken;
		}

		private void attach(ScheduledFuture<?> future) {
			this.future = future;
		}

		private void renewInBackground() {
			if (!owned.get()) return;
			try {
				renewOrLose();
			} catch (TranscodeLeaseLostException lost) {
				log.warn("[media-transcode] lease heartbeat stopped assetId={}", assetId);
			}
		}

		@Override
		public synchronized void checkpoint() {
			if (!owned.get()) throw new TranscodeLeaseLostException();
			renewOrLose();
		}

		private synchronized void renewOrLose() {
			if (!owned.get()) throw new TranscodeLeaseLostException();
			try {
				if (!statusUpdater.renewTranscodeLease(assetId, attemptToken)) {
					owned.set(false);
					throw new TranscodeLeaseLostException();
				}
			} catch (TranscodeLeaseLostException lost) {
				throw lost;
			} catch (RuntimeException renewalFailure) {
				owned.set(false);
				throw new TranscodeLeaseLostException(renewalFailure);
			}
		}

		@Override
		public void close() {
			owned.set(false);
			ScheduledFuture<?> scheduled = future;
			if (scheduled != null) scheduled.cancel(false);
		}
	}
}

package com.berkayb.soundconnect.modules.media.transcode.validation;

import com.berkayb.soundconnect.modules.media.telemetry.MediaWorkerMetrics;
import org.springframework.beans.factory.annotation.Autowired;
import com.berkayb.soundconnect.modules.media.transcode.config.TranscodeProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Byte-weighted, process-wide admission for temporary transcode workspaces.
 * Every job reserves its configured worst-case working set before downloading
 * the source and holds it until upload/finalization and local cleanup finish.
 */
@Component
@Slf4j
public class TranscodeTempBudgetManager {
	private final Object monitor = new Object();
	private final long totalBudgetBytes;
	private final long perJobReservationBytes;
	private final long acquireTimeoutNanos;
	private long reservedBytes;
	private MediaWorkerMetrics telemetry = MediaWorkerMetrics.noop();
	@Autowired void setTelemetry(MediaWorkerMetrics telemetry) {
		this.telemetry = telemetry;
		telemetry.bindTempBudget(this::reservedBytes, totalBudgetBytes);
	}

	public TranscodeTempBudgetManager(TranscodeProperties properties) {
		this.totalBudgetBytes = properties.getGlobalTempBudgetBytes();
		this.perJobReservationBytes = properties.getMaxTempWorkBytes();
		this.acquireTimeoutNanos = TimeUnit.SECONDS.toNanos(
				properties.getTempBudgetAcquireTimeoutSeconds());
		if (perJobReservationBytes <= 0 || totalBudgetBytes < perJobReservationBytes) {
			throw new IllegalStateException(
					"global transcode temp budget must cover one maximum job reservation");
		}
	}

	public Reservation reserveMaxWorkBudget()
			throws InterruptedException, TranscodeCapacityUnavailableException {
		long started = System.nanoTime();
		long deadline = started + acquireTimeoutNanos;
		MediaWorkerMetrics.TempOutcome outcome = null;
		try {
			synchronized (monitor) {
				while (wouldExceedBudget()) {
					long remainingNanos = deadline - System.nanoTime();
					if (remainingNanos <= 0) {
						outcome = MediaWorkerMetrics.TempOutcome.TIMEOUT;
						throw new TranscodeCapacityUnavailableException(
								"timed out waiting for temporary transcode capacity");
					}
					TimeUnit.NANOSECONDS.timedWait(monitor, remainingNanos);
				}
				reservedBytes = Math.addExact(reservedBytes, perJobReservationBytes);
				log.debug("[transcode-budget] admitted reservedBytes={} totalBytes={}",
						reservedBytes, totalBudgetBytes);
				outcome = MediaWorkerMetrics.TempOutcome.ACQUIRED;
				return new Reservation(this, perJobReservationBytes);
			}
		} catch (InterruptedException interrupted) {
			outcome = MediaWorkerMetrics.TempOutcome.INTERRUPTED;
			throw interrupted;
		} finally {
			if (outcome != null) telemetry.tempWait(outcome, started);
		}
	}

	private boolean wouldExceedBudget() {
		return reservedBytes > totalBudgetBytes - perJobReservationBytes;
	}

	private void release(long bytes) {
		synchronized (monitor) {
			reservedBytes -= bytes;
			if (reservedBytes < 0) {
				reservedBytes = 0;
				throw new IllegalStateException("transcode temp reservation underflow");
			}
			monitor.notifyAll();
			log.debug("[transcode-budget] released reservedBytes={} totalBytes={}",
					reservedBytes, totalBudgetBytes);
		}
	}

	long reservedBytes() {
		synchronized (monitor) {
			return reservedBytes;
		}
	}

	public static final class Reservation implements AutoCloseable {
		private final TranscodeTempBudgetManager owner;
		private final long bytes;
		private final AtomicBoolean closed = new AtomicBoolean();

		private Reservation(TranscodeTempBudgetManager owner, long bytes) {
			this.owner = owner;
			this.bytes = bytes;
		}

		@Override
		public void close() {
			if (closed.compareAndSet(false, true)) owner.release(bytes);
		}
	}
}

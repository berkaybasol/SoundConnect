package com.berkayb.soundconnect.modules.media.transcode.validation;

import com.berkayb.soundconnect.modules.media.transcode.config.TranscodeProperties;
import com.berkayb.soundconnect.modules.media.telemetry.MediaWorkerMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TranscodeTempBudgetManagerTest {

	@Test
	void reservationIsByteWeightedAndIdempotentlyReleased() throws Exception {
		TranscodeProperties properties = new TranscodeProperties();
		properties.setMaxTempWorkBytes(1024);
		properties.setGlobalTempBudgetBytes(1024);
		TranscodeTempBudgetManager manager = new TranscodeTempBudgetManager(properties);

		TranscodeTempBudgetManager.Reservation reservation = manager.reserveMaxWorkBudget();
		assertThat(manager.reservedBytes()).isEqualTo(1024);

		reservation.close();
		reservation.close();
		assertThat(manager.reservedBytes()).isZero();
	}

	@Test
	void refusesConfigurationThatCannotAdmitOneWorstCaseJob() {
		TranscodeProperties properties = new TranscodeProperties();
		properties.setMaxTempWorkBytes(2048);
		properties.setGlobalTempBudgetBytes(1024);

		assertThatThrownBy(() -> new TranscodeTempBudgetManager(properties))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("cover one maximum job");
	}

	@Test void telemetryReadsRealReservationsAndTimeoutDoesNotConsumeCapacity() throws Exception {
		var registry = new SimpleMeterRegistry();
		try {
			var properties = new TranscodeProperties();
			properties.setMaxTempWorkBytes(1024);
			properties.setGlobalTempBudgetBytes(1024);
			properties.setTempBudgetAcquireTimeoutSeconds(0);
			var manager = new TranscodeTempBudgetManager(properties);
			manager.setTelemetry(new MediaWorkerMetrics(registry));
			assertThat(registry.get("soundconnect.worker.temp.reserved").gauge().value()).isZero();
			assertThat(registry.get("soundconnect.worker.temp.limit").gauge().value()).isEqualTo(1024);
			var reservation = manager.reserveMaxWorkBudget();
			assertThat(registry.get("soundconnect.worker.temp.reserved").gauge().value()).isEqualTo(1024);
			assertThatThrownBy(manager::reserveMaxWorkBudget).isInstanceOf(TranscodeCapacityUnavailableException.class);
			assertThat(manager.reservedBytes()).isEqualTo(1024);
			reservation.close();
			reservation.close();
			assertThat(registry.get("soundconnect.worker.temp.reserved").gauge().value()).isZero();
			assertThat(registry.get("soundconnect.worker.temp.wait").tag("outcome", "acquired").timer().count()).isEqualTo(1);
			assertThat(registry.get("soundconnect.worker.temp.wait").tag("outcome", "timeout").timer().count()).isEqualTo(1);
		} finally { registry.close(); }
	}

	@Test void interruptedWaitPreservesOwnerReservationAndRecordsOnlyTheInterruptedAttempt() throws Exception {
		var registry = new SimpleMeterRegistry();
		Thread waiter = null;
		TranscodeTempBudgetManager.Reservation reservation = null;
		try {
			var properties = new TranscodeProperties();
			properties.setMaxTempWorkBytes(1024);
			properties.setGlobalTempBudgetBytes(1024);
			var manager = new TranscodeTempBudgetManager(properties);
			manager.setTelemetry(new MediaWorkerMetrics(registry));
			reservation = manager.reserveMaxWorkBudget();
			var entered = new CountDownLatch(1);
			var interrupted = new AtomicBoolean();
			waiter = new Thread(() -> {
				entered.countDown();
				try (var unexpected = manager.reserveMaxWorkBudget()) { throw new AssertionError("capacity was already owned"); }
				catch (InterruptedException expected) { interrupted.set(true); }
				catch (TranscodeCapacityUnavailableException failure) { throw new AssertionError(failure); }
			});
			waiter.start();
			assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
			waiter.interrupt();
			waiter.join(5000);
			assertThat(waiter.isAlive()).isFalse();
			assertThat(interrupted).isTrue();
			assertThat(manager.reservedBytes()).isEqualTo(1024);
			assertThat(registry.get("soundconnect.worker.temp.wait").tag("outcome", "interrupted").timer().count()).isEqualTo(1);
		} finally {
			if (waiter != null) { waiter.interrupt(); waiter.join(5000); }
			if (reservation != null) reservation.close();
			registry.close();
		}
	}
}

package com.berkayb.soundconnect.modules.media.telemetry;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static com.berkayb.soundconnect.modules.media.telemetry.MediaWorkerMetrics.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MediaWorkerMetricsTest {
    @Test void finiteSchemaHasNoIdentifiersAndUnboundTempCapacityRemainsUnknown() {
        var registry = new SimpleMeterRegistry();
        try {
            new MediaWorkerMetrics(registry);
            assertThat(registry.getMeters()).hasSize(30);
            assertThat(registry.getMeters().stream().map(Meter::getId).toList()).allSatisfy(id -> {
                assertThat(id.getDescription()).isNull();
                assertThat(id.getTags()).allSatisfy(tag -> assertThat(tag.getKey()).isIn("workflow", "outcome"));
            });
            assertThat(registry.get("soundconnect.worker.temp.reserved").gauge().value()).isNaN();
            assertThat(registry.get("soundconnect.worker.temp.limit").gauge().value()).isNaN();
            assertThat(registry.get("soundconnect.worker.ffmpeg.cpu.time").gauge().value()).isNaN();
        } finally { registry.close(); }
    }

    @Test void concurrentJobsHaveExactActiveCountAndEachSampleFinishesOnlyOnce() throws Exception {
        var registry = new SimpleMeterRegistry();
        var executor = Executors.newFixedThreadPool(8);
        var started = new CountDownLatch(8);
        var finish = new CountDownLatch(1);
        try {
            var metrics = new MediaWorkerMetrics(registry);
            var futures = new ArrayList<Future<?>>();
            for (int index = 0; index < 8; index++) futures.add(executor.submit(() -> {
                var sample = metrics.startJob(Job.HLS);
                started.countDown();
                try { assertThat(finish.await(5, TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
                finally { sample.finish(JobOutcome.SUCCESS); sample.finish(JobOutcome.FAILURE); }
            }));
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(registry.get("soundconnect.worker.job.active").tag("workflow", "hls").gauge().value()).isEqualTo(8);
            finish.countDown();
            for (var future : futures) future.get(5, TimeUnit.SECONDS);
            assertThat(registry.get("soundconnect.worker.job.active").tag("workflow", "hls").gauge().value()).isZero();
            assertThat(registry.get("soundconnect.worker.job.duration").tags("workflow", "hls", "outcome", "success").timer().count()).isEqualTo(8);
            assertThat(registry.get("soundconnect.worker.job.duration").tags("workflow", "hls", "outcome", "failure").timer().count()).isZero();
        } finally { finish.countDown(); executor.shutdownNow(); registry.close(); }
    }

    @Test void recoveryRowsArePublishedOnlyAfterCommitAndNeverForRollback() {
        var registry = new SimpleMeterRegistry();
        try {
            var metrics = new MediaWorkerMetrics(registry);
            TransactionSynchronizationManager.setActualTransactionActive(true);
            TransactionSynchronizationManager.initSynchronization();
            metrics.recovered(Recovery.RETRY_CLEANUP, 3);
            assertThat(registry.get("soundconnect.worker.recovery").tag("outcome", "retry_cleanup").counter().count()).isZero();
            var commit = TransactionSynchronizationManager.getSynchronizations();
            commit.forEach(TransactionSynchronization::afterCommit);
            assertThat(registry.get("soundconnect.worker.recovery").tag("outcome", "retry_cleanup").counter().count()).isEqualTo(3);
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.initSynchronization();
            metrics.recovered(Recovery.TERMINAL_CLEANUP, 2);
            TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
            assertThat(registry.get("soundconnect.worker.recovery").tag("outcome", "terminal_cleanup").counter().count()).isZero();
        } finally { TransactionSynchronizationManager.clear(); registry.close(); }
    }

    @Test void registrationAndRecordingFailuresDoNotEscapeOrPreventSampleRelease() {
        var broken = mock(MeterRegistry.class);
        var timer = mock(Timer.class);
        var counter = mock(Counter.class);
        when(broken.timer(anyString(), any(String[].class))).thenReturn(timer);
        when(broken.counter(anyString(), any(String[].class))).thenReturn(counter);
        doThrow(new IllegalStateException("private metric failure")).when(timer).record(anyLong(), eq(TimeUnit.NANOSECONDS));
        doThrow(new IllegalStateException("private metric failure")).when(counter).increment(anyDouble());
        // Gauge registration may also reject a registry; every registration is isolated.
        assertThatCode(() -> {
            var metrics = new MediaWorkerMetrics(broken);
            var sample = metrics.startJob(Job.HLS);
            metrics.admission(Job.HLS, Admission.ACCEPTED);
            metrics.lease(LeaseOutcome.LOST);
            metrics.recovered(Recovery.STALE_SENT, 1);
            metrics.tempWait(TempOutcome.TIMEOUT, System.nanoTime());
            sample.finish(JobOutcome.FAILURE);
            sample.finish(JobOutcome.SUCCESS);
        }).doesNotThrowAnyException();
        verify(timer, times(2)).record(anyLong(), eq(TimeUnit.NANOSECONDS));
        verify(counter, times(3)).increment(anyDouble());
    }

    @Test void ownedFfmpegCpuIsSampledWithoutPidInspectionAndUnavailableIsNeverZero() {
        var registry = new SimpleMeterRegistry();
        try {
            var clock = new AtomicLong();
            var metrics = new MediaWorkerMetrics(registry, clock::get);
            var first = mock(Process.class);
            var second = mock(Process.class);
            var firstInfo = mock(ProcessHandle.Info.class);
            var secondInfo = mock(ProcessHandle.Info.class);
            when(first.info()).thenReturn(firstInfo);
            when(second.info()).thenReturn(secondInfo);
            when(firstInfo.totalCpuDuration()).thenReturn(Optional.empty(), Optional.of(Duration.ofSeconds(2)), Optional.empty());
            when(secondInfo.totalCpuDuration()).thenReturn(Optional.of(Duration.ofSeconds(3)));
            var one = metrics.startFfmpeg(first);
            assertThat(registry.get("soundconnect.worker.ffmpeg.active").gauge().value()).isEqualTo(1);
            assertThat(registry.get("soundconnect.worker.ffmpeg.cpu.time").gauge().value()).isNaN();
            one.poll();
            assertThat(registry.get("soundconnect.worker.ffmpeg.cpu.time").gauge().value()).isEqualTo(2);
            var two = metrics.startFfmpeg(second);
            assertThat(registry.get("soundconnect.worker.ffmpeg.cpu.time").gauge().value()).isEqualTo(5);
            clock.set(TimeUnit.SECONDS.toNanos(6));
            assertThat(registry.get("soundconnect.worker.ffmpeg.cpu.time").gauge().value()).isNaN();
            two.poll();
            one.poll();
            assertThat(registry.get("soundconnect.worker.ffmpeg.cpu.time").gauge().value()).isNaN();
            one.close();
            one.close();
            assertThat(registry.get("soundconnect.worker.ffmpeg.cpu.time").gauge().value()).isEqualTo(3);
            two.close();
            assertThat(registry.get("soundconnect.worker.ffmpeg.active").gauge().value()).isZero();
            assertThat(registry.get("soundconnect.worker.ffmpeg.cpu.time").gauge().value()).isNaN();
            verify(first, times(3)).info();
            verify(second, times(2)).info();
            verifyNoMoreInteractions(first, second);
        } finally { registry.close(); }
    }

    @Test void invalidOrThrowingCpuMetadataDoesNotEscapeAndCloseRemainsIdempotent() {
        var registry = new SimpleMeterRegistry();
        try {
            var metrics = new MediaWorkerMetrics(registry);
            var child = mock(Process.class);
            var info = mock(ProcessHandle.Info.class);
            when(child.info()).thenReturn(info).thenThrow(new UnsupportedOperationException("platform metadata"));
            when(info.totalCpuDuration()).thenReturn(Optional.of(Duration.ofSeconds(-1)));
            try (var sample = metrics.startFfmpeg(child)) {
                assertThat(registry.get("soundconnect.worker.ffmpeg.cpu.time").gauge().value()).isNaN();
                assertThatCode(sample::poll).doesNotThrowAnyException();
                assertThat(registry.get("soundconnect.worker.ffmpeg.cpu.time").gauge().value()).isNaN();
            }
            assertThat(registry.get("soundconnect.worker.ffmpeg.active").gauge().value()).isZero();
        } finally { registry.close(); }
    }

    @Test void unexpectedExtraNativeChildrenDoNotGrowTheObservationSetOrReportPartialCpuAsComplete() {
        var registry = new SimpleMeterRegistry();
        var samples = new ArrayList<FfmpegSample>();
        try {
            var metrics = new MediaWorkerMetrics(registry);
            var child = mock(Process.class);
            var info = mock(ProcessHandle.Info.class);
            when(child.info()).thenReturn(info);
            when(info.totalCpuDuration()).thenReturn(Optional.of(Duration.ofSeconds(1)));
            for (int index = 0; index < 33; index++) samples.add(metrics.startFfmpeg(child));
            assertThat(registry.get("soundconnect.worker.ffmpeg.active").gauge().value()).isEqualTo(33);
            assertThat(registry.get("soundconnect.worker.ffmpeg.cpu.time").gauge().value()).isNaN();
            samples.getLast().close();
            assertThat(registry.get("soundconnect.worker.ffmpeg.cpu.time").gauge().value()).isEqualTo(32);
        } finally { samples.forEach(FfmpegSample::close); registry.close(); }
    }
}

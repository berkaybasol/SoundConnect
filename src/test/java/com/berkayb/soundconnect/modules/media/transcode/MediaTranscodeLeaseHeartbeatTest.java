package com.berkayb.soundconnect.modules.media.transcode;

import com.berkayb.soundconnect.modules.media.transcode.config.MediaTranscodeLeaseProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.TaskScheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MediaTranscodeLeaseHeartbeatTest {

	@Mock MediaAssetStatusUpdater statusUpdater;
	@Mock TaskScheduler scheduler;
	@Mock ScheduledFuture<?> future;
	MediaTranscodeLeaseHeartbeat heartbeat;

	@BeforeEach
	void setUp() {
		MediaTranscodeLeaseProperties properties = new MediaTranscodeLeaseProperties();
		properties.setDuration(Duration.ofMinutes(15));
		properties.setHeartbeatInterval(Duration.ofMinutes(1));
		doReturn(future).when(scheduler).scheduleWithFixedDelay(
				any(Runnable.class), any(Instant.class), eq(Duration.ofMinutes(1)));
		heartbeat = new MediaTranscodeLeaseHeartbeat(statusUpdater, properties, scheduler);
	}

	@Test
	void scheduledHeartbeatAndCheckpointRenewExactAttempt() {
		UUID id = UUID.randomUUID();
		UUID token = UUID.randomUUID();
		when(statusUpdater.renewTranscodeLease(id, token)).thenReturn(true);
		ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);

		TranscodeLease lease = heartbeat.start(id, token);
		verify(scheduler).scheduleWithFixedDelay(
				task.capture(), any(Instant.class), eq(Duration.ofMinutes(1)));
		task.getValue().run();
		lease.checkpoint();
		lease.close();

		verify(statusUpdater, times(2)).renewTranscodeLease(id, token);
		verify(future).cancel(false);
	}

	@Test
	void failedCasPermanentlyLosesLease() {
		UUID id = UUID.randomUUID();
		UUID token = UUID.randomUUID();
		when(statusUpdater.renewTranscodeLease(id, token)).thenReturn(false);
		TranscodeLease lease = heartbeat.start(id, token);

		assertThatThrownBy(lease::checkpoint)
				.isInstanceOf(TranscodeLeaseLostException.class);
		assertThatThrownBy(lease::checkpoint)
				.isInstanceOf(TranscodeLeaseLostException.class);

		verify(statusUpdater, times(1)).renewTranscodeLease(id, token);
	}
}

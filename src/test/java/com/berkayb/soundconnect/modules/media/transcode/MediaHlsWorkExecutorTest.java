package com.berkayb.soundconnect.modules.media.transcode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskRejectedException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class MediaHlsWorkExecutorTest {

	@Mock VideoHlsWorkflow workflow;

	@Test
	void completedWorkerAlwaysReleasesAdmissionPermit() throws Exception {
		MediaHlsWorkExecutor executor = new MediaHlsWorkExecutor(workflow, Runnable::run, 1);
		var work = work();

		assertThat(executor.tryReserve()).isTrue();
		assertThat(executor.tryReserve()).isFalse();
		assertThat(executor.submitReserved(work)).isTrue();

		verify(workflow).processClaimed(work);
		assertThat(executor.availablePermits()).isEqualTo(1);
	}

	@Test
	void rejectedHandoffReleasesPermitAndLeavesLeaseRecoveryToDatabase() {
		MediaHlsWorkExecutor executor = new MediaHlsWorkExecutor(
				workflow, task -> { throw new TaskRejectedException("shutdown"); }, 1);
		assertThat(executor.tryReserve()).isTrue();

		assertThat(executor.submitReserved(work())).isFalse();
		assertThat(executor.availablePermits()).isEqualTo(1);
	}

	@Test
	void multipleWorkersFailClosedUntilWeightedDiskAdmissionExists() {
		assertThatThrownBy(() -> new MediaHlsWorkExecutor(workflow, Runnable::run, 2))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("temp-disk admission");
	}

	private static VideoHlsWorkflow.ClaimedVideoHlsWork work() {
		return new VideoHlsWorkflow.ClaimedVideoHlsWork(
				UUID.randomUUID(), UUID.randomUUID(), "verified/media/source.mp4", "media/hls", 1);
	}
}

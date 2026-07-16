package com.berkayb.soundconnect.modules.media.abuse;

import org.junit.jupiter.api.Test;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskRejectedException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class MediaUploadCleanupDispatcherTest {

	@Test
	void acceptedWorkRunsOutsideCallerContractThroughConfiguredExecutor() {
		MediaUploadCleanupWorker worker = mock(MediaUploadCleanupWorker.class);
		MediaUploadCleanupDispatcher dispatcher = new MediaUploadCleanupDispatcher(
				worker, new SyncTaskExecutor());
		UUID assetId = UUID.randomUUID();

		assertThat(dispatcher.submit(assetId)).isTrue();
		verify(worker).clean(assetId);
	}

	@Test
	void saturationRetainsDurableWorkForScheduledRetry() {
		MediaUploadCleanupDispatcher dispatcher = new MediaUploadCleanupDispatcher(
				mock(MediaUploadCleanupWorker.class),
				task -> { throw new TaskRejectedException("full"); });

		assertThat(dispatcher.submit(UUID.randomUUID())).isFalse();
	}
}

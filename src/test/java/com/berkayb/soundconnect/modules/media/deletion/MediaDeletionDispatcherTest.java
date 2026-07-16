package com.berkayb.soundconnect.modules.media.deletion;

import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class MediaDeletionDispatcherTest {

	@Test
	void deletionEvent_isDispatchedOnlyInAfterCommitPhase() throws Exception {
		var listener = MediaDeletionDispatcher.class
				.getMethod("onRequested", MediaDeletionRequestedEvent.class)
				.getAnnotation(TransactionalEventListener.class);

		assertThat(listener).isNotNull();
		assertThat(listener.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
	}

	@Test
	void acceptedTask_runsWorkerThroughBoundedDispatcher() {
		MediaDeletionWorker worker = mock(MediaDeletionWorker.class);
		TaskExecutor sameThread = Runnable::run;
		MediaDeletionDispatcher dispatcher = new MediaDeletionDispatcher(worker, sameThread);
		UUID assetId = UUID.randomUUID();

		assertThat(dispatcher.submit(assetId)).isTrue();

		verify(worker).delete(assetId);
	}

	@Test
	void saturatedQueue_releasesDedupSlotAndLeavesDurableRetryToScheduler() {
		MediaDeletionWorker worker = mock(MediaDeletionWorker.class);
		TaskExecutor saturated = task -> { throw new TaskRejectedException("full"); };
		MediaDeletionDispatcher dispatcher = new MediaDeletionDispatcher(worker, saturated);
		UUID assetId = UUID.randomUUID();

		assertThat(dispatcher.submit(assetId)).isFalse();
		assertThat(dispatcher.submit(assetId)).isFalse();

		verifyNoInteractions(worker);
	}
}

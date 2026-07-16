package com.berkayb.soundconnect.modules.media.recovery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MediaPublicPromotionRecoverySchedulerTest {

	@Mock MediaPublicPromotionRecoveryRepository repository;
	@Mock MediaPublicPromotionRecoveryWorker worker;
	@Mock MediaPublicPromotionRecoveryProperties properties;
	@Mock TaskExecutor executor;

	@Test
	void rotatesThroughCompleteReadySetAndWrapsWithoutFailureStarvation() {
		var first = target();
		var second = target();
		var third = target();
		when(properties.isEnabled()).thenReturn(true);
		when(properties.getBatchSize()).thenReturn(2);
		when(repository.findReadyBatch(0, 2))
				.thenReturn(List.of(first, second))
				.thenReturn(List.of(first, second));
		when(repository.findReadyBatch(2, 2)).thenReturn(List.of(third));
		doAnswer(invocation -> {
			invocation.<Runnable>getArgument(0).run();
			return null;
		}).when(executor).execute(any(Runnable.class));
		var scheduler = scheduler();

		scheduler.recover();
		scheduler.recover();
		scheduler.recover();

		verify(repository).findReadyBatch(2, 2);
		verify(worker, times(2)).recover(first);
		verify(worker, times(2)).recover(second);
		verify(worker).recover(third);
	}

	@Test
	void advancesOnlyPastAcceptedWorkWhenBoundedQueueIsSaturated() {
		var first = target();
		var second = target();
		when(properties.isEnabled()).thenReturn(true);
		when(properties.getBatchSize()).thenReturn(2);
		when(repository.findReadyBatch(0, 2)).thenReturn(List.of(first, second));
		when(repository.findReadyBatch(1, 2)).thenReturn(List.of(second));
		AtomicInteger submissions = new AtomicInteger();
		doAnswer(invocation -> {
			if (submissions.getAndIncrement() == 0) return null;
			throw new TaskRejectedException("full");
		}).when(executor).execute(any(Runnable.class));
		var scheduler = scheduler();

		scheduler.recover();
		scheduler.recover();

		verify(repository).findReadyBatch(1, 2);
	}

	private MediaPublicPromotionRecoveryScheduler scheduler() {
		return new MediaPublicPromotionRecoveryScheduler(repository, worker, properties, executor);
	}

	private static MediaPublicPromotionRecoveryTarget target() {
		UUID assetId = UUID.randomUUID();
		return new MediaPublicPromotionRecoveryTarget(
				assetId, "media/" + assetId + "/source.jpg", LocalDateTime.now().minusHours(1));
	}
}

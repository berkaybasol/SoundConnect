package com.berkayb.soundconnect.modules.media.abuse;

import com.berkayb.soundconnect.modules.media.entity.MediaAsset;
import com.berkayb.soundconnect.modules.media.enums.MediaStatus;
import com.berkayb.soundconnect.modules.media.repository.MediaAssetRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskExecutor;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MediaUploadCleanupSchedulerTest {

	@Mock MediaAssetRepository mediaAssetRepository;
	@Mock MediaUploadCleanupWorker cleanupWorker;
	@Mock MediaUploadCleanupProperties properties;
	@Mock MediaUploadCleanupDispatcher cleanupDispatcher;
	@Mock MediaProtectedUploadRecoveryRepository protectedUploadRecoveryRepository;
	@Mock TaskExecutor protectedUploadRecoveryExecutor;
	@InjectMocks MediaUploadCleanupScheduler scheduler;

	@Test
	void atomicallyClaimsStaleUploadsThenRetriesPendingObjectCleanup() {
		UUID staleId = UUID.randomUUID();
		UUID pendingId = UUID.randomUUID();
		MediaAsset stale = MediaAsset.builder().id(staleId).status(MediaStatus.UPLOADING).build();
		MediaAsset pending = MediaAsset.builder().id(pendingId).status(MediaStatus.CLEANUP_PENDING).build();
		when(properties.isEnabled()).thenReturn(true);
		when(properties.getBatchSize()).thenReturn(25);
		when(properties.getStaleAfter()).thenReturn(java.time.Duration.ofHours(2));
		when(cleanupDispatcher.submit(pendingId)).thenReturn(true);
		when(mediaAssetRepository.findByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(
				eq(MediaStatus.UPLOADING), any(LocalDateTime.class), any(Pageable.class)))
				.thenReturn(List.of(stale));
		when(mediaAssetRepository.findByStatusOrderByUpdatedAtAsc(
				eq(MediaStatus.CLEANUP_PENDING), any(Pageable.class)))
				.thenReturn(List.of(pending));

		scheduler.cleanup();

		verify(cleanupWorker).claim(eq(staleId), any(LocalDateTime.class));
		verify(cleanupDispatcher).submit(pendingId);
	}

	@Test
	void readyProtectedRecoveryRotatesPastFailuresAndRestartsAfterFullSweep() {
		UUID firstId = UUID.randomUUID();
		UUID secondId = UUID.randomUUID();
		LocalDateTime createdAt = LocalDateTime.now().minusHours(1);
		var first = new MediaProtectedUploadRecoveryRepository.RecoveryTarget(
				firstId, "protected/private-verified/media/" + firstId + "/source.mp3", createdAt);
		var second = new MediaProtectedUploadRecoveryRepository.RecoveryTarget(
				secondId, "protected/private-verified/media/" + secondId + "/source.mp3", createdAt);

		when(properties.isEnabled()).thenReturn(true);
		when(properties.getBatchSize()).thenReturn(25);
		when(properties.getStaleAfter()).thenReturn(java.time.Duration.ofHours(2));
		when(properties.isProtectedMutableRecoveryEnabled()).thenReturn(true);
		when(properties.getProtectedMutableRecoveryBatchSize()).thenReturn(2);
		when(mediaAssetRepository.findByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(
				eq(MediaStatus.UPLOADING), any(LocalDateTime.class), any(Pageable.class)))
				.thenReturn(List.of());
		when(mediaAssetRepository.findByStatusOrderByUpdatedAtAsc(
				eq(MediaStatus.CLEANUP_PENDING), any(Pageable.class)))
				.thenReturn(List.of());
		when(protectedUploadRecoveryRepository.findReadyBatch(0, 2))
				.thenReturn(List.of(first, second))
				.thenReturn(List.of(first));
		when(protectedUploadRecoveryRepository.findReadyBatch(2, 2))
				.thenReturn(List.of());
		doAnswer(invocation -> {
			invocation.<Runnable>getArgument(0).run();
			return null;
		}).when(protectedUploadRecoveryExecutor).execute(any(Runnable.class));

		scheduler.cleanup();
		scheduler.cleanup();
		scheduler.cleanup();

		verify(protectedUploadRecoveryRepository).findReadyBatch(2, 2);
		verify(cleanupWorker, org.mockito.Mockito.times(2)).cleanProtectedMutableSource(first);
		verify(cleanupWorker).cleanProtectedMutableSource(second);
	}
}

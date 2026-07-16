package com.berkayb.soundconnect.modules.media.service;

import com.berkayb.soundconnect.modules.media.abuse.MediaUploadCleanupDispatcher;
import com.berkayb.soundconnect.modules.media.entity.MediaAsset;
import com.berkayb.soundconnect.modules.media.enums.MediaStatus;
import com.berkayb.soundconnect.modules.media.repository.MediaAssetRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MediaUploadFailureHandlerTest {

	@Mock MediaAssetRepository mediaAssetRepository;
	@Mock MediaUploadCleanupDispatcher mediaUploadCleanupDispatcher;

	MediaUploadFailureHandler handler;

	@BeforeEach
	void setUp() {
		handler = new MediaUploadFailureHandler(
				mediaAssetRepository,
				mediaUploadCleanupDispatcher
		);
		TransactionSynchronizationManager.initSynchronization();
	}

	@AfterEach
	void tearDown() {
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.clearSynchronization();
		}
	}

	@Test
	void rejectedUploadingAssetIsDurablyQueuedForRetryingCleanup() {
		UUID assetId = UUID.randomUUID();
		String key = "quarantine/media/" + assetId + "/source.png";
		MediaAsset asset = MediaAsset.builder()
				.id(assetId)
				.status(MediaStatus.UPLOADING)
				.storageKey(key)
				.build();
		when(mediaAssetRepository.findByIdForUpdate(assetId)).thenReturn(Optional.of(asset));

		handler.recordRejectedUpload(assetId, key);

		assertThat(asset.getStatus()).isEqualTo(MediaStatus.CLEANUP_PENDING);
		verify(mediaAssetRepository).save(asset);
		verify(mediaUploadCleanupDispatcher, never()).submit(assetId);
		TransactionSynchronizationManager.getSynchronizations()
				.forEach(TransactionSynchronization::afterCommit);
		verify(mediaUploadCleanupDispatcher).submit(assetId);
	}

	@Test
	void transcodeFailedSourceIsNeverClaimedOrDeletedByUploadRejectionPath() {
		UUID assetId = UUID.randomUUID();
		String key = "quarantine/media/" + assetId + "/source.mp4";
		MediaAsset asset = MediaAsset.builder()
				.id(assetId)
				.status(MediaStatus.FAILED)
				.storageKey(key)
				.build();
		when(mediaAssetRepository.findByIdForUpdate(assetId)).thenReturn(Optional.of(asset));

		handler.recordRejectedUpload(assetId, key);

		verify(mediaAssetRepository, never()).save(asset);
		verify(mediaUploadCleanupDispatcher, never()).submit(assetId);
	}

	@Test
	void staleVerificationAttemptCannotRejectNewerCrossNodeClaim() {
		UUID assetId = UUID.randomUUID();
		UUID liveToken = UUID.randomUUID();
		UUID staleToken = UUID.randomUUID();
		String key = "quarantine/media/" + assetId + "/source.png";
		MediaAsset asset = MediaAsset.builder()
				.id(assetId)
				.status(MediaStatus.VERIFYING)
				.storageKey(key)
				.uploadVerificationAttemptToken(liveToken)
				.build();
		when(mediaAssetRepository.findByIdForUpdate(assetId)).thenReturn(Optional.of(asset));

		handler.recordRejectedUpload(assetId, key, staleToken);

		assertThat(asset.getStatus()).isEqualTo(MediaStatus.VERIFYING);
		assertThat(asset.getUploadVerificationAttemptToken()).isEqualTo(liveToken);
		verify(mediaAssetRepository, never()).save(asset);
		verify(mediaUploadCleanupDispatcher, never()).submit(assetId);
	}

	@Test
	void matchingVerificationAttemptQueuesCleanupAndClearsLeaseFence() {
		UUID assetId = UUID.randomUUID();
		UUID token = UUID.randomUUID();
		String key = "quarantine/media/" + assetId + "/source.png";
		MediaAsset asset = MediaAsset.builder()
				.id(assetId)
				.status(MediaStatus.VERIFYING)
				.storageKey(key)
				.uploadVerificationAttemptToken(token)
				.uploadVerificationLeaseExpiresAt(java.time.LocalDateTime.now().plusMinutes(5))
				.build();
		when(mediaAssetRepository.findByIdForUpdate(assetId)).thenReturn(Optional.of(asset));

		handler.recordRejectedUpload(assetId, key, token);

		assertThat(asset.getStatus()).isEqualTo(MediaStatus.CLEANUP_PENDING);
		assertThat(asset.getUploadVerificationAttemptToken()).isEqualTo(token);
		assertThat(asset.getUploadVerificationLeaseExpiresAt()).isNull();
		verify(mediaAssetRepository).save(asset);
	}
}

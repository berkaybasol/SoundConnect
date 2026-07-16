package com.berkayb.soundconnect.modules.media.abuse;

import com.berkayb.soundconnect.modules.media.entity.MediaAsset;
import com.berkayb.soundconnect.modules.media.enums.MediaStatus;
import com.berkayb.soundconnect.modules.media.repository.MediaAssetRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MediaUploadCleanupStateServiceTest {

	@Mock MediaAssetRepository mediaAssetRepository;

	@Test
	void staleTokenCannotFinalizeANewerCleanupIntent() {
		UUID assetId = UUID.randomUUID();
		UUID currentToken = UUID.randomUUID();
		LocalDateTime cleanupNotBefore = LocalDateTime.now().minusMinutes(1);
		MediaAsset asset = MediaAsset.builder()
				.id(assetId)
				.status(MediaStatus.CLEANUP_PENDING)
				.storageKey("quarantine/media/" + assetId + "/source.png")
				.uploadVerificationAttemptToken(currentToken)
				.uploadVerificationCleanupNotBefore(cleanupNotBefore)
				.build();
		when(mediaAssetRepository.findByIdForUpdate(assetId)).thenReturn(Optional.of(asset));
		MediaUploadCleanupStateService service = new MediaUploadCleanupStateService(
				mediaAssetRepository);
		var stale = new MediaUploadCleanupStateService.CleanupTarget(
				assetId,
				asset.getStorageKey(),
				LocalDateTime.now().minusHours(1),
				null,
				UUID.randomUUID(),
				cleanupNotBefore
		);

		assertThat(service.finish(stale)).isFalse();

		assertThat(asset.getStatus()).isEqualTo(MediaStatus.CLEANUP_PENDING);
		assertThat(asset.getStorageKey()).isNotNull();
		verify(mediaAssetRepository, never()).save(asset);
	}

	@Test
	void exactTokenKeyAndDeadlineFinalizeAndClearVerificationState() {
		UUID assetId = UUID.randomUUID();
		UUID token = UUID.randomUUID();
		LocalDateTime cleanupNotBefore = LocalDateTime.now().minusMinutes(1);
		String key = "quarantine/media/" + assetId + "/source.png";
		MediaAsset asset = MediaAsset.builder()
				.id(assetId)
				.status(MediaStatus.CLEANUP_PENDING)
				.storageKey(key)
				.sourceUrl("stale")
				.playbackUrl("stale")
				.thumbnailUrl("stale")
				.uploadVerificationAttemptToken(token)
				.uploadVerificationLeaseExpiresAt(LocalDateTime.now().plusMinutes(1))
				.uploadVerificationAttemptDeadline(LocalDateTime.now().plusMinutes(2))
				.uploadVerificationCleanupNotBefore(cleanupNotBefore)
				.build();
		when(mediaAssetRepository.findByIdForUpdate(assetId)).thenReturn(Optional.of(asset));
		MediaUploadCleanupStateService service = new MediaUploadCleanupStateService(
				mediaAssetRepository);
		var target = new MediaUploadCleanupStateService.CleanupTarget(
				assetId,
				key,
				LocalDateTime.now().minusHours(1),
				null,
				token,
				cleanupNotBefore
		);

		assertThat(service.finish(target)).isTrue();

		assertThat(asset.getStatus()).isEqualTo(MediaStatus.FAILED);
		assertThat(asset.getStorageKey()).isNull();
		assertThat(asset.getSourceUrl()).isNull();
		assertThat(asset.getPlaybackUrl()).isNull();
		assertThat(asset.getThumbnailUrl()).isNull();
		assertThat(asset.getUploadVerificationAttemptToken()).isNull();
		assertThat(asset.getUploadVerificationLeaseExpiresAt()).isNull();
		assertThat(asset.getUploadVerificationAttemptDeadline()).isNull();
		assertThat(asset.getUploadVerificationCleanupNotBefore()).isNull();
		verify(mediaAssetRepository).save(asset);
	}

	@Test
	void deferTouchesOnlyTheExactPendingIntent() {
		UUID assetId = UUID.randomUUID();
		UUID token = UUID.randomUUID();
		LocalDateTime cleanupNotBefore = LocalDateTime.now().plusMinutes(1);
		MediaAsset asset = MediaAsset.builder()
				.id(assetId)
				.status(MediaStatus.CLEANUP_PENDING)
				.storageKey("quarantine/media/" + assetId + "/source.png")
				.uploadVerificationAttemptToken(token)
				.uploadVerificationCleanupNotBefore(cleanupNotBefore)
				.build();
		when(mediaAssetRepository.findByIdForUpdate(assetId)).thenReturn(Optional.of(asset));
		when(mediaAssetRepository.deferUploadCleanup(assetId, MediaStatus.CLEANUP_PENDING))
				.thenReturn(1);
		MediaUploadCleanupStateService service = new MediaUploadCleanupStateService(
				mediaAssetRepository);
		var target = new MediaUploadCleanupStateService.CleanupTarget(
				assetId,
				asset.getStorageKey(),
				LocalDateTime.now(),
				null,
				token,
				cleanupNotBefore
		);

		assertThat(service.defer(target)).isTrue();

		verify(mediaAssetRepository).deferUploadCleanup(
				assetId, MediaStatus.CLEANUP_PENDING);
	}
}

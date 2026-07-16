package com.berkayb.soundconnect.modules.media.deletion;

import com.berkayb.soundconnect.modules.media.enums.MediaKind;
import com.berkayb.soundconnect.modules.media.enums.MediaVisibility;
import com.berkayb.soundconnect.modules.media.storage.MediaPolicy;
import com.berkayb.soundconnect.modules.media.storage.PresignedUploadWriteWindow;
import com.berkayb.soundconnect.modules.media.storage.StorageClient;
import com.berkayb.soundconnect.modules.media.service.MediaAssetReferenceGuard;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MediaDeletionWorkerTest {

	@Mock MediaDeletionStateService stateService;
	@Mock StorageClient storageClient;
	@Mock MediaPolicy mediaPolicy;
	@Mock PresignedUploadWriteWindow presignedUploadWriteWindow;
	@Mock MediaAssetReferenceGuard mediaAssetReferenceGuard;

	@Test
	void transientStorageFailure_keepsPendingRowForRetry_andStillAttemptsDerivative() {
		UUID assetId = UUID.randomUUID();
		String sourceKey = "media/" + assetId + "/source.jpg";
		String thumbnailKey = "media/" + assetId + "/thumbnail.jpg";
		when(stateService.getPendingTarget(assetId)).thenReturn(Optional.of(
				new MediaDeletionStateService.DeletionTarget(
						assetId, sourceKey, MediaKind.IMAGE, MediaVisibility.PUBLIC,
						LocalDateTime.now(ZoneOffset.UTC).minusHours(1),
						LocalDateTime.now(ZoneOffset.UTC).minusHours(1),
						LocalDateTime.now(ZoneOffset.UTC).minusHours(1))));
		when(presignedUploadWriteWindow.isSafeToDelete(org.mockito.ArgumentMatchers.any()))
				.thenReturn(true);
		doThrow(new IllegalStateException("temporary S3 failure"))
				.when(storageClient).deleteObject(sourceKey);
		MediaDeletionWorker worker = new MediaDeletionWorker(
				stateService, storageClient, mediaPolicy,
				presignedUploadWriteWindow, mediaAssetReferenceGuard);

		worker.delete(assetId);

		verify(storageClient).deleteObject(sourceKey);
		verify(storageClient).deleteObject(thumbnailKey);
		verify(stateService, never()).finish(assetId);
		verify(stateService).defer(assetId);
		verify(storageClient, never()).invalidatePublicAsset(assetId);
	}

	@Test
	void successfulVideoCleanup_deletesSourceAndHls_thenDeletesOnlyPendingRow() {
		UUID assetId = UUID.randomUUID();
		String sourceKey = "verified/media/" + assetId + "/source.mp4";
		String hlsPrefix = "media/" + assetId + "/hls/";
		when(stateService.getPendingTarget(assetId)).thenReturn(Optional.of(
				new MediaDeletionStateService.DeletionTarget(
						assetId, sourceKey, MediaKind.VIDEO, MediaVisibility.PUBLIC,
						LocalDateTime.now(ZoneOffset.UTC).minusHours(14),
						null,
						LocalDateTime.now(ZoneOffset.UTC).minusHours(14),
						LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1),
						LocalDateTime.now(ZoneOffset.UTC).minusHours(14))));
		when(presignedUploadWriteWindow.isSafeToDelete(org.mockito.ArgumentMatchers.any()))
				.thenReturn(true);
		when(mediaPolicy.buildHlsPrefix(assetId)).thenReturn(hlsPrefix);
		MediaDeletionWorker worker = new MediaDeletionWorker(
				stateService, storageClient, mediaPolicy,
				presignedUploadWriteWindow, mediaAssetReferenceGuard);

		worker.delete(assetId);

		verify(storageClient).deleteObject(sourceKey);
		verify(storageClient).deleteFolder(hlsPrefix);
		verify(storageClient).invalidatePublicAsset(assetId);
		verify(stateService).finish(assetId);
		verify(stateService, never()).defer(assetId);
	}

	@Test
	void livePresignedPutDefersBeforeAnyStorageIo() {
		UUID assetId = UUID.randomUUID();
		String uploadKey = "quarantine/media/" + assetId + "/source.jpg";
		LocalDateTime createdAt = LocalDateTime.now(ZoneOffset.UTC);
		when(stateService.getPendingTarget(assetId)).thenReturn(Optional.of(
				new MediaDeletionStateService.DeletionTarget(
						assetId, uploadKey, MediaKind.IMAGE, MediaVisibility.PUBLIC,
						createdAt, createdAt, createdAt)));
		when(presignedUploadWriteWindow.isSafeToDelete(createdAt)).thenReturn(false);
		MediaDeletionWorker worker = new MediaDeletionWorker(
				stateService, storageClient, mediaPolicy,
				presignedUploadWriteWindow, mediaAssetReferenceGuard);

		worker.delete(assetId);

		verify(stateService).defer(assetId);
		verifyNoInteractions(storageClient);
		verifyNoInteractions(mediaAssetReferenceGuard);
	}

	@Test
	void referenceRaceDefersBeforeAnyStorageIo() {
		UUID assetId = UUID.randomUUID();
		String sourceKey = "media/" + assetId + "/source.jpg";
		when(stateService.getPendingTarget(assetId)).thenReturn(Optional.of(
				new MediaDeletionStateService.DeletionTarget(
						assetId, sourceKey, MediaKind.IMAGE, MediaVisibility.PUBLIC,
						LocalDateTime.now(ZoneOffset.UTC).minusHours(1),
						LocalDateTime.now(ZoneOffset.UTC).minusHours(1),
						LocalDateTime.now(ZoneOffset.UTC).minusHours(1))));
		when(presignedUploadWriteWindow.isSafeToDelete(org.mockito.ArgumentMatchers.any()))
				.thenReturn(true);
		doThrow(new SoundConnectException(ErrorType.MEDIA_ASSET_IN_USE))
				.when(mediaAssetReferenceGuard).assertNotReferenced(assetId);
		MediaDeletionWorker worker = new MediaDeletionWorker(
				stateService, storageClient, mediaPolicy,
				presignedUploadWriteWindow, mediaAssetReferenceGuard);

		worker.delete(assetId);

		verify(stateService).defer(assetId);
		verifyNoInteractions(storageClient);
	}

	@Test
	void publicImageWithinProducerGrace_retainsDurableRowWithoutStorageIo() {
		UUID assetId = UUID.randomUUID();
		LocalDateTime requestedAt = LocalDateTime.now(ZoneOffset.UTC).minusSeconds(10);
		when(stateService.getPendingTarget(assetId)).thenReturn(Optional.of(
				new MediaDeletionStateService.DeletionTarget(
						assetId, "media/" + assetId + "/source.jpg", MediaKind.IMAGE,
						MediaVisibility.PUBLIC, LocalDateTime.now(ZoneOffset.UTC).minusHours(1),
						requestedAt, requestedAt)));
		when(presignedUploadWriteWindow.isSafeToDelete(org.mockito.ArgumentMatchers.any()))
				.thenReturn(true);
		MediaDeletionWorker worker = new MediaDeletionWorker(
				stateService, storageClient, mediaPolicy,
				presignedUploadWriteWindow, mediaAssetReferenceGuard);

		worker.delete(assetId);

		verifyNoInteractions(storageClient);
		verifyNoInteractions(mediaAssetReferenceGuard);
		verify(stateService, never()).finish(assetId);
		verify(stateService, never()).defer(assetId);
	}

	@Test
	void committedPrivateObjectWithLiveMutablePredecessor_waitsForPutExpiry() {
		UUID assetId = UUID.randomUUID();
		String key = "protected/private-verified/media/" + assetId + "/source.jpg";
		LocalDateTime createdAt = LocalDateTime.now(ZoneOffset.UTC);
		when(stateService.getPendingTarget(assetId)).thenReturn(Optional.of(
				new MediaDeletionStateService.DeletionTarget(
						assetId, key, MediaKind.IMAGE, MediaVisibility.PRIVATE,
						createdAt, createdAt, createdAt)));
		when(presignedUploadWriteWindow.isSafeToDelete(createdAt)).thenReturn(false);
		MediaDeletionWorker worker = new MediaDeletionWorker(
				stateService, storageClient, mediaPolicy,
				presignedUploadWriteWindow, mediaAssetReferenceGuard);

		worker.delete(assetId);

		verify(stateService).defer(assetId);
		verifyNoInteractions(storageClient);
		verifyNoInteractions(mediaAssetReferenceGuard);
	}
}

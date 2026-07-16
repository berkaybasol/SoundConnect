package com.berkayb.soundconnect.modules.media.abuse;

import com.berkayb.soundconnect.modules.media.storage.StorageClient;
import com.berkayb.soundconnect.modules.media.storage.PresignedUploadWriteWindow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MediaUploadCleanupWorkerTest {

	@Mock MediaUploadCleanupStateService stateService;
	@Mock MediaUploadAbuseGuard abuseGuard;
	@Mock StorageClient storageClient;
	@Mock PresignedUploadWriteWindow presignedUploadWriteWindow;
	@InjectMocks MediaUploadCleanupWorker worker;

	@Test
	void releasesConcurrentReservationOnlyAfterAtomicClaim() {
		UUID assetId = UUID.randomUUID();
		LocalDateTime cutoff = LocalDateTime.now();
		when(stateService.claim(assetId, cutoff)).thenReturn(true);

		worker.claim(assetId, cutoff);

		verify(abuseGuard).release(assetId);
	}

	@Test
	void retainsPendingStateWhenStorageDeleteFailsSoTheNextPassRetries() {
		UUID assetId = UUID.randomUUID();
		String storageKey = "media/asset/source.mp4";
		when(stateService.getPendingTarget(assetId))
				.thenReturn(Optional.of(new MediaUploadCleanupStateService.CleanupTarget(
						assetId, storageKey, LocalDateTime.now().minusHours(1))));
		doThrow(new IllegalStateException("storage unavailable"))
				.when(storageClient).deleteObject(storageKey);

		worker.clean(assetId);

		verify(stateService, never()).finish(any());
		verify(stateService).defer(argThat(target -> target.assetId().equals(assetId)));
	}

	@Test
	void finalizesOnlyAfterIdempotentObjectDeletionSucceeds() {
		UUID assetId = UUID.randomUUID();
		String storageKey = "media/asset/source.png";
		when(stateService.getPendingTarget(assetId))
				.thenReturn(Optional.of(new MediaUploadCleanupStateService.CleanupTarget(
						assetId, storageKey, LocalDateTime.now().minusHours(1))));

		worker.clean(assetId);

		verify(storageClient).deleteObject(storageKey);
		verify(stateService).finish(argThat(target ->
				target.assetId().equals(assetId) && storageKey.equals(target.storageKey())));
	}

	@Test
	void publicIntentCleanupDeletesMutableSnapshotAndPossiblePromotion() {
		UUID assetId = UUID.randomUUID();
		String quarantine = "quarantine/media/" + assetId + "/source.png";
		LocalDateTime createdAt = LocalDateTime.now().minusHours(1);
		when(stateService.getPendingTarget(assetId))
				.thenReturn(Optional.of(new MediaUploadCleanupStateService.CleanupTarget(
						assetId, quarantine, createdAt)));
		when(presignedUploadWriteWindow.isSafeToDelete(createdAt)).thenReturn(true);

		worker.clean(assetId);

		verify(storageClient).deleteObject(quarantine);
		verify(storageClient).deleteObject("verified/media/" + assetId + "/source.png");
		verify(storageClient).deleteObject("media/" + assetId + "/source.png");
		verify(stateService).finish(argThat(target ->
				target.assetId().equals(assetId) && quarantine.equals(target.storageKey())));
	}

	@Test
	void rejectedVerificationUsesPersistedTokenToDeleteAttemptScopedObjects() {
		UUID assetId = UUID.randomUUID();
		UUID token = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
		String quarantine = "quarantine/media/" + assetId + "/source.png";
		LocalDateTime createdAt = LocalDateTime.now().minusHours(1);
		when(stateService.getPendingTarget(assetId))
				.thenReturn(Optional.of(new MediaUploadCleanupStateService.CleanupTarget(
						assetId, quarantine, createdAt, null, token)));
		when(presignedUploadWriteWindow.isSafeToDelete(createdAt)).thenReturn(true);

		worker.clean(assetId);

		verify(storageClient).deleteFolder("verified/media/" + assetId + "/attempts");
		verify(storageClient).deleteFolder("media/" + assetId + "/attempts");
		verify(storageClient).deleteObject(
				"verified/media/" + assetId + "/attempts/" + token + "/source.png");
		verify(storageClient).deleteObject(
				"media/" + assetId + "/attempts/" + token + "/source.png");
		verify(stateService).finish(argThat(target ->
				target.assetId().equals(assetId) && quarantine.equals(target.storageKey())));
	}

	@Test
	void livePresignedPutDefersBeforeDeletingAnyCrashStage() {
		UUID assetId = UUID.randomUUID();
		String quarantine = "quarantine/media/" + assetId + "/source.png";
		LocalDateTime createdAt = LocalDateTime.now();
		when(stateService.getPendingTarget(assetId))
				.thenReturn(Optional.of(new MediaUploadCleanupStateService.CleanupTarget(
						assetId, quarantine, createdAt)));
		when(presignedUploadWriteWindow.isSafeToDelete(createdAt)).thenReturn(false);

		worker.clean(assetId);

		verify(stateService).defer(argThat(target -> target.assetId().equals(assetId)));
		verifyNoInteractions(storageClient);
		verify(stateService, never()).finish(any());
	}

	@Test
	void rejectedVerificationWaitsForDurableCleanupFenceBeforeAnyDelete() {
		UUID assetId = UUID.randomUUID();
		UUID token = UUID.randomUUID();
		String quarantine = "quarantine/media/" + assetId + "/source.png";
		LocalDateTime cleanupNotBefore = LocalDateTime.now(ZoneOffset.UTC).plusHours(1);
		var target = new MediaUploadCleanupStateService.CleanupTarget(
				assetId,
				quarantine,
				LocalDateTime.now(ZoneOffset.UTC).minusHours(1),
				null,
				token,
				cleanupNotBefore
		);
		when(stateService.getPendingTarget(assetId)).thenReturn(Optional.of(target));

		worker.clean(assetId);

		verify(stateService).defer(target);
		verifyNoInteractions(storageClient);
		verify(stateService, never()).finish(any());
	}

	@Test
	void elapsedVerificationFenceDeletesExactAttemptAndFinalizesExactIntent() {
		UUID assetId = UUID.randomUUID();
		UUID token = UUID.randomUUID();
		String quarantine = "quarantine/media/" + assetId + "/source.png";
		var target = new MediaUploadCleanupStateService.CleanupTarget(
				assetId,
				quarantine,
				LocalDateTime.now(ZoneOffset.UTC).minusHours(1),
				null,
				token,
				LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1)
		);
		when(stateService.getPendingTarget(assetId)).thenReturn(Optional.of(target));
		when(presignedUploadWriteWindow.isSafeToDelete(target.createdAt())).thenReturn(true);

		worker.clean(assetId);

		verify(storageClient).deleteFolder("verified/media/" + assetId + "/attempts");
		verify(storageClient).deleteFolder("media/" + assetId + "/attempts");
		verify(stateService).finish(target);
		verify(stateService, never()).defer(any());
	}

	@Test
	void readyProtectedRecoveryWaitsForWriteWindowThenDeletesOnlyMutableSource() {
		UUID assetId = UUID.randomUUID();
		LocalDateTime createdAt = LocalDateTime.now();
		var target = new MediaProtectedUploadRecoveryRepository.RecoveryTarget(
				assetId,
				"protected/private-verified/media/" + assetId + "/source.mp3",
				createdAt
		);
		when(presignedUploadWriteWindow.isSafeToDelete(createdAt))
				.thenReturn(false, true);

		worker.cleanProtectedMutableSource(target);
		worker.cleanProtectedMutableSource(target);

		verify(storageClient).deleteObject("protected/media/" + assetId + "/source.mp3");
		verify(storageClient, never()).deleteObject(target.privateVerifiedKey());
	}

	@Test
	void failedProtectedMutableDeleteIsRetriedOnTheNextSweep() {
		UUID assetId = UUID.randomUUID();
		LocalDateTime createdAt = LocalDateTime.now().minusHours(1);
		String mutableKey = "protected/media/" + assetId + "/source.mp3";
		var target = new MediaProtectedUploadRecoveryRepository.RecoveryTarget(
				assetId,
				"protected/private-verified/media/" + assetId + "/source.mp3",
				createdAt
		);
		when(presignedUploadWriteWindow.isSafeToDelete(createdAt)).thenReturn(true);
		doThrow(new IllegalStateException("temporary storage outage"))
				.doNothing()
				.when(storageClient).deleteObject(mutableKey);

		worker.cleanProtectedMutableSource(target);
		worker.cleanProtectedMutableSource(target);

		verify(storageClient, times(2)).deleteObject(mutableKey);
	}
}

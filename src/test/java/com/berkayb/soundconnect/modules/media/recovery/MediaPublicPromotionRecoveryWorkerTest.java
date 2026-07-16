package com.berkayb.soundconnect.modules.media.recovery;

import com.berkayb.soundconnect.modules.media.storage.PresignedUploadWriteWindow;
import com.berkayb.soundconnect.modules.media.storage.StorageClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MediaPublicPromotionRecoveryWorkerTest {

	@Mock StorageClient storageClient;
	@Mock PresignedUploadWriteWindow presignedUploadWriteWindow;
	@InjectMocks MediaPublicPromotionRecoveryWorker worker;

	@Test
	void deletesImmutableCompanionButDefersClientWritableQuarantineUntilExpiry() {
		UUID assetId = UUID.randomUUID();
		LocalDateTime createdAt = LocalDateTime.now();
		var target = target(assetId, createdAt);
		when(presignedUploadWriteWindow.isSafeToDelete(createdAt)).thenReturn(false);

		worker.recover(target);

		verify(storageClient).deleteObject("verified/" + target.publicKey());
		verify(storageClient, never()).deleteObject("quarantine/" + target.publicKey());
		verify(storageClient, never()).deleteObject(target.publicKey());
	}

	@Test
	void deletesBothPrivateCompanionsAfterPresignedWriteWindow() {
		UUID assetId = UUID.randomUUID();
		LocalDateTime createdAt = LocalDateTime.now().minusHours(1);
		var target = target(assetId, createdAt);
		when(presignedUploadWriteWindow.isSafeToDelete(createdAt)).thenReturn(true);

		worker.recover(target);

		verify(storageClient).deleteObject("verified/" + target.publicKey());
		verify(storageClient).deleteObject("quarantine/" + target.publicKey());
		verify(storageClient, never()).deleteObject(target.publicKey());
	}

	@Test
	void oneCompanionFailureDoesNotPreventTheOtherIdempotentDelete() {
		UUID assetId = UUID.randomUUID();
		LocalDateTime createdAt = LocalDateTime.now().minusHours(1);
		var target = target(assetId, createdAt);
		when(presignedUploadWriteWindow.isSafeToDelete(createdAt)).thenReturn(true);
		doThrow(new IllegalStateException("storage unavailable"))
				.when(storageClient).deleteObject("verified/" + target.publicKey());

		worker.recover(target);

		verify(storageClient).deleteObject("quarantine/" + target.publicKey());
		verify(storageClient, never()).deleteObject(target.publicKey());
	}

	@Test
	void refusesReservedPrivateOriginKeyWithoutDerivingDeletionTargets() {
		worker.recover(new MediaPublicPromotionRecoveryTarget(
				UUID.randomUUID(), "verified/media/id/source.jpg", LocalDateTime.now()));

		verifyNoInteractions(storageClient, presignedUploadWriteWindow);
	}

	private static MediaPublicPromotionRecoveryTarget target(
			UUID assetId,
			LocalDateTime createdAt
	) {
		return new MediaPublicPromotionRecoveryTarget(
				assetId, "media/" + assetId + "/source.jpg", createdAt);
	}
}

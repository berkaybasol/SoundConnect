package com.berkayb.soundconnect.modules.media.verification;

import com.berkayb.soundconnect.modules.media.storage.StorageClient;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class MediaUploadVerificationOrphanSweeperTest {

	@Test
	void privateWinnerIsPreservedWhileExpiredAttemptSiblingsAreSwept() {
		UUID assetId = UUID.randomUUID();
		UUID tokenB = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
		String winner = "protected/private-verified/media/" + assetId
				+ "/attempts/" + tokenB + "/source.png";
		String prefix = "protected/private-verified/media/" + assetId + "/attempts";
		String winnerSubtree = "protected/private-verified/media/" + assetId
				+ "/attempts/" + tokenB;
		MediaUploadVerificationOrphanTarget target =
				new MediaUploadVerificationOrphanTarget(assetId, winner, LocalDateTime.now());
		MediaUploadVerificationOrphanRepository repository =
				mock(MediaUploadVerificationOrphanRepository.class);
		StorageClient storage = mock(StorageClient.class);
		MediaUploadVerificationOrphanSweeper sweeper = new MediaUploadVerificationOrphanSweeper(
				repository, storage, new MediaUploadVerificationProperties(), Runnable::run);

		sweeper.sweep(target);

		verify(storage).deleteFolderExceptPrefix(prefix, winnerSubtree);
		verify(repository).markSwept(target);
	}

	@Test
	void publicWinnerKeepsExactPublishedAttemptAndRemovesEveryPrivateOrphan() {
		UUID assetId = UUID.randomUUID();
		UUID tokenB = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
		String winner = "media/" + assetId + "/attempts/" + tokenB + "/source.png";
		String privatePrefix = "verified/media/" + assetId + "/attempts";
		String publicPrefix = "media/" + assetId + "/attempts";
		String privateWinnerSubtree = "verified/media/" + assetId + "/attempts/" + tokenB;
		String publicWinnerSubtree = "media/" + assetId + "/attempts/" + tokenB;
		MediaUploadVerificationOrphanTarget target =
				new MediaUploadVerificationOrphanTarget(assetId, winner, LocalDateTime.now());
		MediaUploadVerificationOrphanRepository repository =
				mock(MediaUploadVerificationOrphanRepository.class);
		StorageClient storage = mock(StorageClient.class);
		MediaUploadVerificationOrphanSweeper sweeper = new MediaUploadVerificationOrphanSweeper(
				repository, storage, new MediaUploadVerificationProperties(), Runnable::run);

		sweeper.sweep(target);

		verify(storage).deleteFolderExceptPrefix(privatePrefix, privateWinnerSubtree);
		verify(storage).deleteFolderExceptPrefix(publicPrefix, publicWinnerSubtree);
		verify(repository).markSwept(target);
	}
}

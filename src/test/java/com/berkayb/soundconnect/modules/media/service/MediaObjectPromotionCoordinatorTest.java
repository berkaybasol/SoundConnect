package com.berkayb.soundconnect.modules.media.service;

import com.berkayb.soundconnect.modules.media.storage.StorageClient;
import com.berkayb.soundconnect.modules.media.image.MediaImageVariantProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class MediaObjectPromotionCoordinatorTest {

	@Mock
	StorageClient storageClient;

	MediaObjectPromotionCoordinator coordinator;

	@BeforeEach
	void setUp() {
		coordinator = new MediaObjectPromotionCoordinator(storageClient, new MediaImageVariantProperties());
	}

	@AfterEach
	void clearTransactionState() {
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.clearSynchronization();
		}
		TransactionSynchronizationManager.setActualTransactionActive(false);
	}

	@Test
	void promotionRunsOutsideTransactionAndUsesDeterministicKeys() {
		String quarantineKey = "quarantine/media/id/source.png";
		UUID attemptToken = UUID.fromString("11111111-1111-1111-1111-111111111111");
		String immutableKey = coordinator.snapshotUpload(quarantineKey, "etag-v1", attemptToken);

		String publicKey = coordinator.promoteAfterValidation(
				immutableKey, "image/png", "verified-etag-v1");

		assertThat(immutableKey).isEqualTo(
				"verified/media/id/attempts/11111111-1111-1111-1111-111111111111/source.png");
		assertThat(publicKey).isEqualTo(
				"media/id/attempts/11111111-1111-1111-1111-111111111111/source.png");
		verify(storageClient).copyUploadToImmutable(quarantineKey, immutableKey, "etag-v1");
		verify(storageClient).promoteVerifiedObject(
				immutableKey, publicKey, "image/png",
				"public, max-age=300, s-maxage=3600, stale-while-revalidate=60",
				"verified-etag-v1");
		verify(storageClient, never()).deleteObject(quarantineKey);
		verify(storageClient, never()).deleteObject(immutableKey);
		verify(storageClient, never()).deleteObject(quarantineKey);
		verify(storageClient, never()).deleteObject(immutableKey);
		verify(storageClient, never()).deleteObject(publicKey);
	}

	@Test
	void retriesReuseDeterministicCopiesForDurableRowDrivenRecovery() {
		String quarantineKey = "quarantine/media/id/source.png";
		UUID attemptToken = UUID.fromString("22222222-2222-2222-2222-222222222222");
		String immutableKey = coordinator.snapshotUpload(quarantineKey, "etag-v1", attemptToken);
		String publicKey = coordinator.promoteAfterValidation(
				immutableKey, "image/png", "verified-etag-v1");

		verify(storageClient, never()).deleteObject(immutableKey);
		verify(storageClient, never()).deleteObject(publicKey);
		verify(storageClient, never()).deleteObject(quarantineKey);
	}

	@Test
	void protectedSnapshotRetainsMutableSourceUntilExpiryAwareRecovery() {
		String mutableKey = "protected/media/private-id/source.mp3";
		UUID attemptToken = UUID.fromString("33333333-3333-3333-3333-333333333333");

		String immutableKey = coordinator.snapshotUpload(mutableKey, "etag-private", attemptToken);

		assertThat(immutableKey)
				.isEqualTo("protected/private-verified/media/private-id/attempts/"
						+ "33333333-3333-3333-3333-333333333333/source.mp3");
		verify(storageClient).copyUploadToImmutable(mutableKey, immutableKey, "etag-private");
		verify(storageClient, never()).deleteObject(mutableKey);
		verify(storageClient, never()).deleteObject(immutableKey);
	}

	@Test
	void snapshotAndPromotionFailClosedInsideDatabaseTransaction() {
		beginTransaction();
		assertThatThrownBy(() -> coordinator.snapshotUpload(
				"quarantine/media/id/source.png", "etag-v1", UUID.randomUUID()))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("forbidden inside a database transaction");
		assertThatThrownBy(() -> coordinator.promoteAfterValidation(
				"verified/media/id/source.png", "image/png", "verified-etag-v1"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("forbidden inside a database transaction");
		verifyNoInteractions(storageClient);
	}

	@Test
	void generatedVariantUsesImmutableCacheOutsideTransaction() {
		Path local = Path.of("thumbnail.jpg");
		String publicKey = "media/id/thumbnail.jpg";

		coordinator.storeGeneratedPublicObject(local, publicKey, "image/jpeg");

		verify(storageClient).putFile(
				local, publicKey, "image/jpeg",
				"public, max-age=300, s-maxage=3600, stale-while-revalidate=60");
		verify(storageClient, never()).deleteObject(publicKey);
	}

	private static void beginTransaction() {
		TransactionSynchronizationManager.setActualTransactionActive(true);
		TransactionSynchronizationManager.initSynchronization();
	}

	private static void complete(int completionStatus) {
		var synchronizations = TransactionSynchronizationManager.getSynchronizations();
		if (completionStatus == TransactionSynchronization.STATUS_COMMITTED) {
			synchronizations.forEach(TransactionSynchronization::afterCommit);
		}
		synchronizations.forEach(sync -> sync.afterCompletion(completionStatus));
	}
}

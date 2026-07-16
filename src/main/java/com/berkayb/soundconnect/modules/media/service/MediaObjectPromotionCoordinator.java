package com.berkayb.soundconnect.modules.media.service;

import com.berkayb.soundconnect.modules.media.image.MediaImageVariantProperties;
import com.berkayb.soundconnect.modules.media.storage.StorageClient;
import com.berkayb.soundconnect.modules.media.storage.StorageObjectKeys;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.file.Path;

/**
 * Performs deterministic object copies only outside database transactions.
 * The caller must first commit a durable VERIFYING fence and later finalize the
 * row in a separate short transaction. This guard prevents future call sites
 * from accidentally holding a row lock across object-store latency.
 */
@Service
@RequiredArgsConstructor
public class MediaObjectPromotionCoordinator {

	private final StorageClient storageClient;
	private final MediaImageVariantProperties imageVariantProperties;

	/**
	 * Freezes the exact ETag-selected upload into a key for which the client has
	 * never received write authority. All validation and processing must use the
	 * returned key rather than the reusable presigned-upload destination.
	 */
	public String snapshotUpload(String uploadKey, String expectedETag, java.util.UUID attemptToken) {
		assertOutsideTransaction();
		String immutableKey = StorageObjectKeys.immutableKeyForUpload(uploadKey, attemptToken);
		storageClient.copyUploadToImmutable(uploadKey, immutableKey, expectedETag);
		return immutableKey;
	}

	public String promoteAfterValidation(
			String immutableKey,
			String contentType,
			String expectedVerifiedETag
	) {
		assertOutsideTransaction();
		String publicKey = StorageObjectKeys.publicKeyForVerified(immutableKey);
		storageClient.promoteVerifiedObject(
				immutableKey,
				publicKey,
				contentType,
				imageVariantProperties.getPublicCacheControl(),
				expectedVerifiedETag
		);
		return publicKey;
	}

	/**
	 * Stores a deterministic generated variant and removes it if the enclosing
	 * database transaction rolls back. Generated variants are public-only and
	 * inherit the same immutable cache policy as their immutable source.
	 */
	public void storeGeneratedPublicObject(Path local, String publicKey, String contentType) {
		assertOutsideTransaction();
		if (StorageObjectKeys.isPrivateOrigin(publicKey)) {
			throw new IllegalArgumentException("Generated public media cannot use a private-origin key");
		}
		storageClient.putFile(
				local,
				publicKey,
				contentType,
				imageVariantProperties.getPublicCacheControl()
		);
	}

	private void assertOutsideTransaction() {
		if (TransactionSynchronizationManager.isActualTransactionActive()) {
			throw new IllegalStateException("Media object storage I/O is forbidden inside a database transaction");
		}
	}
}

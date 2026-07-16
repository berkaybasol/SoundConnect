package com.berkayb.soundconnect.modules.media.image;

import com.berkayb.soundconnect.modules.media.storage.StorageClient;
import com.berkayb.soundconnect.modules.media.storage.StorageObjectKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.UUID;

@Service
@ConditionalOnImageVariantWorker
@RequiredArgsConstructor
@Slf4j
public class ImageThumbnailService {

	private static final String THUMBNAIL_CONTENT_TYPE = "image/jpeg";

	private final StorageClient storageClient;
	private final ImageThumbnailProcessor thumbnailProcessor;
	private final MediaImageVariantProperties properties;

	/** Images keep the short storage-operation deadline; large video uses the
	 * transcode-specific download deadline through the two-argument method. */
	@Value("${media.upload-verification.storage-call-timeout:PT30S}")
	private Duration sourceDownloadTimeout = Duration.ofSeconds(30);

	/**
	 * Backfill variant generation deliberately runs without a database
	 * transaction. The deterministic key makes concurrent attempts safe: they
	 * overwrite the same derived object, and a short transaction later rechecks
	 * eligibility before attaching its URL. A crash between upload and attach is
	 * repaired by the next idempotent backfill pass.
	 */
	public ImageThumbnailResult generateAndStoreDetached(UUID assetId, String sourceStorageKey)
			throws IOException, InterruptedException {
		String thumbnailKey = thumbnailKeyFor(sourceStorageKey);
		String thumbnailUrl = storageClient.publicUrl(thumbnailKey);
		Path workDirectory = Files.createTempDirectory("soundconnect-image-" + assetId + "-");
		try {
			Path source = workDirectory.resolve("source");
			Path thumbnail = workDirectory.resolve("thumbnail.jpg");
			storageClient.downloadToFile(sourceStorageKey, source, sourceDownloadTimeout);
			ProcessedImageThumbnail processed = thumbnailProcessor.createThumbnail(source, thumbnail);
			storageClient.putFile(
					thumbnail,
					thumbnailKey,
					THUMBNAIL_CONTENT_TYPE,
					properties.getPublicCacheControl());
			log.info("[media-image] thumbnail generated assetId={} source={}x{} thumbnail={}x{}",
					assetId,
					processed.sourceWidth(), processed.sourceHeight(),
					processed.thumbnailWidth(), processed.thumbnailHeight());
			return new ImageThumbnailResult(
					thumbnailKey,
					thumbnailUrl,
					processed.sourceWidth(),
					processed.sourceHeight(),
					processed.thumbnailWidth(),
					processed.thumbnailHeight());
		} finally {
			deleteTreeBestEffort(workDirectory);
		}
	}

	public static String thumbnailKeyFor(String sourceStorageKey) {
		String publicSourceKey;
		if (StorageObjectKeys.isVerified(sourceStorageKey)) {
			publicSourceKey = StorageObjectKeys.publicKeyForVerified(sourceStorageKey);
		} else if (StorageObjectKeys.isQuarantined(sourceStorageKey)) {
			publicSourceKey = StorageObjectKeys.publicKeyForQuarantine(sourceStorageKey);
		} else {
			publicSourceKey = sourceStorageKey;
		}
		if (StorageObjectKeys.isPrivateOrigin(publicSourceKey)) {
			throw new IllegalArgumentException("Image thumbnail source must have public delivery intent");
		}
		int lastSlash = publicSourceKey.lastIndexOf('/');
		if (lastSlash <= 0) {
			throw new IllegalArgumentException("Image source key has no asset directory");
		}
		return publicSourceKey.substring(0, lastSlash + 1) + "thumbnail.jpg";
	}

	/** Deletes an unattached deterministic variant with bounded retries. */
	public void deleteStoredVariant(String thumbnailKey) {
		RuntimeException finalFailure = null;
		for (int attempt = 1; attempt <= 3; attempt++) {
			try {
				storageClient.deleteObject(thumbnailKey);
				return;
			} catch (RuntimeException cleanupFailure) {
				finalFailure = cleanupFailure;
			}
		}
		throw new IllegalStateException(
				"Generated image variant cleanup failed after bounded retries", finalFailure);
	}

	private void deleteTreeBestEffort(Path root) {
		try (var paths = Files.walk(root)) {
			paths.sorted(Comparator.reverseOrder()).forEach(path -> {
				try {
					Files.deleteIfExists(path);
				} catch (IOException cleanupFailure) {
					log.warn("[media-image] temporary file cleanup failed exceptionType={}",
							cleanupFailure.getClass().getSimpleName());
				}
			});
		} catch (IOException cleanupFailure) {
			log.warn("[media-image] temporary directory cleanup failed exceptionType={}",
					cleanupFailure.getClass().getSimpleName());
		}
	}
}

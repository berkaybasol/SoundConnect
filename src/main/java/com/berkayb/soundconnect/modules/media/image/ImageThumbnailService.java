package com.berkayb.soundconnect.modules.media.image;

import com.berkayb.soundconnect.modules.media.telemetry.MediaWorkerMetrics;
import org.springframework.beans.factory.annotation.Autowired;
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
	private MediaWorkerMetrics telemetry = MediaWorkerMetrics.noop();
	@Autowired void setTelemetry(MediaWorkerMetrics telemetry) { this.telemetry = telemetry; }

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
		var sample = telemetry.startJob(MediaWorkerMetrics.Job.IMAGE_THUMBNAIL);
		var outcome = MediaWorkerMetrics.JobOutcome.FAILURE;
		Path workDirectory = null;
		try {
			boolean protectedImage = StorageObjectKeys.isPrivateVerified(sourceStorageKey);
			String thumbnailKey = protectedImage
					? protectedThumbnailKeyFor(sourceStorageKey) : thumbnailKeyFor(sourceStorageKey);
			String thumbnailUrl = protectedImage ? null : storageClient.publicUrl(thumbnailKey);
			workDirectory = Files.createTempDirectory("soundconnect-image-" + assetId + "-");
			Path source = workDirectory.resolve("source");
			Path thumbnail = workDirectory.resolve("thumbnail.jpg");
			storageClient.downloadToFile(sourceStorageKey, source, sourceDownloadTimeout);
			ProcessedImageThumbnail processed = thumbnailProcessor.createThumbnail(source, thumbnail);
			storageClient.putFile(
					thumbnail,
					thumbnailKey,
					THUMBNAIL_CONTENT_TYPE,
					protectedImage ? "private,no-store,max-age=0" : properties.getPublicCacheControl());
			log.info("[media-image] thumbnail generated assetId={} source={}x{} thumbnail={}x{}",
					assetId,
					processed.sourceWidth(), processed.sourceHeight(),
					processed.thumbnailWidth(), processed.thumbnailHeight());
			var result = new ImageThumbnailResult(
					thumbnailKey,
					thumbnailUrl,
					processed.sourceWidth(),
					processed.sourceHeight(),
					processed.thumbnailWidth(),
					processed.thumbnailHeight());
			outcome = MediaWorkerMetrics.JobOutcome.SUCCESS;
			return result;
		} catch (InterruptedException interrupted) {
			outcome = MediaWorkerMetrics.JobOutcome.INTERRUPTED;
			throw interrupted;
		} finally {
			try { if (workDirectory != null) deleteTreeBestEffort(workDirectory); }
			catch (RuntimeException cleanupFailure) {
				outcome = MediaWorkerMetrics.JobOutcome.FAILURE;
				throw cleanupFailure;
			}
			finally { sample.finish(outcome); }
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

	/** A derivative stays inside the validated, server-only winning attempt. */
	public static String protectedThumbnailKeyFor(String sourceStorageKey) {
		StorageObjectKeys.physicalKey(sourceStorageKey); // validates path segments
		if (!StorageObjectKeys.isPrivateVerified(sourceStorageKey)) {
			throw new IllegalArgumentException("Protected image thumbnail requires an immutable source");
		}
		StorageObjectKeys.mutableUploadKeyForPrivateVerified(sourceStorageKey);
		return sourceStorageKey.substring(0, sourceStorageKey.lastIndexOf('/') + 1) + "thumbnail.jpg";
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

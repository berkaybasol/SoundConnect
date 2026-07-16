package com.berkayb.soundconnect.modules.media.verification;

import com.berkayb.soundconnect.modules.media.abuse.MediaUploadAbuseGuard;
import com.berkayb.soundconnect.modules.media.entity.MediaAsset;
import com.berkayb.soundconnect.modules.media.enums.MediaKind;
import com.berkayb.soundconnect.modules.media.enums.MediaOwnerType;
import com.berkayb.soundconnect.modules.media.enums.MediaStatus;
import com.berkayb.soundconnect.modules.media.enums.MediaVisibility;
import com.berkayb.soundconnect.modules.media.image.ImageThumbnailRequestedEvent;
import com.berkayb.soundconnect.modules.media.repository.MediaAssetRepository;
import com.berkayb.soundconnect.modules.media.service.MediaObjectPromotionCoordinator;
import com.berkayb.soundconnect.modules.media.service.MediaUploadFailureHandler;
import com.berkayb.soundconnect.modules.media.storage.MediaContentSignatureValidator;
import com.berkayb.soundconnect.modules.media.storage.MediaMimeType;
import com.berkayb.soundconnect.modules.media.storage.MediaPolicy;
import com.berkayb.soundconnect.modules.media.storage.StorageClient;
import com.berkayb.soundconnect.modules.media.storage.StorageObjectKeys;
import com.berkayb.soundconnect.modules.media.storage.StorageObjectMetadata;
import com.berkayb.soundconnect.modules.media.transcode.MediaTranscodeQueuedEvent;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.RateLimitedException;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Split-transaction upload completion with a durable cross-node fence.
 *
 * <p>A short row-lock transaction claims a unique attempt token and lease. All
 * object-store I/O runs on a bounded executor without a database transaction.
 * A second short transaction finalizes only when the same token still owns the
 * row. The HTTP request waits briefly for the common fast path; after that the
 * durable worker and scheduled crash recovery continue independently.</p>
 */
@Service
@Slf4j
public class MediaUploadVerificationCoordinator {

	private final MediaAssetRepository mediaAssetRepository;
	private final StorageClient storageClient;
	private final MediaPolicy mediaPolicy;
	private final MediaObjectPromotionCoordinator promotionCoordinator;
	private final MediaUploadFailureHandler failureHandler;
	private final MediaUploadAbuseGuard abuseGuard;
	private final ApplicationEventPublisher eventPublisher;
	private final TaskExecutor executor;
	private final MediaUploadVerificationProperties properties;
	private final TransactionTemplate transactionTemplate;
	private final ConcurrentHashMap<UUID, CompletableFuture<MediaAsset>> inFlight =
			new ConcurrentHashMap<>();

	public MediaUploadVerificationCoordinator(
			MediaAssetRepository mediaAssetRepository,
			StorageClient storageClient,
			MediaPolicy mediaPolicy,
			MediaObjectPromotionCoordinator promotionCoordinator,
			MediaUploadFailureHandler failureHandler,
			MediaUploadAbuseGuard abuseGuard,
			ApplicationEventPublisher eventPublisher,
			@Qualifier("mediaUploadVerificationExecutor") TaskExecutor executor,
			MediaUploadVerificationProperties properties,
			PlatformTransactionManager transactionManager
	) {
		this.mediaAssetRepository = mediaAssetRepository;
		this.storageClient = storageClient;
		this.mediaPolicy = mediaPolicy;
		this.promotionCoordinator = promotionCoordinator;
		this.failureHandler = failureHandler;
		this.abuseGuard = abuseGuard;
		this.eventPublisher = eventPublisher;
		this.executor = executor;
		this.properties = properties;
		this.transactionTemplate = new TransactionTemplate(transactionManager);
		this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	public MediaAsset complete(
			UUID assetId,
			MediaOwnerType expectedOwnerType,
			UUID expectedOwnerId
	) {
		CompletableFuture<MediaAsset> result = submit(
				new WorkRequest(assetId, expectedOwnerType, expectedOwnerId), true);
		try {
			return result.get(requestWaitMillis(), TimeUnit.MILLISECONDS);
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Upload verification was interrupted", interrupted);
		} catch (TimeoutException timeout) {
			log.debug("[media] upload verification continues after HTTP wait timeout assetId={}", assetId);
			throw new SoundConnectException(ErrorType.MEDIA_ASSET_NOT_READY);
		} catch (ExecutionException failed) {
			Throwable cause = failed.getCause();
			if (cause instanceof RuntimeException runtimeException) throw runtimeException;
			throw new IllegalStateException("Upload verification failed", cause);
		}
	}

	@Scheduled(
			initialDelayString = "${media.upload-verification.recovery-initial-delay-ms:15000}",
			fixedDelayString = "${media.upload-verification.recovery-fixed-delay-ms:30000}"
	)
	public void recoverPending() {
		if (!properties.isEnabled()) return;
		int batchSize = Math.max(1, Math.min(properties.getRecoveryBatchSize(), 500));
		LocalDateTime now = utcNow();
		mediaAssetRepository.findByStatusOrderByUpdatedAtAsc(
				MediaStatus.VERIFYING,
				PageRequest.of(0, Math.min(500, batchSize * 5))
		).stream()
				.filter(asset -> leaseExpired(asset, now))
				.limit(batchSize)
				.forEach(asset -> submit(new WorkRequest(asset.getId(), null, null), false));
	}

	/**
	 * The map is the per-instance/per-asset gate. A repeated request never joins
	 * an existing slow future and therefore cannot multiply servlet waiters.
	 * A lightweight durable-lease read gives duplicate requests a stable result;
	 * executor acceptance remains the global bounded gate for actual work, before
	 * the row-lock claim and every object-store call.
	 */
	private CompletableFuture<MediaAsset> submit(WorkRequest request, boolean requestThread) {
		UUID assetId = request.assetId();
		if (requestThread) {
			assertRequestMayEnterLocalExecutor(request);
		}
		CompletableFuture<MediaAsset> created = new CompletableFuture<>();
		CompletableFuture<MediaAsset> existing = inFlight.putIfAbsent(assetId, created);
		if (existing != null) {
			if (requestThread) throw new SoundConnectException(ErrorType.MEDIA_ASSET_NOT_READY);
			return existing;
		}

		try {
			executor.execute(() -> {
				try {
					created.complete(completeWork(request));
				} catch (RuntimeException failure) {
					created.completeExceptionally(failure);
				} finally {
					inFlight.remove(assetId, created);
				}
			});
		} catch (TaskRejectedException rejected) {
			inFlight.remove(assetId, created);
			if (requestThread) {
				// Close the preflight/admission race: another node may have claimed
				// the durable lease after our first read but before local rejection.
				assertRequestMayEnterLocalExecutor(request);
				throw new RateLimitedException(ErrorType.MEDIA_UPLOAD_CONCURRENCY_LIMITED, 1L);
			}
			created.completeExceptionally(rejected);
			log.warn("[media] verification recovery deferred by bounded executor assetId={}", assetId);
		} catch (RuntimeException submissionFailure) {
			inFlight.remove(assetId, created);
			created.completeExceptionally(submissionFailure);
			if (requestThread) throw submissionFailure;
			log.warn("[media] verification recovery submission failed assetId={} exceptionType={}",
					assetId, submissionFailure.getClass().getSimpleName());
		}
		return created;
	}

	/**
	 * A live durable lease belongs to some already-admitted worker (possibly on a
	 * different node). Check it on the request thread before competing for this
	 * node's bounded executor so duplicate completion has one stable API result:
	 * MEDIA_ASSET_NOT_READY, never an unrelated local-capacity response.
	 */
	private void assertRequestMayEnterLocalExecutor(WorkRequest request) {
		MediaAsset observed = mediaAssetRepository.findById(request.assetId())
				.orElseThrow(() -> new SoundConnectException(ErrorType.MEDIA_ASSET_NOT_FOUND));
		if (request.expectedOwnerType() != null && request.expectedOwnerId() != null
				&& (observed.getOwnerType() != request.expectedOwnerType()
				|| !Objects.equals(observed.getOwnerId(), request.expectedOwnerId()))) {
			throw new SoundConnectException(ErrorType.MEDIA_ASSET_NOT_FOUND);
		}
		if (observed.getStatus() == MediaStatus.VERIFYING
				&& !leaseExpired(observed, utcNow())) {
			throw new SoundConnectException(ErrorType.MEDIA_ASSET_NOT_READY);
		}
	}

	private MediaAsset completeWork(WorkRequest request) {
		ClaimResult claim = claim(request);
		if (claim.completed() != null) return claim.completed();
		return verifyOutsideTransaction(claim.target());
	}

	private ClaimResult claim(WorkRequest request) {
		return transactionTemplate.execute(status -> {
			MediaAsset asset = mediaAssetRepository.findByIdForUpdate(request.assetId())
					.orElseThrow(() -> new SoundConnectException(ErrorType.MEDIA_ASSET_NOT_FOUND));
			if (request.expectedOwnerType() != null && request.expectedOwnerId() != null
					&& (asset.getOwnerType() != request.expectedOwnerType()
					|| !Objects.equals(asset.getOwnerId(), request.expectedOwnerId()))) {
				throw new SoundConnectException(ErrorType.MEDIA_ASSET_NOT_FOUND);
			}
			if (isCompleted(asset.getStatus())) {
				abuseGuard.releaseAfterCommit(asset.getId());
				return new ClaimResult(null, asset);
			}

			LocalDateTime now = utcNow();
			if (asset.getStatus() == MediaStatus.VERIFYING && !leaseExpired(asset, now)) {
				throw new SoundConnectException(ErrorType.MEDIA_ASSET_NOT_READY);
			}
			if (asset.getStatus() != MediaStatus.UPLOADING
					&& asset.getStatus() != MediaStatus.VERIFYING) {
				throw new SoundConnectException(ErrorType.MEDIA_ASSET_STATE_INVALID);
			}
			if (!StringUtils.hasText(asset.getStorageKey())) {
				throw new SoundConnectException(ErrorType.MEDIA_ASSET_STATE_INVALID);
			}

			UUID attemptToken = UUID.randomUUID();
			LocalDateTime attemptDeadline = now.plus(attemptHardTimeout());
			LocalDateTime cleanupNotBefore = attemptDeadline
					.plus(storageCallTimeout())
					.plus(Duration.ofMillis(
							Math.max(1_000L, properties.getRecoveryFixedDelayMs())))
					.plusSeconds(30);
			if (asset.getUploadVerificationCleanupNotBefore() != null
					&& asset.getUploadVerificationCleanupNotBefore().isAfter(cleanupNotBefore)) {
				cleanupNotBefore = asset.getUploadVerificationCleanupNotBefore();
			}
			asset.setStatus(MediaStatus.VERIFYING);
			asset.setUploadVerificationAttemptToken(attemptToken);
			asset.setUploadVerificationLeaseExpiresAt(now.plus(verificationLease()));
			asset.setUploadVerificationAttemptDeadline(attemptDeadline);
			asset.setUploadVerificationCleanupNotBefore(cleanupNotBefore);
			asset.setSourceUrl(null);
			asset.setPlaybackUrl(null);
			asset.setThumbnailUrl(null);
			mediaAssetRepository.save(asset);
			return new ClaimResult(toTarget(asset, attemptToken, attemptDeadline), null);
		});
	}

	private MediaAsset verifyOutsideTransaction(VerificationTarget target) {
		assertNoTransaction();
		try {
			assertStorageRouteMatchesVisibility(target);
			StorageObjectMetadata metadata = storageClient.getObjectMetadata(target.mutableKey())
					.orElseThrow(() -> new SoundConnectException(
							ErrorType.MEDIA_STORAGE_OBJECT_NOT_FOUND));
			String actualMimeType = validateMutableMetadata(target, metadata);

			assertAttemptWriteAuthorized(target);
			String immutableKey = promotionCoordinator.snapshotUpload(
					target.mutableKey(), metadata.eTag(), target.attemptToken());
			StorageObjectMetadata immutableMetadata = storageClient.getObjectMetadata(immutableKey)
					.orElseThrow(() -> new SoundConnectException(
							ErrorType.MEDIA_STORAGE_OBJECT_NOT_FOUND));
			String immutableMimeType = MediaMimeType.sanitize(immutableMetadata.contentType());
			if (!StringUtils.hasText(immutableMetadata.eTag())
					|| !MediaMimeType.equivalent(actualMimeType, immutableMimeType)
					|| immutableMetadata.sizeBytes() != metadata.sizeBytes()) {
				throw new SoundConnectException(ErrorType.MEDIA_UPLOAD_METADATA_MISMATCH);
			}
			validateStoredContentSignature(immutableKey, actualMimeType);

			String committedKey = immutableKey;
			String publicUrl = null;
			if (target.kind() != MediaKind.VIDEO
					&& target.visibility() == MediaVisibility.PUBLIC) {
				assertAttemptWriteAuthorized(target);
				committedKey = promotionCoordinator.promoteAfterValidation(
						immutableKey, actualMimeType, immutableMetadata.eTag());
				publicUrl = storageClient.publicUrl(committedKey);
			}
			return finalizeVerified(
					target, committedKey, actualMimeType, metadata.sizeBytes(), publicUrl);
		} catch (SoundConnectException rejected) {
			if (isPermanentUploadRejection(rejected.getErrorType())) {
				failureHandler.recordRejectedUpload(
						target.assetId(), target.mutableKey(), target.attemptToken());
			}
			throw rejected;
		}
	}

	private MediaAsset finalizeVerified(
			VerificationTarget target,
			String committedKey,
			String actualMimeType,
			long actualSize,
			String publicUrl
	) {
		return transactionTemplate.execute(status -> {
			MediaAsset asset = mediaAssetRepository.findByIdForUpdate(target.assetId())
					.orElseThrow(() -> new SoundConnectException(ErrorType.MEDIA_ASSET_NOT_FOUND));
			if (isCompleted(asset.getStatus())) return asset;
			if (asset.getStatus() != MediaStatus.VERIFYING
					|| !Objects.equals(asset.getStorageKey(), target.mutableKey())
					|| !Objects.equals(
							asset.getUploadVerificationAttemptToken(), target.attemptToken())
					|| asset.getUploadVerificationAttemptDeadline() == null
					|| !utcNow().isBefore(asset.getUploadVerificationAttemptDeadline())) {
				throw new SoundConnectException(ErrorType.MEDIA_ASSET_NOT_READY);
			}

			asset.setStorageKey(committedKey);
			asset.setMimeType(actualMimeType);
			asset.setSize(actualSize);
			asset.setUploadVerificationLeaseExpiresAt(null);
			if (asset.getKind() == MediaKind.VIDEO) {
				asset.setSourceUrl(null);
				asset.setPlaybackUrl(null);
				asset.setThumbnailUrl(null);
				asset.setStatus(MediaStatus.TRANSCODE_QUEUED);
				mediaAssetRepository.save(asset);
				eventPublisher.publishEvent(new MediaTranscodeQueuedEvent(asset.getId()));
			} else {
				asset.setStatus(MediaStatus.READY);
				if (asset.getVisibility() == MediaVisibility.PUBLIC) {
					asset.setSourceUrl(publicUrl);
					asset.setPlaybackUrl(publicUrl);
				} else {
					asset.setSourceUrl(null);
					asset.setPlaybackUrl(null);
					asset.setThumbnailUrl(null);
				}
				mediaAssetRepository.save(asset);
				if (asset.getKind() == MediaKind.IMAGE
						&& asset.getVisibility() == MediaVisibility.PUBLIC) {
					eventPublisher.publishEvent(new ImageThumbnailRequestedEvent(asset.getId()));
				}
			}
			abuseGuard.releaseAfterCommit(asset.getId());
			return asset;
		});
	}

	private String validateMutableMetadata(
			VerificationTarget target,
			StorageObjectMetadata metadata
	) {
		String actualMimeType = MediaMimeType.sanitize(metadata.contentType());
		if (!StringUtils.hasText(actualMimeType)
				|| !StringUtils.hasText(metadata.eTag())
				|| metadata.sizeBytes() <= 0) {
			throw new SoundConnectException(ErrorType.MEDIA_UPLOAD_METADATA_MISMATCH);
		}
		mediaPolicy.validate(target.kind(), actualMimeType, metadata.sizeBytes());
		if (!MediaMimeType.equivalent(target.declaredMimeType(), actualMimeType)
				|| target.declaredSize() == null
				|| !target.declaredSize().equals(metadata.sizeBytes())) {
			throw new SoundConnectException(ErrorType.MEDIA_UPLOAD_METADATA_MISMATCH);
		}
		return actualMimeType;
	}

	private void validateStoredContentSignature(String storageKey, String mimeType) {
		try (InputStream input = storageClient.getObjectStream(storageKey)) {
			if (!MediaContentSignatureValidator.matches(mimeType, input)) {
				throw new SoundConnectException(ErrorType.MEDIA_UPLOAD_METADATA_MISMATCH);
			}
		} catch (SoundConnectException exception) {
			throw exception;
		} catch (IOException exception) {
			throw new IllegalStateException("Stored media content could not be inspected", exception);
		}
	}

	private void assertAttemptWriteAuthorized(VerificationTarget target) {
		transactionTemplate.executeWithoutResult(status -> {
			MediaAsset asset = mediaAssetRepository.findByIdForUpdate(target.assetId())
					.orElseThrow(() -> new SoundConnectException(ErrorType.MEDIA_ASSET_NOT_FOUND));
			LocalDateTime now = utcNow();
			if (asset.getStatus() != MediaStatus.VERIFYING
					|| !Objects.equals(asset.getStorageKey(), target.mutableKey())
					|| !Objects.equals(
							asset.getUploadVerificationAttemptToken(), target.attemptToken())
					|| asset.getUploadVerificationAttemptDeadline() == null
					|| !now.isBefore(asset.getUploadVerificationAttemptDeadline())
					|| !now.isBefore(target.attemptDeadline())) {
				throw new SoundConnectException(ErrorType.MEDIA_ASSET_NOT_READY);
			}
		});
	}

	private static VerificationTarget toTarget(
			MediaAsset asset,
			UUID attemptToken,
			LocalDateTime attemptDeadline
	) {
		return new VerificationTarget(
				asset.getId(), asset.getKind(), asset.getVisibility(),
				asset.getMimeType(), asset.getSize(), asset.getStorageKey(),
				attemptToken, attemptDeadline);
	}

	private static void assertStorageRouteMatchesVisibility(VerificationTarget target) {
		boolean correctlyRouted = target.visibility() == MediaVisibility.PUBLIC
				? StorageObjectKeys.isQuarantined(target.mutableKey())
				: StorageObjectKeys.isProtected(target.mutableKey());
		if (!correctlyRouted) {
			throw new SoundConnectException(ErrorType.MEDIA_UPLOAD_METADATA_MISMATCH);
		}
	}

	private static void assertNoTransaction() {
		if (TransactionSynchronizationManager.isActualTransactionActive()) {
			throw new IllegalStateException(
					"Object storage verification must run outside a database transaction");
		}
	}

	private static boolean isCompleted(MediaStatus status) {
		return status == MediaStatus.READY
				|| status == MediaStatus.PROCESSING
				|| status == MediaStatus.TRANSCODE_QUEUED
				|| status == MediaStatus.TRANSCODE_SENT;
	}

	private static boolean isPermanentUploadRejection(ErrorType errorType) {
		return errorType == ErrorType.MEDIA_STORAGE_OBJECT_NOT_FOUND
				|| errorType == ErrorType.MEDIA_UPLOAD_METADATA_MISMATCH
				|| errorType == ErrorType.MEDIA_UPLOAD_INVALID_REQUEST
				|| errorType == ErrorType.MEDIA_UPLOAD_UNSUPPORTED_MIME
				|| errorType == ErrorType.MEDIA_UPLOAD_SIZE_EXCEEDED;
	}

	private static boolean leaseExpired(MediaAsset asset, LocalDateTime now) {
		LocalDateTime lease = asset.getUploadVerificationLeaseExpiresAt();
		return lease == null || !lease.isAfter(now);
	}

	private long requestWaitMillis() {
		Duration timeout = properties.getRequestWaitTimeout();
		if (timeout == null || timeout.isNegative() || timeout.isZero()) return 1L;
		return Math.max(1L, timeout.toMillis());
	}

	private Duration verificationLease() {
		Duration lease = properties.getVerificationLease();
		if (lease == null || lease.isNegative() || lease.isZero()) {
			throw new IllegalStateException("media.upload-verification.verification-lease must be positive");
		}
		return lease;
	}

	private Duration attemptHardTimeout() {
		Duration timeout = properties.getAttemptHardTimeout();
		if (timeout == null || timeout.isNegative() || timeout.isZero()) {
			throw new IllegalStateException(
					"media.upload-verification.attempt-hard-timeout must be positive");
		}
		return timeout;
	}

	private Duration storageCallTimeout() {
		Duration timeout = properties.getStorageCallTimeout();
		if (timeout == null || timeout.isNegative() || timeout.isZero()) {
			throw new IllegalStateException(
					"media.upload-verification.storage-call-timeout must be positive");
		}
		return timeout;
	}

	private static LocalDateTime utcNow() {
		return LocalDateTime.now(ZoneOffset.UTC);
	}

	private record ClaimResult(VerificationTarget target, MediaAsset completed) {
	}

	private record VerificationTarget(
			UUID assetId,
			MediaKind kind,
			MediaVisibility visibility,
			String declaredMimeType,
			Long declaredSize,
			String mutableKey,
			UUID attemptToken,
			LocalDateTime attemptDeadline
	) {
	}

	private record WorkRequest(
			UUID assetId,
			MediaOwnerType expectedOwnerType,
			UUID expectedOwnerId
	) {
	}
}

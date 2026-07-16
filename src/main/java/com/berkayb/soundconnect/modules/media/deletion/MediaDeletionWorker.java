package com.berkayb.soundconnect.modules.media.deletion;

import com.berkayb.soundconnect.modules.media.enums.MediaKind;
import com.berkayb.soundconnect.modules.media.enums.MediaVisibility;
import com.berkayb.soundconnect.modules.media.image.ImageThumbnailService;
import com.berkayb.soundconnect.modules.media.storage.MediaPolicy;
import com.berkayb.soundconnect.modules.media.storage.PresignedUploadWriteWindow;
import com.berkayb.soundconnect.modules.media.storage.StorageClient;
import com.berkayb.soundconnect.modules.media.storage.StorageObjectKeys;
import com.berkayb.soundconnect.modules.media.service.MediaAssetReferenceGuard;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class MediaDeletionWorker {

	private final MediaDeletionStateService stateService;
	private final StorageClient storageClient;
	private final MediaPolicy mediaPolicy;
	private final PresignedUploadWriteWindow presignedUploadWriteWindow;
	private final MediaAssetReferenceGuard mediaAssetReferenceGuard;

	public void delete(UUID assetId) {
		stateService.getPendingTarget(assetId).ifPresent(this::deleteTarget);
	}

	private void deleteTarget(MediaDeletionStateService.DeletionTarget target) {
		if ((StorageObjectKeys.isClientWritableUpload(target.storageKey())
				|| StorageObjectKeys.hasClientWritablePredecessor(target.storageKey()))
				&& !isUploadWriteWindowClosed(target)) {
			// A user can delete an UPLOADING asset while its PUT signature is still
			// valid. Keep the durable row until a late PUT can no longer resurrect it.
			stateService.defer(target.assetId());
			log.info("[media-delete] waiting for presigned PUT expiry assetId={}", target.assetId());
			return;
		}

		LocalDateTime producerGraceDeadline = producerGraceDeadline(target);
		if (producerGraceDeadline != null
				&& LocalDateTime.now(ZoneOffset.UTC).isBefore(producerGraceDeadline)) {
			// A thumbnail/HLS worker may have taken a READY/PROCESSING snapshot before
			// the row was fenced. Retain the durable deletion marker until every native
			// producer is beyond its hard runtime bound, then perform one final sweep.
			log.info("[media-delete] waiting for producer grace assetId={} deadline={}",
					target.assetId(), producerGraceDeadline);
			return;
		}
		try {
			// Repeat the service-layer preflight immediately before external I/O.
			// Raw UUID relations are not database foreign keys, so this second check
			// closes the attach-after-request race without ever deleting first.
			mediaAssetReferenceGuard.assertNotReferenced(target.assetId());
		} catch (SoundConnectException conflict) {
			stateService.defer(target.assetId());
			if (conflict.getErrorType() == ErrorType.MEDIA_ASSET_IN_USE) {
				log.warn("[media-delete] referenced asset retained assetId={}", target.assetId());
				return;
			}
			throw conflict;
		} catch (RuntimeException referenceCheckFailure) {
			stateService.defer(target.assetId());
			throw referenceCheckFailure;
		}

		RuntimeException deletionFailure = null;
		if (StringUtils.hasText(target.storageKey())) {
			try {
				for (String prefix : StorageObjectKeys.attemptPrefixesFor(target.storageKey())) {
					try {
						// Producer grace/hard-deadline fencing has elapsed, so deletion
						// removes every A/B attempt object without retaining a winner.
						storageClient.deleteFolder(prefix);
					} catch (RuntimeException failure) {
						deletionFailure = combine(deletionFailure, failure);
					}
				}
				for (String key : StorageObjectKeys.abandonedUploadCleanupKeys(
						target.storageKey(), target.uploadVerificationAttemptToken())) {
					deletionFailure = deleteObject(key, deletionFailure);
				}
			} catch (RuntimeException invalidKey) {
				deletionFailure = combine(deletionFailure, invalidKey);
			}
		}
		if (target.kind() == MediaKind.IMAGE
				&& target.visibility() == MediaVisibility.PUBLIC
				&& StringUtils.hasText(target.storageKey())) {
			try {
				String thumbnailKey = ImageThumbnailService.thumbnailKeyFor(target.storageKey());
				deletionFailure = deleteObject(thumbnailKey, deletionFailure);
			} catch (RuntimeException invalidKey) {
				deletionFailure = combine(deletionFailure, invalidKey);
			}
		}
		if (target.kind() == MediaKind.VIDEO) {
			try {
				storageClient.deleteFolder(mediaPolicy.buildHlsPrefix(target.assetId()));
			} catch (RuntimeException failure) {
				deletionFailure = combine(deletionFailure, failure);
			}
		}
		if (deletionFailure == null && target.visibility() == MediaVisibility.PUBLIC) {
			try {
				// Exactly one asset-scoped wildcard is submitted when CloudFront is
				// configured. Without it, the bounded cache TTL is the fail-safe.
				storageClient.invalidatePublicAsset(target.assetId());
			} catch (RuntimeException failure) {
				deletionFailure = failure;
			}
		}

		if (deletionFailure == null) {
			boolean removed = stateService.finish(target.assetId());
			log.info("[media-delete] cleanup completed assetId={} rowRemoved={}",
					target.assetId(), removed);
			return;
		}

		// Every storage operation is idempotent. Retain the durable row and move it
		// behind untouched work so one unavailable key cannot starve the batch.
		try {
			stateService.defer(target.assetId());
		} catch (RuntimeException stateFailure) {
			deletionFailure.addSuppressed(stateFailure);
		}
		log.warn("[media-delete] deferred assetId={} exceptionType={}",
				target.assetId(), deletionFailure.getClass().getSimpleName());
	}

	private LocalDateTime producerGraceDeadline(MediaDeletionStateService.DeletionTarget target) {
		if (target.physicalDeletionNotBefore() != null) {
			return laterOf(
					target.physicalDeletionNotBefore(),
					target.uploadVerificationCleanupNotBefore());
		}
		Duration grace = null;
		if (target.kind() == MediaKind.VIDEO) {
			// Rolling-upgrade fallback: an old node may create a pending row after
			// the migration without the exact deadline. Use the maximum accepted
			// window, never a possibly shortened current configuration.
			grace = Duration.ofDays(7);
		} else if (target.kind() == MediaKind.IMAGE
				&& target.visibility() == MediaVisibility.PUBLIC) {
			grace = Duration.ofHours(1);
		}
		if (grace == null) return target.uploadVerificationCleanupNotBefore();

		LocalDateTime requestedAt = target.deletionRequestedAt();
		if (requestedAt == null) {
			// Backward-safe fallback for a row created before deletionRequestedAt was
			// introduced. The rollout migration backfills this value from updatedAt.
			requestedAt = target.updatedAt();
		}
		if (requestedAt == null) {
			// Missing durability metadata must never authorize destructive storage I/O.
			return LocalDateTime.MAX;
		}
		return laterOf(requestedAt.plus(grace), target.uploadVerificationCleanupNotBefore());
	}

	private static LocalDateTime laterOf(LocalDateTime first, LocalDateTime second) {
		if (first == null) return second;
		if (second == null) return first;
		return first.isAfter(second) ? first : second;
	}

	private boolean isUploadWriteWindowClosed(MediaDeletionStateService.DeletionTarget target) {
		if (target.uploadWriteAuthorityExpiresAt() == null) {
			return presignedUploadWriteWindow.isSafeToDelete(target.createdAt());
		}
		return presignedUploadWriteWindow.isSafeToDelete(
				target.uploadWriteAuthorityExpiresAt(), target.createdAt());
	}

	private RuntimeException deleteObject(String objectKey, RuntimeException previous) {
		try {
			storageClient.deleteObject(objectKey);
			return previous;
		} catch (RuntimeException failure) {
			return combine(previous, failure);
		}
	}

	private static RuntimeException combine(RuntimeException previous, RuntimeException next) {
		if (previous == null) return next;
		previous.addSuppressed(next);
		return previous;
	}
}

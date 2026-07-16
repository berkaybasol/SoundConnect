package com.berkayb.soundconnect.modules.media.transcode;

import com.berkayb.soundconnect.modules.media.entity.MediaAsset;
import com.berkayb.soundconnect.modules.media.enums.MediaKind;
import com.berkayb.soundconnect.modules.media.enums.MediaStatus;
import com.berkayb.soundconnect.modules.media.enums.MediaStreamingProtocol;
import com.berkayb.soundconnect.modules.media.enums.MediaVisibility;
import com.berkayb.soundconnect.modules.media.repository.MediaAssetRepository;
import com.berkayb.soundconnect.modules.media.storage.StorageObjectKeys;
import com.berkayb.soundconnect.modules.media.transcode.config.MediaTranscodeLeaseProperties;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

/** Transactional state machine and ownership fences for public video HLS work. */
@Service
@Slf4j
public class MediaAssetStatusUpdater {
	private final MediaAssetRepository mediaAssetRepository;
	private final MediaTranscodeLeaseProperties leaseProperties;
	private final long hardAttemptTimeoutHours;

	public MediaAssetStatusUpdater(
			MediaAssetRepository mediaAssetRepository,
			MediaTranscodeLeaseProperties leaseProperties,
			@Value("${media.transcode.processing-timeout-hours:12}") long hardAttemptTimeoutHours
	) {
		this.mediaAssetRepository = mediaAssetRepository;
		this.leaseProperties = leaseProperties;
		this.hardAttemptTimeoutHours = Math.max(1L, hardAttemptTimeoutHours);
	}

	@Transactional
	public boolean markTranscodeSent(UUID assetId) {
		return mediaAssetRepository.markTranscodeSent(
				assetId, MediaKind.VIDEO, MediaVisibility.PUBLIC,
				MediaStatus.TRANSCODE_QUEUED, MediaStatus.TRANSCODE_SENT
		) == 1;
	}

	/** Defers a broker signal when this node has no native worker admission slot. */
	@Transactional
	public boolean requeueUnclaimedTranscodeSignal(UUID assetId) {
		return mediaAssetRepository.requeueUnclaimedTranscodeSignal(
				assetId, MediaKind.VIDEO, MediaVisibility.PUBLIC,
				MediaStatus.TRANSCODE_SENT, MediaStatus.TRANSCODE_QUEUED
		) == 1;
	}

	/**
	 * Claims one durable attempt. The transaction returns only after both the CAS
	 * and authoritative verified source have been observed under the same row lock.
	 */
	@Transactional
	public Optional<TranscodeClaim> tryClaimQueuedTranscode(UUID assetId) {
		UUID attemptToken = UUID.randomUUID();
		LocalDateTime now = utcNow();
		LocalDateTime leaseUntil = now.plus(leaseProperties.getDuration());
		LocalDateTime attemptDeadline = now.plusHours(hardAttemptTimeoutHours);
		int updated = mediaAssetRepository.claimQueuedTranscode(
				assetId,
				MediaKind.VIDEO,
				MediaVisibility.PUBLIC,
				MediaStatus.TRANSCODE_QUEUED,
				MediaStatus.TRANSCODE_SENT,
				MediaStatus.PROCESSING,
				attemptToken,
				leaseUntil,
				attemptDeadline,
				leaseProperties.getMaxAttempts()
		);
		if (updated == 0) {
			log.info("[asset] transcode claim ignored assetId={}", assetId);
			return Optional.empty();
		}

		MediaAsset asset = getOrThrow(assetId);
		validateOwnedPublicVideo(asset, attemptToken, now);
		return Optional.of(new TranscodeClaim(
				assetId,
				attemptToken,
				asset.getStorageKey(),
				asset.getTranscodeAttemptCount(),
				asset.getTranscodeLeaseUntil(),
				asset.getTranscodeAttemptDeadline()
		));
	}

	@Transactional
	public boolean renewTranscodeLease(UUID assetId, UUID attemptToken) {
		LocalDateTime now = utcNow();
		return mediaAssetRepository.renewTranscodeLease(
				assetId,
				MediaKind.VIDEO,
				MediaVisibility.PUBLIC,
				MediaStatus.PROCESSING,
				attemptToken,
				now,
				now.plus(leaseProperties.getDuration())
		) == 1;
	}

	@Transactional(readOnly = true)
	public MediaAsset getClaimedPublicVideo(UUID assetId, UUID attemptToken) {
		MediaAsset asset = getOrThrow(assetId);
		validateOwnedPublicVideo(asset, attemptToken, utcNow());
		return asset;
	}

	@Transactional
	public boolean tryFinalizeReadyHls(
			UUID assetId,
			UUID attemptToken,
			String playbackUrl,
			String thumbnailUrl,
			Integer durationSeconds,
			Integer width,
			Integer height
	) {
		if (!StringUtils.hasText(playbackUrl)) {
			throw new SoundConnectException(ErrorType.INTERNAL_ERROR);
		}
		LocalDateTime now = utcNow();
		int updated = mediaAssetRepository.finalizeHlsIfProcessing(
				assetId,
				MediaKind.VIDEO,
				MediaVisibility.PUBLIC,
				MediaStatus.PROCESSING,
				attemptToken,
				now,
				MediaStatus.READY,
				MediaStreamingProtocol.HLS,
				playbackUrl,
				StringUtils.hasText(thumbnailUrl) ? thumbnailUrl : null,
				positiveOrNull(durationSeconds),
				positiveOrNull(width),
				positiveOrNull(height)
		);
		if (updated == 1) {
			log.info("[asset] READY (HLS) assetId={} playbackUrl={}", assetId, playbackUrl);
			return true;
		}
		log.info("[asset] HLS finalization fenced assetId={} attemptToken={}", assetId, attemptToken);
		return false;
	}

	/** A failed live worker may request terminal cleanup only for its exact lease. */
	@Transactional
	public boolean markHlsCleanupPending(UUID assetId, UUID attemptToken) {
		int updated = mediaAssetRepository.markTranscodeAttemptForCleanup(
				assetId,
				MediaKind.VIDEO,
				MediaVisibility.PUBLIC,
				MediaStatus.PROCESSING,
				MediaStatus.HLS_CLEANUP,
				attemptToken,
				utcNow()
		);
		if (updated == 1) {
			log.warn("[asset] HLS cleanup pending assetId={} attemptToken={}", assetId, attemptToken);
			return true;
		}
		log.info("[asset] stale cleanup transition fenced assetId={} attemptToken={}", assetId, attemptToken);
		return false;
	}

	/**
	 * Retryable infrastructure failures preserve the verified source and use a
	 * bounded exponential backoff. Permanent admission failures skip retry.
	 */
	@Transactional
	public boolean markHlsCleanupPending(
			UUID assetId,
			UUID attemptToken,
			int attemptNumber,
			boolean retryable
	) {
		if (retryable) {
			LocalDateTime now = utcNow();
			int retry = mediaAssetRepository.markTranscodeAttemptForRetryCleanup(
					assetId,
					MediaKind.VIDEO,
					MediaVisibility.PUBLIC,
					MediaStatus.PROCESSING,
					MediaStatus.HLS_CLEANUP,
					attemptToken,
					attemptNumber,
					leaseProperties.getMaxAttempts(),
					now,
					now.plus(retryBackoff(attemptNumber))
			);
			if (retry == 1) {
				log.warn("[asset] retryable HLS cleanup pending assetId={} attempt={}",
						assetId, attemptNumber);
				return true;
			}
			int retained = mediaAssetRepository.markTranscodeAttemptForRetainedCleanup(
					assetId,
					MediaKind.VIDEO,
					MediaVisibility.PUBLIC,
					MediaStatus.PROCESSING,
					MediaStatus.HLS_CLEANUP,
					attemptToken,
					attemptNumber,
					leaseProperties.getMaxAttempts(),
					now
			);
			if (retained == 1) {
				log.warn("[asset] exhausted infrastructure retry; verified source retained for manual recovery assetId={} attempt={}",
						assetId, attemptNumber);
				return true;
			}
			log.info("[asset] retryable cleanup transition fenced assetId={} attempt={}",
					assetId, attemptNumber);
			return false;
		}
		return markHlsCleanupPending(assetId, attemptToken);
	}

	/**
	 * Gives back a claim that never reached a native worker. No process or upload
	 * can still be writing, so derivative cleanup may start immediately instead
	 * of waiting for the normal retry backoff or hard-attempt deadline.
	 */
	@Transactional
	public boolean abandonClaimForRetry(
			UUID assetId,
			UUID attemptToken,
			int attemptNumber
	) {
		LocalDateTime now = utcNow();
		int retry = mediaAssetRepository.markTranscodeAttemptForRetryCleanup(
				assetId,
				MediaKind.VIDEO,
				MediaVisibility.PUBLIC,
				MediaStatus.PROCESSING,
				MediaStatus.HLS_CLEANUP,
				attemptToken,
				attemptNumber,
				leaseProperties.getMaxAttempts(),
				now,
				now
		);
		if (retry == 1) {
			log.warn("[asset] unstarted HLS claim returned for immediate retry assetId={} attempt={}",
					assetId, attemptNumber);
			return true;
		}

		int retained = mediaAssetRepository.markTranscodeAttemptForRetainedCleanup(
				assetId,
				MediaKind.VIDEO,
				MediaVisibility.PUBLIC,
				MediaStatus.PROCESSING,
				MediaStatus.HLS_CLEANUP,
				attemptToken,
				attemptNumber,
				leaseProperties.getMaxAttempts(),
				now
		);
		if (retained == 1) {
			log.warn("[asset] unstarted HLS claim exhausted retry budget; verified source retained assetId={} attempt={}",
					assetId, attemptNumber);
			return true;
		}
		log.info("[asset] unstarted HLS claim abandonment fenced assetId={} attempt={}",
				assetId, attemptNumber);
		return false;
	}

	@Transactional
	public boolean completeHlsCleanup(UUID assetId) {
		return mediaAssetRepository.completeHlsCleanup(
				assetId, MediaKind.VIDEO, MediaVisibility.PUBLIC,
				MediaStatus.HLS_CLEANUP, MediaStatus.FAILED
		) == 1;
	}

	@Transactional
	public HlsRetryCleanupOutcome completeHlsRetryCleanup(UUID assetId) {
		int requeued = mediaAssetRepository.completeHlsRetryCleanup(
				assetId, MediaKind.VIDEO, MediaVisibility.PUBLIC,
				MediaStatus.HLS_CLEANUP, MediaStatus.TRANSCODE_QUEUED,
				leaseProperties.getMaxAttempts()
		);
		if (requeued == 1) return HlsRetryCleanupOutcome.REQUEUED;

		int exhausted = mediaAssetRepository.exhaustHlsRetryCleanup(
				assetId, MediaKind.VIDEO, MediaVisibility.PUBLIC,
				MediaStatus.HLS_CLEANUP, leaseProperties.getMaxAttempts()
		);
		return exhausted == 1
				? HlsRetryCleanupOutcome.EXHAUSTED
				: HlsRetryCleanupOutcome.SUPERSEDED;
	}

	@Transactional
	public boolean completeRetainedSourceFailure(UUID assetId) {
		return mediaAssetRepository.completeRetainedSourceFailure(
				assetId,
				MediaKind.VIDEO,
				MediaVisibility.PUBLIC,
				MediaStatus.HLS_CLEANUP,
				MediaStatus.FAILED
		) == 1;
	}

	@Transactional(readOnly = true)
	public Optional<HlsCleanupTarget> getHlsCleanupTarget(UUID assetId) {
		return mediaAssetRepository.findById(assetId)
				.filter(asset -> asset.getStatus() == MediaStatus.HLS_CLEANUP)
				.filter(asset -> asset.getKind() == MediaKind.VIDEO)
				.filter(asset -> asset.getVisibility() == MediaVisibility.PUBLIC)
				.map(asset -> {
					String sourceKey = asset.getStorageKey();
					if (StringUtils.hasText(sourceKey)
							&& (!StorageObjectKeys.isVerified(sourceKey)
							|| StorageObjectKeys.isProtected(sourceKey))) {
						throw new SoundConnectException(ErrorType.MEDIA_ASSET_STATE_INVALID);
					}
					return new HlsCleanupTarget(
							sourceKey,
							asset.getCreatedAt(),
							asset.getUploadWriteAuthorityExpiresAt(),
							asset.isTranscodeRetryPending(),
							asset.isTranscodeRetainSourceAfterCleanup(),
							asset.getTranscodeCleanupNotBefore()
					);
				});
	}

	@Transactional
	public boolean deferHlsCleanup(UUID assetId) {
		return mediaAssetRepository.deferHlsCleanup(
				assetId, MediaKind.VIDEO, MediaVisibility.PUBLIC, MediaStatus.HLS_CLEANUP
		) == 1;
	}

	private MediaAsset getOrThrow(UUID assetId) {
		return mediaAssetRepository.findById(assetId)
				.orElseThrow(() -> new SoundConnectException(ErrorType.MEDIA_ASSET_NOT_FOUND));
	}

	private static void validateOwnedPublicVideo(
			MediaAsset asset,
			UUID attemptToken,
			LocalDateTime now
	) {
		if (asset.getStatus() != MediaStatus.PROCESSING
				|| asset.getKind() != MediaKind.VIDEO
				|| asset.getVisibility() != MediaVisibility.PUBLIC
				|| !attemptToken.equals(asset.getTranscodeAttemptToken())
				|| asset.getTranscodeLeaseUntil() == null
				|| !asset.getTranscodeLeaseUntil().isAfter(now)
				|| asset.getTranscodeAttemptDeadline() == null
				|| !asset.getTranscodeAttemptDeadline().isAfter(now)
				|| !StorageObjectKeys.isVerified(asset.getStorageKey())
				|| StorageObjectKeys.isProtected(asset.getStorageKey())) {
			throw new SoundConnectException(ErrorType.MEDIA_ASSET_STATE_INVALID);
		}
	}

	private static LocalDateTime utcNow() {
		return LocalDateTime.now(ZoneOffset.UTC);
	}

	private java.time.Duration retryBackoff(int attemptNumber) {
		int exponent = Math.max(0, Math.min(attemptNumber - 1, 30));
		java.time.Duration candidate;
		try {
			candidate = leaseProperties.getRetryBackoffBase().multipliedBy(1L << exponent);
		} catch (ArithmeticException overflow) {
			candidate = leaseProperties.getRetryBackoffMax();
		}
		return candidate.compareTo(leaseProperties.getRetryBackoffMax()) > 0
				? leaseProperties.getRetryBackoffMax()
				: candidate;
	}

	private static Integer positiveOrNull(Integer value) {
		return value != null && value > 0 ? value : null;
	}

	public record TranscodeClaim(
			UUID assetId,
			UUID attemptToken,
			String sourceKey,
			int attemptNumber,
			LocalDateTime leaseUntil,
			LocalDateTime attemptDeadline
	) {}

	public record HlsCleanupTarget(
			String sourceKey,
			LocalDateTime createdAt,
			LocalDateTime uploadWriteAuthorityExpiresAt,
			boolean retryAfterCleanup,
			boolean retainSourceAfterCleanup,
			LocalDateTime cleanupNotBefore
	) {
		public HlsCleanupTarget(String sourceKey) {
			this(sourceKey, null, null, false, false, null);
		}

		public HlsCleanupTarget(String sourceKey, boolean retryAfterCleanup) {
			this(sourceKey, null, null, retryAfterCleanup, false, null);
		}
	}

	public enum HlsRetryCleanupOutcome {
		REQUEUED,
		EXHAUSTED,
		SUPERSEDED
	}
}

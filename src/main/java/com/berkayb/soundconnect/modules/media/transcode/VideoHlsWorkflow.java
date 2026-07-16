package com.berkayb.soundconnect.modules.media.transcode;

import com.berkayb.soundconnect.modules.media.dto.request.VideoHlsRequest;
import com.berkayb.soundconnect.modules.media.dto.response.HlsUploadResult;
import com.berkayb.soundconnect.modules.media.storage.MediaPolicy;
import com.berkayb.soundconnect.modules.media.storage.StorageClient;
import com.berkayb.soundconnect.modules.media.transcode.config.TranscodeProperties;
import com.berkayb.soundconnect.modules.media.transcode.ffmpeg.FfmpegService;
import com.berkayb.soundconnect.modules.media.transcode.ffmpeg.FfprobeService;
import com.berkayb.soundconnect.modules.media.transcode.ffmpeg.VideoProbeMetadata;
import com.berkayb.soundconnect.modules.media.transcode.upload.HlsUploader;
import com.berkayb.soundconnect.modules.media.transcode.validation.VideoTranscodeRejectedException;
import com.berkayb.soundconnect.modules.media.transcode.validation.TranscodeTempBudgetManager;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/** Lease-fenced HLS orchestration. The database row, not Rabbit, owns the work. */
@Service
@RequiredArgsConstructor
@Slf4j
public class VideoHlsWorkflow {
	private final StorageClient storage;
	private final FfmpegService ffmpeg;
	private final HlsUploader uploader;
	private final MediaAssetStatusUpdater statusUpdater;
	private final FfprobeService ffprobe;
	private final MediaPolicy mediaPolicy;
	private final MediaTranscodeLeaseHeartbeat leaseHeartbeat;
	private final TranscodeTempBudgetManager tempBudgetManager;
	private final TranscodeProperties transcodeProperties;

	/**
	 * Commits durable ownership before the Rabbit delivery is acknowledged. An
	 * empty result is a live duplicate, terminal row, or exhausted attempt budget.
	 */
	public Optional<ClaimedVideoHlsWork> claim(VideoHlsRequest req) {
		validateRequest(req);
		UUID assetId = UUID.fromString(req.assetId());
		return statusUpdater.tryClaimQueuedTranscode(assetId)
				.map(claim -> new ClaimedVideoHlsWork(
						claim.assetId(),
						claim.attemptToken(),
						claim.sourceKey(),
						mediaPolicy.buildHlsPrefix(assetId),
						claim.attemptNumber()
				));
	}

	/** Leaves an unclaimed SENT signal in the durable QUEUED dispatcher state. */
	public boolean deferSignal(VideoHlsRequest req) {
		validateRequest(req);
		return statusUpdater.requeueUnclaimedTranscodeSignal(UUID.fromString(req.assetId()));
	}

	/** Returns a durable claim that could not be handed to a native worker. */
	public boolean abandonClaimForRetry(ClaimedVideoHlsWork work) {
		if (work == null) return false;
		return statusUpdater.abandonClaimForRetry(
				work.assetId(), work.attemptToken(), work.attemptNumber());
	}

	/** Compatibility entry point used outside the manual-ACK listener. */
	public void process(VideoHlsRequest req) throws Exception {
		Optional<ClaimedVideoHlsWork> claimed = claim(req);
		if (claimed.isEmpty()) {
			log.info("[workflow] duplicate, terminal, or exhausted delivery ignored assetId={}",
					req.assetId());
			return;
		}
		processClaimed(claimed.orElseThrow());
	}

	/** Executes work whose durable claim has already committed. */
	public void processClaimed(ClaimedVideoHlsWork work) throws Exception {
		UUID assetId = work.assetId();
		Path workdir = null;
		TranscodeTempBudgetManager.Reservation tempReservation = null;
		try (TranscodeLease lease = leaseHeartbeat.start(assetId, work.attemptToken())) {
			lease.checkpoint();
			tempReservation = tempBudgetManager.reserveMaxWorkBudget();
			lease.checkpoint();
			workdir = Files.createTempDirectory("sc-hls-" + assetId + "-");
			Path srcFile = workdir.resolve("source" + extFromKey(work.sourceKey()));
			Path outDir = workdir.resolve("out");
			Path thumb = workdir.resolve("thumbnail.jpg");

			log.info("[workflow] start assetId={} attempt={} out={}",
					assetId, work.attemptNumber(), outDir);
			storage.downloadToFile(
					work.sourceKey(),
					srcFile,
					Duration.ofSeconds(transcodeProperties.getSourceDownloadTimeoutSec())
			);
			lease.checkpoint();

			// Strict admission: probe, metadata, output estimate and actual temp
			// capacity must all pass before the first FFmpeg child process starts.
			VideoProbeMetadata metadata = ffprobe.probeVideo(srcFile);
			Integer duration = metadata.roundedDurationSeconds();
			Integer width = metadata.width();
			Integer height = metadata.height();
			log.debug("[workflow] admitted video assetId={} duration={}s {}x{} fps={}",
					assetId, duration, width, height, metadata.frameRate());
			lease.checkpoint();

			ffmpeg.generateHlsLadder(srcFile, outDir);
			lease.checkpoint();
			ffmpeg.generateThumbnail(srcFile, thumb);
			lease.checkpoint();

			HlsUploadResult upload = uploader.uploadHlsTree(outDir, work.hlsPrefix(), thumb);
			lease.checkpoint();

			boolean finalized = statusUpdater.tryFinalizeReadyHls(
					assetId,
					work.attemptToken(),
					upload.playbackUrl(),
					upload.thumbnailUrl(),
					duration,
					width,
					height
			);
			if (!finalized) {
				log.info("[workflow] late attempt fenced after upload assetId={} attempt={} prefix={}",
						assetId, work.attemptNumber(), work.hlsPrefix());
				return;
			}
			log.info("[workflow] OK assetId={} attempt={} objects={} playback={}",
					assetId, work.attemptNumber(), upload.objectCount(), upload.playbackUrl());
		} catch (TranscodeLeaseLostException lost) {
			// The expired-attempt recovery state owns deterministic prefix cleanup and
			// retry. Returning is intentional: the Rabbit signal was already ACKed.
			log.warn("[workflow] lease lost; durable recovery owns assetId={} attempt={}",
					assetId, work.attemptNumber());
		} catch (Exception failure) {
			try {
				statusUpdater.markHlsCleanupPending(
						assetId,
						work.attemptToken(),
						work.attemptNumber(),
						!hasCause(failure, VideoTranscodeRejectedException.class)
				);
			} catch (Exception cleanupStateFailure) {
				failure.addSuppressed(cleanupStateFailure);
				log.error("[workflow] HLS cleanup intent error assetId={} err={}",
						assetId, cleanupStateFailure.getMessage(), cleanupStateFailure);
			}
			log.error("[workflow] FAILED assetId={} attempt={} err={}",
					assetId, work.attemptNumber(), failure.getMessage(), failure);
			throw failure;
		} finally {
			if (workdir != null) {
				try {
					deleteRecursive(workdir);
				} catch (Exception cleanupFailure) {
					log.warn("[workflow] temp cleanup failed dir={} err={}",
							workdir, cleanupFailure.getMessage());
				}
			}
			if (tempReservation != null) {
				tempReservation.close();
			}
		}
	}

	private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
		Throwable current = error;
		while (current != null) {
			if (type.isInstance(current)) return true;
			if (current.getCause() == current) break;
			current = current.getCause();
		}
		return false;
	}

	private static void validateRequest(VideoHlsRequest req) {
		if (req == null) throw new SoundConnectException(ErrorType.INVALID_HLS_REQUEST);
		if (req.assetId() == null || req.assetId().isBlank()) {
			throw new SoundConnectException(ErrorType.ASSET_ID_REQUIRED);
		}
		if (req.sourceKey() == null || req.sourceKey().isBlank()) {
			throw new SoundConnectException(ErrorType.SOURCE_KEY_REQUIRED);
		}
		if (req.hlsPrefix() == null || req.hlsPrefix().isBlank()) {
			throw new SoundConnectException(ErrorType.HLS_PREFIX_REQUIRED);
		}
		try {
			UUID.fromString(req.assetId());
		} catch (IllegalArgumentException invalidId) {
			throw new SoundConnectException(ErrorType.INVALID_HLS_REQUEST);
		}
	}

	private static String extFromKey(String key) {
		int dot = key.lastIndexOf('.');
		if (dot < 0 || dot == key.length() - 1) return ".mp4";
		String raw = key.substring(dot).toLowerCase();
		return raw.length() > 8 ? ".mp4" : raw;
	}

	private static void deleteRecursive(Path root) throws IOException {
		if (!Files.exists(root)) return;
		Files.walkFileTree(root, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				Files.deleteIfExists(file);
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
				Files.deleteIfExists(dir);
				return FileVisitResult.CONTINUE;
			}
		});
	}

	public record ClaimedVideoHlsWork(
			UUID assetId,
			UUID attemptToken,
			String sourceKey,
			String hlsPrefix,
			int attemptNumber
	) {}
}

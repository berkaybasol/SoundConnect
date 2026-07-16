package com.berkayb.soundconnect.modules.media.transcode.validation;

import com.berkayb.soundconnect.modules.media.transcode.config.TranscodeProperties;
import com.berkayb.soundconnect.modules.media.transcode.config.TranscodeVariant;
import com.berkayb.soundconnect.modules.media.transcode.ffmpeg.VideoProbeMetadata;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Fail-closed resource admission for untrusted video. This executes after the
 * source download and before the first FFmpeg child process.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class VideoTranscodeResourceValidator {

	private static final BigDecimal BITS_PER_BYTE = BigDecimal.valueOf(8);
	private static final BigDecimal KILO = BigDecimal.valueOf(1_000);
	private static final BigDecimal MEGA = BigDecimal.valueOf(1_000_000);

	private final TranscodeProperties properties;
	private final TranscodeDiskSpaceInspector diskSpaceInspector;

	/** Returns the conservative HLS output estimate when the input is admitted. */
	public long validate(Path source, VideoProbeMetadata metadata) throws IOException {
		if (source == null || source.getParent() == null || !Files.isRegularFile(source)) {
			throw rejected("downloaded source file is missing");
		}
		validateMetadata(metadata);

		long inputBytes = Files.size(source);
		if (inputBytes <= 0) throw rejected("downloaded source file is empty");

		long estimatedOutputBytes = estimateOutputBytes(metadata.durationSeconds());
		if (estimatedOutputBytes > properties.getMaxEstimatedOutputBytes()) {
			throw rejected("estimated HLS output exceeds configured budget");
		}

		long estimatedWorkingSet = addExact(inputBytes, estimatedOutputBytes,
				"temporary working-set estimate overflowed");
		if (estimatedWorkingSet > properties.getMaxTempWorkBytes()) {
			throw rejected("temporary working-set estimate exceeds configured budget");
		}

		long requiredFreeBytes = addExact(
				estimatedOutputBytes,
				properties.getMinFreeTempBytes(),
				"temporary free-space requirement overflowed"
		);
		long usableBytes = diskSpaceInspector.usableBytes(source.getParent());
		if (usableBytes < requiredFreeBytes) {
			// Capacity is an infrastructure condition, not a permanent media
			// rejection. Durable retry must preserve the verified source.
			throw new TranscodeCapacityUnavailableException(
					"insufficient temporary disk space for estimated HLS output");
		}

		log.info("[transcode-preflight] admitted duration={}s resolution={}x{} fps={} inputBytes={} estimatedOutputBytes={} usableTempBytes={}",
				metadata.durationSeconds(), metadata.width(), metadata.height(), metadata.frameRate(),
				inputBytes, estimatedOutputBytes, usableBytes);
		return estimatedOutputBytes;
	}

	private void validateMetadata(VideoProbeMetadata metadata)
			throws VideoTranscodeRejectedException {
		if (metadata == null) throw rejected("ffprobe returned no video metadata");
		if (metadata.durationSeconds() == null
				|| !Double.isFinite(metadata.durationSeconds())
				|| metadata.durationSeconds() <= 0) {
			throw rejected("ffprobe returned an invalid duration");
		}
		if (metadata.durationSeconds() > properties.getMaxDurationSeconds()) {
			throw rejected("video duration exceeds configured limit");
		}
		if (metadata.width() == null || metadata.width() <= 0
				|| metadata.height() == null || metadata.height() <= 0) {
			throw rejected("ffprobe returned an invalid resolution");
		}
		if (metadata.maxDimension() == null
				|| metadata.maxDimension() > properties.getMaxVideoDimension()) {
			throw rejected("video dimension exceeds configured 4K limit");
		}
		if (metadata.maxPixelCount() == null
				|| metadata.maxPixelCount() > properties.getMaxVideoPixels()) {
			throw rejected("video pixel count exceeds configured 4K limit");
		}
		if (metadata.frameRate() == null
				|| !Double.isFinite(metadata.frameRate())
				|| metadata.frameRate() <= 0) {
			throw rejected("ffprobe returned an invalid frame rate");
		}
		if (metadata.frameRate() > properties.getMaxFrameRate()) {
			throw rejected("video frame rate exceeds configured limit");
		}
	}

	private long estimateOutputBytes(double durationSeconds)
			throws VideoTranscodeRejectedException {
		try {
			BigDecimal totalBitsPerSecond = BigDecimal.ZERO;
			for (TranscodeVariant variant : properties.getLadder()) {
				totalBitsPerSecond = totalBitsPerSecond
						.add(parseBitrate(variant.getVideoBitrate()))
						.add(parseBitrate(variant.getAudioBitrate()));
			}
			if (totalBitsPerSecond.signum() <= 0) {
				throw rejected("transcode ladder has no usable bitrate budget");
			}

			return totalBitsPerSecond
					.multiply(BigDecimal.valueOf(durationSeconds))
					.divide(BITS_PER_BYTE, 0, RoundingMode.CEILING)
					.multiply(BigDecimal.valueOf(properties.getOutputEstimateMultiplier()))
					.add(BigDecimal.valueOf(properties.getOutputFixedOverheadBytes()))
					.setScale(0, RoundingMode.CEILING)
					.longValueExact();
		} catch (ArithmeticException invalidEstimate) {
			throw rejected("HLS output estimate overflowed", invalidEstimate);
		}
	}

	static BigDecimal parseBitrate(String raw) throws VideoTranscodeRejectedException {
		if (raw == null || raw.isBlank()) throw rejected("transcode ladder bitrate is missing");
		String normalized = raw.trim().toLowerCase(Locale.ROOT);
		BigDecimal multiplier = BigDecimal.ONE;
		if (normalized.endsWith("k")) {
			multiplier = KILO;
			normalized = normalized.substring(0, normalized.length() - 1);
		} else if (normalized.endsWith("m")) {
			multiplier = MEGA;
			normalized = normalized.substring(0, normalized.length() - 1);
		}
		try {
			BigDecimal bitrate = new BigDecimal(normalized).multiply(multiplier);
			if (bitrate.signum() <= 0) throw rejected("transcode ladder bitrate must be positive");
			return bitrate;
		} catch (NumberFormatException invalidBitrate) {
			throw rejected("transcode ladder bitrate is invalid", invalidBitrate);
		}
	}

	private static long addExact(long left, long right, String message)
			throws VideoTranscodeRejectedException {
		try {
			return Math.addExact(left, right);
		} catch (ArithmeticException overflow) {
			throw rejected(message, overflow);
		}
	}

	private static VideoTranscodeRejectedException rejected(String message) {
		return new VideoTranscodeRejectedException(message);
	}

	private static VideoTranscodeRejectedException rejected(String message, Throwable cause) {
		return new VideoTranscodeRejectedException(message, cause);
	}
}

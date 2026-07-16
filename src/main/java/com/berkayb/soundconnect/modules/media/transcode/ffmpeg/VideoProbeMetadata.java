package com.berkayb.soundconnect.modules.media.transcode.ffmpeg;

/** Untrusted video metadata extracted by one bounded ffprobe invocation. */
public record VideoProbeMetadata(
		Double durationSeconds,
		Integer width,
		Integer height,
		Double frameRate,
		Long maxPixelCount,
		Integer maxDimension
) {
	public VideoProbeMetadata(
			Double durationSeconds,
			Integer width,
			Integer height,
			Double frameRate
	) {
		this(
				durationSeconds,
				width,
				height,
				frameRate,
				width == null || height == null ? null : (long) width * height,
				width == null || height == null ? null : Math.max(width, height)
		);
	}

	public Integer roundedDurationSeconds() {
		if (durationSeconds == null
				|| !Double.isFinite(durationSeconds)
				|| durationSeconds <= 0
				|| durationSeconds > Integer.MAX_VALUE) {
			return null;
		}
		return (int) Math.ceil(durationSeconds);
	}
}

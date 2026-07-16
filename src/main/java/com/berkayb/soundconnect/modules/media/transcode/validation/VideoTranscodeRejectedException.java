package com.berkayb.soundconnect.modules.media.transcode.validation;

import java.io.IOException;

/** Permanent preflight rejection; the input must never reach FFmpeg. */
public class VideoTranscodeRejectedException extends IOException {
	public VideoTranscodeRejectedException(String message) {
		super(message);
	}

	public VideoTranscodeRejectedException(String message, Throwable cause) {
		super(message, cause);
	}
}

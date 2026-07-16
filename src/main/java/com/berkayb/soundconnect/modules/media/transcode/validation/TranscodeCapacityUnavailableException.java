package com.berkayb.soundconnect.modules.media.transcode.validation;

import java.io.IOException;

/**
 * Retryable infrastructure-capacity failure. Unlike invalid media metadata,
 * this does not make the uploaded source permanently unusable.
 */
public class TranscodeCapacityUnavailableException extends IOException {
	public TranscodeCapacityUnavailableException(String message) {
		super(message);
	}

	public TranscodeCapacityUnavailableException(String message, Throwable cause) {
		super(message, cause);
	}
}

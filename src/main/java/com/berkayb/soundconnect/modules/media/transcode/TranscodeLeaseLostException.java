package com.berkayb.soundconnect.modules.media.transcode;

/** The workflow no longer owns its durable attempt and must stop publishing. */
public class TranscodeLeaseLostException extends RuntimeException {
	public TranscodeLeaseLostException() {
		super("Transcode attempt lease is no longer owned");
	}

	public TranscodeLeaseLostException(Throwable cause) {
		super("Transcode attempt lease could not be renewed", cause);
	}
}

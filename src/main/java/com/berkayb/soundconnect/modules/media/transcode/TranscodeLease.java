package com.berkayb.soundconnect.modules.media.transcode;

/** A live, heartbeat-backed lease handle for one HLS attempt. */
public interface TranscodeLease extends AutoCloseable {
	/** Synchronously renews ownership and fails closed before the next phase. */
	void checkpoint();

	@Override
	void close();
}

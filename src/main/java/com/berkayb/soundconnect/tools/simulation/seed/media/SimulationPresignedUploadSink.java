package com.berkayb.soundconnect.tools.simulation.seed.media;

/**
 * Simulation-only counterpart of a client performing one presigned PUT.
 *
 * <p>The URL is an opaque, single-use capability issued by the active
 * {@link com.berkayb.soundconnect.modules.media.storage.StorageClient}. The
 * caller never receives an object key and cannot use this port to write an
 * arbitrary path.</p>
 */
public interface SimulationPresignedUploadSink {

	void upload(String uploadUrl, String contentType, byte[] bytes);
}

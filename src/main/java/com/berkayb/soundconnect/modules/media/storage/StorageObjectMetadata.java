package com.berkayb.soundconnect.modules.media.storage;

/**
 * Authoritative metadata read from object storage after a direct client upload.
 */
public record StorageObjectMetadata(long sizeBytes, String contentType, String eTag) {
	public StorageObjectMetadata {
		if (sizeBytes < 0) {
			throw new IllegalArgumentException("Object size cannot be negative");
		}
	}
}

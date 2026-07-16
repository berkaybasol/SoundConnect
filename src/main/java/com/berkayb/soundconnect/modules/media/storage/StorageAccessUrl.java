package com.berkayb.soundconnect.modules.media.storage;

import java.time.Instant;

/**
 * A short-lived, origin-signed object URL. It must never be persisted.
 */
public record StorageAccessUrl(String url, Instant expiresAt) {
}

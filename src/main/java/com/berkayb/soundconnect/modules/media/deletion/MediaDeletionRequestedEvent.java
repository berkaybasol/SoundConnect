package com.berkayb.soundconnect.modules.media.deletion;

import java.util.UUID;

/** Transaction-local signal for a committed durable deletion intent. */
public record MediaDeletionRequestedEvent(UUID assetId) {
}

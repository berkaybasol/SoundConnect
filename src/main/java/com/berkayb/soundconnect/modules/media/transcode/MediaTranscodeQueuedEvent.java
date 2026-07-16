package com.berkayb.soundconnect.modules.media.transcode;

import java.util.UUID;

/**
 * Transaction-local signal that a durable media row is ready for dispatch.
 * The asset id is deliberately the only payload so the listener always reads
 * the committed source of truth rather than stale in-memory state.
 */
public record MediaTranscodeQueuedEvent(UUID assetId) {
}

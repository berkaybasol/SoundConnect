package com.berkayb.soundconnect.modules.media.image;

import java.util.UUID;

/**
 * Transaction-local signal that a committed READY public image needs its
 * deterministic display variant. The row itself remains the durable retry
 * intent while {@code thumbnailUrl} is absent.
 */
public record ImageThumbnailRequestedEvent(UUID assetId) {
}

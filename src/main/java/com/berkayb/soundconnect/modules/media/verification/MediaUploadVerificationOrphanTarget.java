package com.berkayb.soundconnect.modules.media.verification;

import java.time.LocalDateTime;
import java.util.UUID;

public record MediaUploadVerificationOrphanTarget(
		UUID assetId,
		String currentStorageKey,
		LocalDateTime cleanupNotBefore
) {
}

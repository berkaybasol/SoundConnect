package com.berkayb.soundconnect.modules.media.dto.response;

import java.time.Instant;
import java.util.UUID;

/**
 * Owner-authorized, short-lived access to a protected progressive media object.
 */
public record MediaAccessUrlResponseDto(
		UUID assetId,
		String accessUrl,
		Instant expiresAt
) {
}

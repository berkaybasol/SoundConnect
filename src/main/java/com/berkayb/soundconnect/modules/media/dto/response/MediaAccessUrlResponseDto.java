package com.berkayb.soundconnect.modules.media.dto.response;

import java.time.Instant;
import java.util.UUID;
import com.berkayb.soundconnect.modules.media.enums.MediaStreamingProtocol;

/**
 * Owner-authorized, short-lived access to a protected progressive media object.
 */
public record MediaAccessUrlResponseDto(
		UUID assetId,
		String accessUrl,
		Instant expiresAt,
		String thumbnailAccessUrl,
		Instant thumbnailExpiresAt,
		MediaStreamingProtocol streamingProtocol
) {
	public MediaAccessUrlResponseDto(UUID assetId, String accessUrl, Instant expiresAt) {
		this(assetId, accessUrl, expiresAt, null, null, null);
	}
}

package com.berkayb.soundconnect.modules.media.recovery;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Minimal durable projection used to recover private companions of a committed
 * public progressive asset. The public object itself is never a cleanup target.
 */
public record MediaPublicPromotionRecoveryTarget(
		UUID assetId,
		String publicKey,
		LocalDateTime createdAt,
		LocalDateTime uploadWriteAuthorityExpiresAt,
		UUID uploadVerificationAttemptToken
) {
	public MediaPublicPromotionRecoveryTarget(
			UUID assetId,
			String publicKey,
			LocalDateTime createdAt,
			LocalDateTime uploadWriteAuthorityExpiresAt
	) {
		this(assetId, publicKey, createdAt, uploadWriteAuthorityExpiresAt, null);
	}

	public MediaPublicPromotionRecoveryTarget(
			UUID assetId,
			String publicKey,
			LocalDateTime createdAt
	) {
		this(assetId, publicKey, createdAt, null, null);
	}
}

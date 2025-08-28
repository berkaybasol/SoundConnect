package com.berkayb.soundconnect.modules.media.dto.request;

public record VideoHlsRequest(
		String assetId,
		String sourceKey,
		String hlsPrefix,
		String requestedAtIso,
		int schemaVersion
) {
}
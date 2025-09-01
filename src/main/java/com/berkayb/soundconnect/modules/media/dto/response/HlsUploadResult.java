package com.berkayb.soundconnect.modules.media.dto.response;

public record HlsUploadResult(
		String playbackUrl,
		String thumbnailUrl,
		int objectCount
) {
}
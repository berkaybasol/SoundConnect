package com.berkayb.soundconnect.modules.track.dto.response;

import java.util.UUID;

public record TrackResponseDto(
		UUID id,
		UUID mediaAssetId,
		String title,
		String playbackUrl,
		Integer durationSeconds,
		Integer bpm
) {
}
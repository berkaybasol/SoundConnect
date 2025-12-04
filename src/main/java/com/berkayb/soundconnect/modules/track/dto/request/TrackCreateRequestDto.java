package com.berkayb.soundconnect.modules.track.dto.request;

import java.util.UUID;

public record TrackCreateRequestDto(
		UUID mediaAssetId,
		String title,
		Integer durationSeconds,
		Integer bpm
) {
}
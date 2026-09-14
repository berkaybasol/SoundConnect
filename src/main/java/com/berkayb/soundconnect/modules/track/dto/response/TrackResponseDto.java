package com.berkayb.soundconnect.modules.track.dto.response;

import java.util.UUID;
import com.berkayb.soundconnect.modules.media.enums.MediaContentAudience;

public record TrackResponseDto(
		UUID id,
		UUID mediaAssetId,
		String title,
		String playbackUrl,
		Integer durationSeconds,
		Integer bpm,
		MediaContentAudience contentAudience
) {
	public TrackResponseDto(UUID id, UUID mediaAssetId, String title, String playbackUrl,
			Integer durationSeconds, Integer bpm) {
		this(id, mediaAssetId, title, playbackUrl, durationSeconds, bpm, MediaContentAudience.MAINSTAGE);
	}
}

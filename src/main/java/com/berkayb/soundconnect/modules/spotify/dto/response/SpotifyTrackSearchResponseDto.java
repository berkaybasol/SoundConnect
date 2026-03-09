package com.berkayb.soundconnect.modules.spotify.dto.response;

import java.util.List;

public record SpotifyTrackSearchResponseDto(
		String query,
		Integer limit,
		List<SpotifyTrackItemDto> tracks
) {
}
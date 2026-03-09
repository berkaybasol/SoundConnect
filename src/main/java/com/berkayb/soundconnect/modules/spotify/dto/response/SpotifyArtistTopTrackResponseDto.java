package com.berkayb.soundconnect.modules.spotify.dto.response;

import java.util.List;

public record SpotifyArtistTopTrackResponseDto(
		String artistId,
		String market,
		List<SpotifyTrackItemDto> tracks
) {
}
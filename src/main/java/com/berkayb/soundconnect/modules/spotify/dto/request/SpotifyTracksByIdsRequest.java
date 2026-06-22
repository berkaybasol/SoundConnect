package com.berkayb.soundconnect.modules.spotify.dto.request;

import java.util.List;

public record SpotifyTracksByIdsRequest(
		List<String> ids
) {
}
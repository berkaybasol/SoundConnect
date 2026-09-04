package com.berkayb.soundconnect.modules.profile.ListenerProfile.dto.response;

import java.util.UUID;

public record ListenerPlaylistResponseDto(
		UUID id,
		String spotifyPlaylistId,
		String title,
		String coverImageUrl,
		String spotifyUrl,
		int position
) {
}

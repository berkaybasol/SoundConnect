package com.berkayb.soundconnect.modules.profile.ListenerProfile.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;

public record ListenerPlaylistsUpdateRequestDto(
		@NotNull
		@Size(max = MAX_PLAYLISTS)
		List<@NotBlank @Size(max = 2048) String> spotifyUrls,

		@NotNull
		@PositiveOrZero
		Long expectedVersion
) {
	public static final int MAX_PLAYLISTS = 4;
}

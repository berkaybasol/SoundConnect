package com.berkayb.soundconnect.modules.spotify.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record SpotifyTracksByIdsRequest(
		@NotEmpty @Size(max = 50) List<@NotBlank @Size(max = 64) String> ids
) {
}

package com.berkayb.soundconnect.modules.profile.MusicianProfile.band.dto.request;

import java.util.List;
import java.util.UUID;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record BandCreateRequestDto(
		@Size(max = 100) String name,
		@Size(max = 1024) String description,
		UUID profilePicture,
		@Size(max = 255) String instagramUrl,
		@Size(max = 255) String youtubeUrl,
		@Size(max = 255) String soundCloudUrl,
		@Size(max = 255) String spotifyEmbedUrl,
		@Size(max = 255) String spotifyArtistId,
		@Size(max = 50) List<@NotBlank @Size(max = 64) String> spotifyTrackIds
) {
}

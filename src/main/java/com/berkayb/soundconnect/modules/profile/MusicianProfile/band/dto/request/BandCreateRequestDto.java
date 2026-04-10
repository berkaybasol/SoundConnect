package com.berkayb.soundconnect.modules.profile.MusicianProfile.band.dto.request;

import java.util.List;
import java.util.UUID;

public record BandCreateRequestDto(
		String name,
		String description,
		UUID profilePicture,
		String instagramUrl,
		String youtubeUrl,
		String soundCloudUrl,
		String spotifyEmbedUrl,
		String spotifyArtistId,
		List<String> spotifyTrackIds
) {
}
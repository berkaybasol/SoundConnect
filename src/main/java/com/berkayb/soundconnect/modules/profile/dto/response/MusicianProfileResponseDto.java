package com.berkayb.soundconnect.modules.profile.dto.response;

import java.util.Set;
import java.util.UUID;

public record MusicianProfileResponseDto(
		UUID id,
		String stageName,
		String bio,
		String profilePicture,
		String instagramUrl,
		String youtubeUrl,
		String soundcloudUrl,
		String spotifyEmbedUrl,
		Set<String> instruments,
		Set<String> activeVenues
) {}
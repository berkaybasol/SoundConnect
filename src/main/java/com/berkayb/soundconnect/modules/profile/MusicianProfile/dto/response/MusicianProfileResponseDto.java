package com.berkayb.soundconnect.modules.profile.MusicianProfile.dto.response;

import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.dto.response.BandResponseDto;

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
		Set<String> activeVenues,
		Set<BandResponseDto> bands
) {}
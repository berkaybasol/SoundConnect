package com.berkayb.soundconnect.modules.profile.MusicianProfile.dto.response;

import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.dto.response.BandResponseDto;
import com.berkayb.soundconnect.modules.spotify.dto.response.SpotifyTrackItemDto;

import java.util.List;
import java.util.Set;
import java.util.UUID;

public record MusicianProfileResponseDto(
		UUID id,
		UUID userId,
		String stageName,
		String bio,
		UUID profilePictureMediaId,
		String profilePictureUrl,
		String instagramUrl,
		String youtubeUrl,
		String soundcloudUrl,
		String spotifyEmbedUrl,
		String spotifyArtistId,
		Set<String> instruments,
		Set<String> activeVenues,
		Set<BandResponseDto> bands,
		List<String> spotifyTrackIds,
		List<SpotifyTrackItemDto> spotifyTracks
) {}
package com.berkayb.soundconnect.modules.profile.MusicianProfile.dto.request;

import com.berkayb.soundconnect.modules.spotify.dto.response.SpotifyTrackItemDto;

import java.util.List;
import java.util.Set;
import java.util.UUID;

// burada user id gondermiyoruz cunku zaten oturum acmis kullanicinin profili olacak.

public record MusicianProfileSaveRequestDto(
		String stageName,
		String description,
		UUID profilePicture,
		String instagramUrl,
		String youtubeUrl,
		String soundcloudUrl,
		String spotifyEmbedUrl,
		String spotifyArtistId,
		Set<UUID> instrumentIds,
		List<String> spotifyTrackIds,
		List<SpotifyTrackItemDto> spotifyTracks
		
) {
}
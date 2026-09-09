package com.berkayb.soundconnect.modules.profile.MusicianProfile.dto.request;

import com.berkayb.soundconnect.modules.spotify.dto.response.SpotifyTrackItemDto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Set;
import java.util.UUID;

// burada user id gondermiyoruz cunku zaten oturum acmis kullanicinin profili olacak.

public record MusicianProfileSaveRequestDto(
		@Size(max = 255) String stageName,
		@Size(max = 1024) String description,
		UUID profilePicture,
		@Size(max = 255) String instagramUrl,
		@Size(max = 255) String youtubeUrl,
		@Size(max = 255) String soundcloudUrl,
		@Size(max = 255) String spotifyEmbedUrl,
		@Size(max = 255) String spotifyArtistId,
		@Size(max = 50) Set<@NotNull UUID> instrumentIds,
		@Size(max = 50) List<@NotBlank @Size(max = 64) String> spotifyTrackIds,
		@Valid @Size(max = 50) List<@NotNull SpotifyTrackItemDto> spotifyTracks
		
) {
}

package com.berkayb.soundconnect.modules.profile.MusicianProfile.band.dto.response;

import java.util.List;
import java.util.Set;
import java.util.UUID;

public record BandResponseDto(
		UUID id,
		String name,
		String description,
		UUID profilePictureMediaId,
		String profilePictureUrl,
		String instagramUrl,
		String youtubeUrl,
		String soundCloudUrl,
		String spotifyEmbedUrl,
		String spotifyArtistId,
		List<String> spotifyTrackIds,
		Set<BandMemberResponseDto> members,
		Boolean countsTowardCreationLimit
) {
}

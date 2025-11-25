package com.berkayb.soundconnect.modules.profile.MusicianProfile.band.dto.response;

import java.util.Set;
import java.util.UUID;

public record BandResponseDto(
		UUID id,
		String name,
		String description,
		String profilePicture,
		String instagramUrl,
		String youtubeUrl,
		String soundCloudUrl,
		String spotifyArtistId,
		Set<BandMemberResponseDto> members
) {
}
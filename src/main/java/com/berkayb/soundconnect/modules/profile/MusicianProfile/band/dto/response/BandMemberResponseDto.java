package com.berkayb.soundconnect.modules.profile.MusicianProfile.band.dto.response;

import java.util.UUID;

public record BandMemberResponseDto(
		UUID userId,
		String username,
		String profilePicture,
		String role,
		String status
) {
}
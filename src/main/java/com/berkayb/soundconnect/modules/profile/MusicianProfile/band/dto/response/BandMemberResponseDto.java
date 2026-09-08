package com.berkayb.soundconnect.modules.profile.MusicianProfile.band.dto.response;

import java.util.UUID;

public record BandMemberResponseDto(
		UUID userId,
		String username,
		String profilePicture,
		String role,
		String status,
		String memberTitle,
		long titleVersion
) {
	public BandMemberResponseDto(UUID userId, String username, String profilePicture,
	                             String role, String status) {
		this(userId, username, profilePicture, role, status, null, 0L);
	}
}

package com.berkayb.soundconnect.modules.profile.MusicianProfile.dto.response;

import java.util.UUID;

public record MusicianProfileSearchItemDto(
		UUID profileId,
		UUID userId,
		String username,
		String stageName,
		String profilePictureUrl
) {
}
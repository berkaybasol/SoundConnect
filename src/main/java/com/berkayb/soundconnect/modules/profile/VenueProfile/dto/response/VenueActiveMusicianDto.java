package com.berkayb.soundconnect.modules.profile.VenueProfile.dto.response;

import java.util.UUID;

public record VenueActiveMusicianDto(
		UUID musicianProfileId,
		String displayName,
		String profileImageUrl
) {
}
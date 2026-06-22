package com.berkayb.soundconnect.modules.profile.VenueProfile.dto.response;

import java.util.UUID;

public record VenueActiveBandDto(
		UUID bandId,
		String displayName,
		String profileImageUrl
) {
}
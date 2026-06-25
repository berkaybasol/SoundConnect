package com.berkayb.soundconnect.modules.profile.MusicianProfile.dto.response;

import java.util.UUID;

public record MusicianProfileActiveVenueDto(
		UUID venueId,
		String venueName,
		String profileImageUrl
) {
}

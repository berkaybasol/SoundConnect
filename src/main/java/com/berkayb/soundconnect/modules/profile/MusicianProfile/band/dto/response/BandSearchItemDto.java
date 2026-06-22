package com.berkayb.soundconnect.modules.profile.MusicianProfile.band.dto.response;

import java.util.UUID;

public record BandSearchItemDto(
		UUID bandId,
		String name,
		String profilePictureUrl
) {
}
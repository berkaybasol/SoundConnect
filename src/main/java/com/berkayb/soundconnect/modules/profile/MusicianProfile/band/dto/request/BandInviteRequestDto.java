package com.berkayb.soundconnect.modules.profile.MusicianProfile.band.dto.request;

import java.util.UUID;

public record BandInviteRequestDto(
		UUID bandId,
		UUID userId,
		String message
) {
}
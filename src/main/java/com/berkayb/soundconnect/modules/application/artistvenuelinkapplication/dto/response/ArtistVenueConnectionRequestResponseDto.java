package com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.dto.response;

import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.enums.RequestByType;

import java.util.UUID;

public record ArtistVenueConnectionRequestResponseDto(
		UUID id,
		UUID musicianProfileId,
		UUID venueId,
		String musicianStageName,
		String venueName,
		String message,
		String status,
		RequestByType requestByType,
		String createdAt
		
) {
}
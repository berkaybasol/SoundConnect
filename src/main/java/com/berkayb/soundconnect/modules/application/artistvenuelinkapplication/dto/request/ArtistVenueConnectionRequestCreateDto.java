package com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.dto.request;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record ArtistVenueConnectionRequestCreateDto(
		
		UUID musicianProfileId, // basvuran sanatci
		UUID bandId,
		
		@NotNull
		UUID venueId, // hedef mekan
		String message
) {
}
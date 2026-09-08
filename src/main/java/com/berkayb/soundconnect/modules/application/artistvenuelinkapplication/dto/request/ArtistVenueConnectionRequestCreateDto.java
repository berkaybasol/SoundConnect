package com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record ArtistVenueConnectionRequestCreateDto(
		
		UUID musicianProfileId, // basvuran sanatci
		UUID bandId,
		
		@NotNull
		UUID venueId, // hedef mekan
		@Size(max = 255)
		String message
) {
}

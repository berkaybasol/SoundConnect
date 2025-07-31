package com.berkayb.soundconnect.modules.artistvenueconnection.dto.request;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record ArtistVenueConnectionRequestCreateDto(
		@NotNull
		UUID musicianProfileId, // basvuran sanatci
		@NotNull
		UUID venueId, // hedef mekan
		String message
) {
}
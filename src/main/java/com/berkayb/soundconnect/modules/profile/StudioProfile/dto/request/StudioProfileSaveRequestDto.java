package com.berkayb.soundconnect.modules.profile.StudioProfile.dto.request;

import com.berkayb.soundconnect.modules.spotify.dto.response.SpotifyTrackItemDto;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.PositiveOrZero;

import java.util.List;
import java.util.Set;
import java.util.UUID;

public record StudioProfileSaveRequestDto(
		@Size(max = 100) String name,
		@JsonAlias("description") @Size(max = 1024) String descpriction,
		UUID profilePicture,
		@JsonAlias("address") @Size(max = 255) String adress,
		@Size(max = 32) String phone,
		@Size(max = 255) String website,
		@Size(max = 50) Set<@Size(max = 60) String> facilities,
		@Size(max = 255) String instagramUrl,
		@Size(max = 255) String youtubeUrl,
		@Size(max = 64) String timeZone,
		@PositiveOrZero Long version,
		@Size(max = 50) List<@Size(min = 1, max = 64) String> spotifyTrackIds,
		@JsonProperty(access = JsonProperty.Access.READ_ONLY)
		@Valid @Size(max = 50) List<SpotifyTrackItemDto> spotifyTracks
) {
	/** Compatibility constructor for the original Studio profile contract. */
	public StudioProfileSaveRequestDto(
			String name,
			String descpriction,
			UUID profilePicture,
			String adress,
			String phone,
			String website,
			Set<String> facilities,
			String instagramUrl,
			String youtubeUrl
	) {
		this(name, descpriction, profilePicture, adress, phone, website, facilities,
				instagramUrl, youtubeUrl, null, null, null, null);
	}
}

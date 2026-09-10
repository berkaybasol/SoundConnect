package com.berkayb.soundconnect.modules.spotify.dto.response;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public record SpotifyTrackItemDto(
		@NotBlank @Size(max = 64) String spotifyTrackId,
		@Size(max = 255) String name,
		@Min(0) @Max(86_400_000) Integer durationMs,
		boolean explicit,
		@Size(max = 2048) String previewUrl, // 30 sn preview icin. yoksa null
		@Size(max = 2048) String spotifyUrl, // tamamini dinle kismi icin
		@Size(max = 255) String albumName,
		@Size(max = 2048) String albumImageUrl, // UI karti icin ama kullanmicaz bizim ui da gorsel yok
		@Size(max = 20) List<@Size(max = 255) String> artistNames,
		@Size(max = 20) List<@NotBlank @Size(max = 64) String> artistIds
) {
	public SpotifyTrackItemDto {
		artistIds = artistIds == null ? List.of() : List.copyOf(artistIds);
	}

	public SpotifyTrackItemDto(String spotifyTrackId, String name, Integer durationMs, boolean explicit,
	                          String previewUrl, String spotifyUrl, String albumName, String albumImageUrl,
	                          List<String> artistNames) {
		this(spotifyTrackId, name, durationMs, explicit, previewUrl, spotifyUrl, albumName, albumImageUrl,
				artistNames, List.of());
	}
}

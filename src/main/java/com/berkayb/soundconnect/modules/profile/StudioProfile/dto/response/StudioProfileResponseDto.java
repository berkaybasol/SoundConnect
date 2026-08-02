package com.berkayb.soundconnect.modules.profile.StudioProfile.dto.response;

import com.berkayb.soundconnect.modules.spotify.dto.response.SpotifyTrackItemDto;

import java.util.List;
import java.util.Set;
import java.util.UUID;

public record StudioProfileResponseDto(
		UUID id,
		UUID userId,
		String name,
		String description,
		UUID profilePictureMediaId,
		String profilePictureUrl,
		String adress,
		UUID cityId,
		String cityName,
		UUID districtId,
		String districtName,
		UUID neighborhoodId,
		String neighborhoodName,
		String phone,
		String website,
		Set<String> facilities,
		String instagramUrl,
		String youtubeUrl,
		String timeZone,
		long version,
		List<String> spotifyTrackIds,
		List<SpotifyTrackItemDto> spotifyTracks,
		long activeRoomCount,
		long backlineUnitCount
) {
	/** Compatibility constructor for existing callers while clients migrate. */
	public StudioProfileResponseDto(
			UUID id,
			UUID userId,
			String name,
			String description,
			UUID profilePictureMediaId,
			String profilePictureUrl,
			String adress,
			String phone,
			String website,
			Set<String> facilities,
			String instagramUrl,
			String youtubeUrl
	) {
		this(id, userId, name, description, profilePictureMediaId, profilePictureUrl,
				adress, null, null, null, null, null, null, phone, website, facilities, instagramUrl, youtubeUrl,
				"Europe/Istanbul", 0L, List.of(), List.of(), 0L, 0L);
	}
}

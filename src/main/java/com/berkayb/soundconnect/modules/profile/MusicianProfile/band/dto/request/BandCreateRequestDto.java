package com.berkayb.soundconnect.modules.profile.MusicianProfile.band.dto.request;

public record BandCreateRequestDto(
		String name,
		String description,
		String profilePicture,
		String instagramUrl,
		String youtubeUrl,
		String soundCloudUrl
) {
}
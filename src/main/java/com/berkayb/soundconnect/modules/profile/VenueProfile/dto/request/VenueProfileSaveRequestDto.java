package com.berkayb.soundconnect.modules.profile.VenueProfile.dto.request;


import java.util.UUID;
import jakarta.validation.constraints.Size;

public record VenueProfileSaveRequestDto(
		@Size(max = 1024) String bio,
		UUID profilePicture,
		@Size(max = 255) String instagramUrl,
		@Size(max = 255) String youtubeUrl,
		@Size(max = 255) String websiteUrl
) {
}

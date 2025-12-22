package com.berkayb.soundconnect.modules.profile.ListenerProfile.dto.request;

import java.util.UUID;

public record ListenerSaveRequestDto(
		String description,
		UUID profilePictureMediaId
) {
}
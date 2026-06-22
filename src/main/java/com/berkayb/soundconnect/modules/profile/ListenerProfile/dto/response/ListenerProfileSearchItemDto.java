package com.berkayb.soundconnect.modules.profile.ListenerProfile.dto.response;

import java.util.UUID;

public record ListenerProfileSearchItemDto(
		UUID profileId,
		UUID userId,
		String username,
		String bio,
		String profilePictureUrl
) {}
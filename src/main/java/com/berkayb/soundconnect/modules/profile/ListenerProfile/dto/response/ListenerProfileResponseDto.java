package com.berkayb.soundconnect.modules.profile.ListenerProfile.dto.response;

import java.util.UUID;

public record ListenerProfileResponseDto(
		UUID id,
		String bio,
		UUID profilePictureMediaId,
		UUID userId
) {}
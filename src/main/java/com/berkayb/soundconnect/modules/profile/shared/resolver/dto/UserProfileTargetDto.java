package com.berkayb.soundconnect.modules.profile.shared.resolver.dto;

import java.util.UUID;

public record UserProfileTargetDto(
		String type,              // MUSICIAN, VENUE, LISTENER, STUDIO, ...
		UUID profileId,
		String displayName,
		String profilePictureUrl
) {}
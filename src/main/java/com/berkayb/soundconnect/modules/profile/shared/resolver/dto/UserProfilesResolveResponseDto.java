package com.berkayb.soundconnect.modules.profile.shared.resolver.dto;

import java.util.List;
import java.util.UUID;

public record UserProfilesResolveResponseDto(
		UUID userId,
		List<UserProfileTargetDto> profiles
) {}
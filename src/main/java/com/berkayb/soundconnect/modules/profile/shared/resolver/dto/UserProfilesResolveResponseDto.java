package com.berkayb.soundconnect.modules.profile.shared.resolver.dto;

import java.util.List;
import java.util.UUID;

public record UserProfilesResolveResponseDto(
		UUID userId,
		List<UserProfileTargetDto> profiles,
		@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
		String accessRestriction
) {
	public UserProfilesResolveResponseDto(UUID userId, List<UserProfileTargetDto> profiles) {
		this(userId, profiles, null);
	}
}

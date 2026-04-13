package com.berkayb.soundconnect.modules.profile.shared.resolver.service;

import com.berkayb.soundconnect.modules.profile.shared.resolver.dto.UserProfilesResolveResponseDto;

import java.util.UUID;

public interface PublicProfileResolverService {
	UserProfilesResolveResponseDto resolveByUserId(UUID userId);
}
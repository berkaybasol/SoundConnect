package com.berkayb.soundconnect.modules.profile.shared.resolver.contributor;

import com.berkayb.soundconnect.modules.profile.shared.resolver.dto.UserProfileTargetDto;

import java.util.List;
import java.util.UUID;

public interface PublicProfileContributor {
	String type();
	List<UserProfileTargetDto> resolve(UUID userId);
}
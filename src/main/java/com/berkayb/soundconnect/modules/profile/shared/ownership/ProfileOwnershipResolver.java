package com.berkayb.soundconnect.modules.profile.shared.ownership;

import com.berkayb.soundconnect.modules.profile.shared.media.enums.ProfileType;

import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface ProfileOwnershipResolver {

	List<OwnedProfileTarget> resolveOwnedProfiles(UUID userId, Set<ProfileType> allowedTypes);

	OwnedProfileTarget requireOwnership(UUID userId, ProfileType type, UUID sourceId);
}

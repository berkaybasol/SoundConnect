package com.berkayb.soundconnect.modules.profile.shared.ownership;

import com.berkayb.soundconnect.modules.profile.shared.media.enums.ProfileType;

import java.util.UUID;

public record OwnedProfileTarget(
		ProfileType type,
		UUID sourceId,
		String displayName,
		String profilePictureUrl
) {
}

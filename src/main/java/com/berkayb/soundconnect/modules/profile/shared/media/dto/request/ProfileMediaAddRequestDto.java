package com.berkayb.soundconnect.modules.profile.shared.media.dto.request;

import com.berkayb.soundconnect.modules.profile.shared.media.enums.ProfileMediaRole;
import com.berkayb.soundconnect.modules.profile.shared.media.enums.ProfileType;

import java.util.UUID;

public record ProfileMediaAddRequestDto(
		ProfileType profileType,
		UUID profileId,
		UUID mediaAssetId,
		ProfileMediaRole role,
		Integer orderIndex
) {
}
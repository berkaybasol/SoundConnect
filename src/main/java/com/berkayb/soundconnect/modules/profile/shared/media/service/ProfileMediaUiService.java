package com.berkayb.soundconnect.modules.profile.shared.media.service;

import com.berkayb.soundconnect.modules.profile.shared.media.dto.response.ProfileMediaUiResponseDto;
import com.berkayb.soundconnect.modules.profile.shared.media.enums.ProfileType;

import java.util.UUID;

public interface ProfileMediaUiService {
	
	ProfileMediaUiResponseDto getProfileMedia(ProfileType profileType, UUID profileId);
}
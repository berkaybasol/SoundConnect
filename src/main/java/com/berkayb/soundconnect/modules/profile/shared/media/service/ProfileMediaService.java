package com.berkayb.soundconnect.modules.profile.shared.media.service;

import com.berkayb.soundconnect.modules.profile.shared.media.entity.ProfileMedia;
import com.berkayb.soundconnect.modules.profile.shared.media.enums.ProfileMediaRole;
import com.berkayb.soundconnect.modules.profile.shared.media.enums.ProfileType;

import java.util.List;
import java.util.UUID;

public interface ProfileMediaService {
	
	// tekil medya
	ProfileMedia getSingleMedia(ProfileType profileType, UUID profileId, ProfileMediaRole role);
	
	List<ProfileMedia> getMediaList(ProfileType profileType, UUID profileId, ProfileMediaRole role);
	
	ProfileMedia addMedia(ProfileType profileType, UUID profileId, UUID mediaAssetId, ProfileMediaRole role, Integer orderIndex);
	
	// Media silme
	void removeMedia(UUID profileMediaId);
}
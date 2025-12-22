package com.berkayb.soundconnect.modules.profile.shared.media.repository;

import com.berkayb.soundconnect.modules.profile.shared.media.entity.ProfileMedia;
import com.berkayb.soundconnect.modules.profile.shared.media.enums.ProfileMediaRole;
import com.berkayb.soundconnect.modules.profile.shared.media.enums.ProfileType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProfileMediaRepository extends JpaRepository<ProfileMedia, UUID> {
	
	// belirli bir profile ait, belirli roldeki tum medyalar
	List<ProfileMedia> findByProfileTypeAndProfileIdAndRoleOrderByOrderIndexAsc(ProfileType profileType, UUID profileId, ProfileMediaRole role);
	
	// belirli bir profile ait, belirli rolde tek medya
	Optional<ProfileMedia> findFirstByProfileTypeAndProfileIdAndRole(ProfileType profileType, UUID profileId, ProfileMediaRole role);
	
	// Profile;a ait tum medyalar (admin icin)
	List<ProfileMedia> findByProfileTypeAndProfileId(ProfileType profileType, UUID profileId);
}
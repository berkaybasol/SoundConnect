package com.berkayb.soundconnect.modules.profile.shared.media.service;

import com.berkayb.soundconnect.modules.profile.shared.media.entity.ProfileMedia;
import com.berkayb.soundconnect.modules.profile.shared.media.enums.ProfileMediaRole;
import com.berkayb.soundconnect.modules.profile.shared.media.enums.ProfileType;
import com.berkayb.soundconnect.modules.profile.shared.media.repository.ProfileMediaRepository;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j

public class ProfileMediaServiceImpl implements ProfileMediaService{
	
	private final ProfileMediaRepository profileMediaRepository;
	
	
	@Override
	@Transactional(readOnly = true)
	public ProfileMedia getSingleMedia(ProfileType profileType, UUID profileId, ProfileMediaRole role) {
		return profileMediaRepository.findFirstByProfileTypeAndProfileIdAndRole(profileType, profileId, role).orElse(null);
	}
	
	@Override
	@Transactional(readOnly = true)
	public List<ProfileMedia> getMediaList(ProfileType profileType, UUID profileId, ProfileMediaRole role) {
		return profileMediaRepository.findByProfileTypeAndProfileIdAndRoleOrderByOrderIndexAsc(profileType, profileId, role);
	}
	
	@Override
	@Transactional
	public ProfileMedia addMedia(ProfileType profileType, UUID profileId, UUID mediaAssetId, ProfileMediaRole role, Integer orderIndex) {
		ProfileMedia media = ProfileMedia.builder()
				.profileType(profileType)
				.profileId(profileId)
				.mediaAssetId(mediaAssetId)
				.role(role)
				.orderIndex(orderIndex)
				.build();
		ProfileMedia saved = profileMediaRepository.save(media);
		
		log.info("[ProfileMedia] added profileType={} profileId={} mediaAssetId={} role={}",
		         profileType, profileId, mediaAssetId, role);
		return saved;
	}
	
	@Override
	@Transactional
	public void removeMedia(UUID profileMediaId) {
		if (!profileMediaRepository.existsById(profileMediaId)) {
			throw new SoundConnectException(ErrorType.PROFILE_MEDIA_NOT_FOUND);
		}
		profileMediaRepository.deleteById(profileMediaId);
	}
}
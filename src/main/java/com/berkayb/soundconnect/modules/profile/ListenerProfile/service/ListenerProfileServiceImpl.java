package com.berkayb.soundconnect.modules.profile.ListenerProfile.service;

import com.berkayb.soundconnect.modules.follow.service.FollowService;
import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.media.enums.MediaKind;
import com.berkayb.soundconnect.modules.media.enums.MediaOwnerType;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.dto.request.ListenerSaveRequestDto;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.dto.response.ListenerProfileResponseDto;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.dto.response.ListenerProfileSearchItemDto;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.entity.ListenerProfile;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.mapper.ListenerProfileMapper;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.repository.ListenerProfileRepository;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.support.UserEntityFinder;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.berkayb.soundconnect.shared.util.UsernameUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ListenerProfileServiceImpl implements ListenerProfileService {
	
	private final ListenerProfileRepository listenerProfileRepository;
	private final UserEntityFinder userEntityFinder;
	private final ListenerProfileMapper listenerProfileMapper;
	private final MediaAssetService mediaAssetService;
	private final FollowService followService;
	
	@Override
	public List<ListenerProfileSearchItemDto> searchProfiles(String query) {
		String q = query == null ? "" : UsernameUtils.stripBoundaryWhitespace(query);
		if (q.isEmpty()) return List.of();
		
		return listenerProfileRepository.searchByUsernameOrBio(
				q,
				UsernameUtils.normalize(q),
				PageRequest.of(0, 10)
		)
		                                .stream()
		                                .limit(10)
		                                .map(profile -> new ListenerProfileSearchItemDto(
				                                profile.getId(),
				                                profile.getUser().getId(),
				                                profile.getUser().getUsername(),
				                                profile.getDescription(),
				                                resolveProfilePictureUrl(profile.getProfilePictureMediaId())
		                                ))
		                                .toList();
	}
	
	@Override
	public ListenerProfileResponseDto getProfileByProfileId(UUID profileId) {
		ListenerProfile profile = listenerProfileRepository.findById(profileId)
		                                                   .orElseThrow(() -> {
			                                                   log.warn("Listener profile not found. profileId={}", profileId);
			                                                   return new SoundConnectException(ErrorType.PROFILE_NOT_FOUND);
		                                                   });
		
		return toResponse(profile);
	}
	
	@Override
	@Transactional
	public ListenerProfileResponseDto createProfile(UUID userId, ListenerSaveRequestDto dto) {
		User user = userEntityFinder.getUser(userId);
		
		if (listenerProfileRepository.findByUserId(user.getId()).isPresent()) {
			log.warn("Listener profile already exists. userId={}", userId);
			throw new SoundConnectException(ErrorType.PROFILE_ALREADY_EXISTS);
		}
		if (dto.profilePictureMediaId() != null) {
			mediaAssetService.validateAssignableMedia(
					userId, dto.profilePictureMediaId(), MediaOwnerType.USER, userId, MediaKind.IMAGE
			);
		}
		
		ListenerProfile profile = ListenerProfile.builder()
		                                         .user(user)
		                                         .description(dto.description())
		                                         .profilePictureMediaId(dto.profilePictureMediaId())
		                                         .build();
		
		ListenerProfile saved = listenerProfileRepository.save(profile);
		log.info("Listener profile created. userId={}", userId);
		
		return toResponse(saved);
	}
	
	@Override
	public ListenerProfileResponseDto getProfileByUserId(UUID userId) {
		userEntityFinder.getUser(userId);
		
		ListenerProfile profile = listenerProfileRepository.findByUserId(userId)
		                                                   .orElseThrow(() -> {
			                                                   log.warn("Listener profile not found. userId={}", userId);
			                                                   return new SoundConnectException(ErrorType.PROFILE_NOT_FOUND);
		                                                   });
		
		return toResponse(profile);
	}
	
	@Override
	@Transactional
	public ListenerProfileResponseDto updateProfile(UUID userId, ListenerSaveRequestDto dto) {
		userEntityFinder.getUser(userId);
		
		ListenerProfile profile = listenerProfileRepository.findByUserId(userId)
		                                                   .orElseThrow(() -> {
			                                                   log.warn("Listener profile not found. userId={}", userId);
			                                                   return new SoundConnectException(ErrorType.PROFILE_NOT_FOUND);
		                                                   });
		
		if (dto.description() != null) profile.setDescription(dto.description());
		if (dto.profilePictureMediaId() != null) {
			mediaAssetService.validateAssignableMedia(
					userId, dto.profilePictureMediaId(), MediaOwnerType.LISTENER_PROFILE, profile.getId(), MediaKind.IMAGE
			);
			profile.setProfilePictureMediaId(dto.profilePictureMediaId());
		}
		
		ListenerProfile updated = listenerProfileRepository.save(profile);
		log.info("Listener profile updated. userId={}", userId);
		
		return toResponse(updated);
	}
	
	private ListenerProfileResponseDto toResponse(ListenerProfile profile) {
		ListenerProfileResponseDto base = listenerProfileMapper.toDto(profile);
		
		String profilePictureUrl = resolveProfilePictureUrl(profile.getProfilePictureMediaId());
		long followerCount = followService.countFollowers(profile.getUser());
		long followingCount = followService.countFollowing(profile.getUser());
		
		return new ListenerProfileResponseDto(
				base.id(),
				base.userId(),
				base.username(),
				base.bio(),
				base.profilePictureMediaId(),
				profilePictureUrl,
				followerCount,
				followingCount
		);
	}
	
	private String resolveProfilePictureUrl(UUID mediaAssetId) {
		if (mediaAssetId == null) return null;
		try {
			return mediaAssetService.getDisplayUrl(mediaAssetId);
		} catch (Exception e) {
			log.warn("Listener profile picture resolve failed. mediaAssetId={}", mediaAssetId);
			return null;
		}
	}
}

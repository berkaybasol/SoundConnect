package com.berkayb.soundconnect.modules.profile.shared.media.service;

import com.berkayb.soundconnect.modules.media.mapper.MediaAssetMapper;
import com.berkayb.soundconnect.modules.media.dto.response.MediaResponseDto;
import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.support.ListenerVisibilityPolicy;
import com.berkayb.soundconnect.modules.profile.shared.media.dto.response.ProfileMediaUiResponseDto;
import com.berkayb.soundconnect.modules.profile.shared.media.entity.ProfileMedia;
import com.berkayb.soundconnect.modules.profile.shared.media.enums.ProfileMediaRole;
import com.berkayb.soundconnect.modules.profile.shared.media.enums.ProfileType;
import com.berkayb.soundconnect.modules.track.dto.response.TrackResponseDto;
import com.berkayb.soundconnect.modules.track.enums.TrackOwnerType;
import com.berkayb.soundconnect.modules.track.service.TrackService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProfileMediaUiServiceImpl implements ProfileMediaUiService {
	
	private final ProfileMediaService profileMediaService;
	private final MediaAssetService mediaAssetService;
	private final MediaAssetMapper mediaAssetMapper;
	private final TrackService trackService;
	private final ListenerVisibilityPolicy listenerVisibilityPolicy;
	
	@Override
	@Transactional
	public ProfileMediaUiResponseDto getProfileMedia(ProfileType profileType, UUID profileId) {
		if (profileType == ProfileType.LISTENER
				&& listenerVisibilityPolicy.lockForReadAndIsPubliclyRestrictedProfile(profileId)) {
			return new ProfileMediaUiResponseDto(null, List.of(), List.of());
		}
		
		// featured video
		ProfileMedia featured = profileMediaService.getSingleMedia(
				profileType,
				profileId,
				ProfileMediaRole.FEATURED_VIDEO
		);
		
		MediaResponseDto featuredDto = featured == null
				? null
				: toPublicDtoOrNull(featured.getMediaAssetId());
		
		// gallery videos
		List<ProfileMedia> gallery = profileMediaService.getMediaList(
				profileType,
				profileId,
				ProfileMediaRole.GALLERY
		);
		
		List<MediaResponseDto> videos = gallery.stream()
				.map(pm -> toPublicDtoOrNull(pm.getMediaAssetId()))
				.filter(java.util.Objects::nonNull)
				.toList();
		
		// audios (Track) //FIXME SESLER KISMI EKLEMEK ISTEDIGIN PROFILLERE BURDAN EKLEME YAP
		TrackOwnerType ownerType = switch (profileType) {
			case MUSICIAN -> TrackOwnerType.MUSICIAN_PROFILE;
			case BAND -> TrackOwnerType.BAND;
			case STUDIO -> TrackOwnerType.STUDIO_PROFILE;
			case PRODUCER -> TrackOwnerType.PRODUCER_PROFILE;
			default -> null;
		};
		
		List<TrackResponseDto> audios =
				ownerType == null ? List.of() : trackService.getTracksByOwner(profileId, ownerType);
		
		log.info("[ProfileMediaUI] loaded profileType={} profileId={}", profileType, profileId);
		
		return new ProfileMediaUiResponseDto(featuredDto, videos, audios);
	}

	private MediaResponseDto toPublicDtoOrNull(UUID mediaAssetId) {
		try {
			return mediaAssetMapper.toDto(mediaAssetService.getPublicReadyById(mediaAssetId));
		} catch (RuntimeException exception) {
			log.warn("[ProfileMediaUI] unavailable public media omitted mediaAssetId={}", mediaAssetId);
			return null;
		}
	}
}

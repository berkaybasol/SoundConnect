package com.berkayb.soundconnect.modules.profile.shared.media.service;

import com.berkayb.soundconnect.modules.media.entity.MediaAsset;
import com.berkayb.soundconnect.modules.media.enums.MediaOwnerType;
import com.berkayb.soundconnect.modules.media.enums.MediaKind;
import com.berkayb.soundconnect.modules.media.enums.MediaStatus;
import com.berkayb.soundconnect.modules.media.enums.MediaVisibility;
import com.berkayb.soundconnect.modules.media.repository.MediaAssetRepository;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.repository.ListenerProfileRepository;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity.Band;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.enums.BandMemberShipStatus;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.enums.BandRole;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.repository.BandRepository;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.repository.MusicianProfileRepository;
import com.berkayb.soundconnect.modules.profile.OrganizerProfile.repository.OrganizerProfileRepository;
import com.berkayb.soundconnect.modules.profile.ProducerProfile.repository.ProducerProfileRepository;
import com.berkayb.soundconnect.modules.profile.StudioProfile.repository.StudioProfileRepository;
import com.berkayb.soundconnect.modules.profile.VenueProfile.repository.VenueProfileRepository;
import com.berkayb.soundconnect.modules.profile.shared.media.entity.ProfileMedia;
import com.berkayb.soundconnect.modules.profile.shared.media.enums.ProfileMediaRole;
import com.berkayb.soundconnect.modules.profile.shared.media.enums.ProfileType;
import com.berkayb.soundconnect.modules.profile.shared.media.repository.ProfileMediaRepository;
import com.berkayb.soundconnect.modules.venue.repository.VenueRepository;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j

public class ProfileMediaServiceImpl implements ProfileMediaService{
	
	private final ProfileMediaRepository profileMediaRepository;
	private final MediaAssetRepository mediaAssetRepository;
	private final BandRepository bandRepository;
	private final VenueRepository venueRepository;
	private final MusicianProfileRepository musicianProfileRepository;
	private final ProducerProfileRepository producerProfileRepository;
	private final OrganizerProfileRepository organizerProfileRepository;
	private final StudioProfileRepository studioProfileRepository;
	private final ListenerProfileRepository listenerProfileRepository;
	private final VenueProfileRepository venueProfileRepository;
	
	
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
	public ProfileMedia addMedia(UUID actingUserId, ProfileType profileType, UUID profileId, UUID mediaAssetId, ProfileMediaRole role, Integer orderIndex) {
		if (profileType == null || profileId == null || mediaAssetId == null || role == null
				|| (orderIndex != null && orderIndex < 0)) {
			throw new SoundConnectException(ErrorType.BAD_REQUEST);
		}
		assertCanManageProfile(actingUserId, profileType, profileId);
		MediaAsset asset = mediaAssetRepository.findByIdForUpdate(mediaAssetId)
				.orElseThrow(() -> new SoundConnectException(ErrorType.MEDIA_ASSET_NOT_FOUND));
		assertMediaBelongsToProfile(profileType, profileId, asset);
		ProfileMedia existing = profileMediaRepository
				.findByProfileTypeAndProfileIdAndMediaAssetIdAndRole(
						profileType, profileId, mediaAssetId, role)
				.orElse(null);
		if (existing != null) {
			log.info("[ProfileMedia] idempotent replay profileType={} profileId={} mediaAssetId={} role={}",
					profileType, profileId, mediaAssetId, role);
			return existing;
		}
		if (!isAttachableProfileMedia(asset, role)) {
			throw new SoundConnectException(ErrorType.MEDIA_ASSET_STATE_INVALID);
		}
		
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

	/**
	 * Gallery images are attached only after synchronous validation completes.
	 * Videos may be associated after durable queueing; public profile reads still
	 * omit them until READY, so processing media never leaks while the UI polls.
	 */
	static boolean isAttachableProfileMedia(MediaAsset asset, ProfileMediaRole role) {
		if (asset == null
				|| role == null
				|| asset.getVisibility() != MediaVisibility.PUBLIC) {
			return false;
		}
		if (asset.getKind() == MediaKind.IMAGE) {
			return role == ProfileMediaRole.GALLERY
					&& asset.getStatus() == MediaStatus.READY
					&& (StringUtils.hasText(asset.getSourceUrl())
							|| StringUtils.hasText(asset.getPlaybackUrl()));
		}
		if (asset.getKind() != MediaKind.VIDEO) {
			return false;
		}
		return switch (asset.getStatus()) {
			case TRANSCODE_QUEUED, TRANSCODE_SENT, PROCESSING -> true;
			case READY -> StringUtils.hasText(asset.getPlaybackUrl());
			default -> false;
		};
	}
	
	@Override
	@Transactional
	public void removeMedia(UUID actingUserId, UUID profileMediaId) {
		ProfileMedia profileMedia = profileMediaRepository.findById(profileMediaId)
				.orElseThrow(() -> new SoundConnectException(ErrorType.PROFILE_MEDIA_NOT_FOUND));
		assertCanManageProfile(actingUserId, profileMedia.getProfileType(), profileMedia.getProfileId());
		profileMediaRepository.delete(profileMedia);
	}

	private void assertCanManageProfile(UUID actingUserId, ProfileType profileType, UUID profileId) {
		if (profileType == null || profileId == null || !canManageProfile(actingUserId, profileType, profileId)) {
			throw new SoundConnectException(ErrorType.FORBIDDEN_ACCESS);
		}
	}

	private boolean canManageProfile(UUID actingUserId, ProfileType profileType, UUID profileId) {
		return switch (profileType) {
			case MUSICIAN -> musicianProfileRepository.findById(profileId)
					.map(profile -> profile.getUser() != null && profile.getUser().getId().equals(actingUserId))
					.orElse(false);
			case BAND -> bandRepository.findById(profileId)
					.map(band -> canManageBand(actingUserId, band))
					.orElse(false);
			case VENUE -> venueRepository.findById(profileId)
					.map(venue -> venue.getOwner() != null && venue.getOwner().getId().equals(actingUserId))
					.orElseGet(() -> venueProfileRepository.findById(profileId)
							.map(profile -> profile.getVenue() != null
									&& profile.getVenue().getOwner() != null
									&& profile.getVenue().getOwner().getId().equals(actingUserId))
							.orElse(false));
			case PRODUCER -> producerProfileRepository.findById(profileId)
					.map(profile -> profile.getUser() != null && profile.getUser().getId().equals(actingUserId))
					.orElse(false);
			case ORGANIZER -> organizerProfileRepository.findById(profileId)
					.map(profile -> profile.getUser() != null && profile.getUser().getId().equals(actingUserId))
					.orElse(false);
			case STUDIO -> studioProfileRepository.findById(profileId)
					.map(profile -> profile.getUser() != null && profile.getUser().getId().equals(actingUserId))
					.orElse(false);
			case LISTENER -> listenerProfileRepository.findById(profileId)
					.map(profile -> profile.getUser() != null && profile.getUser().getId().equals(actingUserId))
					.orElse(false);
			case MUSIC_HOUSE, MANAGER, STUDENT_SOCIETIES, VISUAL_PRODUCTION_SPECIALIST -> false;
		};
	}

	private void assertMediaBelongsToProfile(ProfileType profileType, UUID profileId, MediaAsset asset) {
		boolean matches = switch (profileType) {
			case MUSICIAN -> asset.getOwnerType() == MediaOwnerType.MUSICIAN_PROFILE && asset.getOwnerId().equals(profileId);
			case BAND -> asset.getOwnerType() == MediaOwnerType.BAND && asset.getOwnerId().equals(profileId);
			case VENUE -> (asset.getOwnerType() == MediaOwnerType.VENUE || asset.getOwnerType() == MediaOwnerType.VENUE_PROFILE)
					&& asset.getOwnerId().equals(profileId);
			case PRODUCER -> asset.getOwnerType() == MediaOwnerType.PRODUCER_PROFILE && asset.getOwnerId().equals(profileId);
			case ORGANIZER -> asset.getOwnerType() == MediaOwnerType.ORGANIZER_PROFILE && asset.getOwnerId().equals(profileId);
			case STUDIO -> asset.getOwnerType() == MediaOwnerType.STUDIO_PROFILE && asset.getOwnerId().equals(profileId);
			case LISTENER -> asset.getOwnerType() == MediaOwnerType.LISTENER_PROFILE && asset.getOwnerId().equals(profileId);
			case MUSIC_HOUSE, MANAGER, STUDENT_SOCIETIES, VISUAL_PRODUCTION_SPECIALIST -> false;
		};
		if (!matches) {
			throw new SoundConnectException(ErrorType.MEDIA_ASSET_DELETE_FORBIDDEN);
		}
	}

	private boolean canManageBand(UUID actingUserId, Band band) {
		return band.getMembers().stream()
				.anyMatch(member -> member.getUser() != null
						&& member.getUser().getId().equals(actingUserId)
						&& member.getStatus() == BandMemberShipStatus.ACTIVE
						&& (member.getBandRole() == BandRole.FOUNDER || member.getBandRole() == BandRole.MANAGER));
	}
}

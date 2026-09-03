package com.berkayb.soundconnect.modules.profile.ListenerProfile.service;

import com.berkayb.soundconnect.modules.follow.service.FollowGraphMutationService;
import com.berkayb.soundconnect.modules.follow.service.FollowService;
import com.berkayb.soundconnect.modules.media.enums.MediaKind;
import com.berkayb.soundconnect.modules.media.enums.MediaOwnerType;
import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.dto.request.ListenerAvatarUpdateRequestDto;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.dto.request.ListenerSaveRequestDto;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.dto.request.ListenerVisibilityUpdateRequestDto;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.dto.response.ListenerProfileOwnerResponseDto;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.dto.response.ListenerProfilePublicResponseDto;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.dto.response.ListenerProfileResponseDto;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.dto.response.ListenerProfileSearchItemDto;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.entity.ListenerProfile;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.enums.ListenerVisibilityMode;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.mapper.ListenerProfileMapper;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.repository.ListenerProfileRepository;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.support.ListenerProfileProvisioner;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.support.ListenerVisibilityTimeProvider;
import com.berkayb.soundconnect.modules.profile.shared.type.PersonalProfileTypePolicy;
import com.berkayb.soundconnect.modules.role.enums.RoleEnum;
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
import java.util.Objects;
import java.util.UUID;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Service
@RequiredArgsConstructor
@Slf4j
public class ListenerProfileServiceImpl implements ListenerProfileService {

	private static final int SEARCH_RESULT_LIMIT = 10;
	private static final int SEARCH_QUERY_MAX_LENGTH = 100;

	private final ListenerProfileRepository listenerProfileRepository;
	private final UserEntityFinder userEntityFinder;
	private final ListenerProfileMapper listenerProfileMapper;
	private final MediaAssetService mediaAssetService;
	private final FollowService followService;
	private final FollowGraphMutationService followGraphMutationService;
	private final ListenerVisibilityTimeProvider visibilityTimeProvider;
	private final PersonalProfileTypePolicy personalProfileTypePolicy;
	private final ListenerProfileProvisioner listenerProfileProvisioner;

	@Override
	@Transactional
	public List<ListenerProfileSearchItemDto> searchProfiles(String query) {
		String q = query == null ? "" : UsernameUtils.stripBoundaryWhitespace(query);
		if (q.isEmpty()) return List.of();
		if (q.length() > SEARCH_QUERY_MAX_LENGTH) {
			throw new SoundConnectException(
					ErrorType.VALIDATION_ERROR,
					"Listener profile search query cannot exceed " + SEARCH_QUERY_MAX_LENGTH + " characters"
			);
		}

		return listenerProfileRepository.searchForPublicDiscovery(
				q,
				UsernameUtils.normalize(q),
				ListenerVisibilityMode.GHOST,
				PageRequest.of(0, SEARCH_RESULT_LIMIT)
		)
				.stream()
				.limit(SEARCH_RESULT_LIMIT)
				.map(this::toSearchResponse)
				.toList();
	}

	@Override
	@Transactional
	public ListenerProfilePublicResponseDto getProfileByProfileId(UUID profileId) {
		ListenerProfile profile = listenerProfileRepository.findByIdForVisibilityRead(profileId)
		                                                   .orElseThrow(() -> profileNotFoundById(profileId));
		if (!profile.isVisibilityChoiceCompleted()) {
			throw profileNotFoundById(profileId);
		}
		return toPublicResponse(profile);
	}

	@Override
	@Transactional
	public ListenerProfileOwnerResponseDto createProfile(UUID userId, ListenerSaveRequestDto dto) {
		validateProfileContentCommand(dto);
		User user = personalProfileTypePolicy.lockAndAssertCanAcquire(
				userId, RoleEnum.ROLE_LISTENER);
		if (listenerProfileRepository.findByUserId(userId).isPresent()) {
			log.warn("Listener profile already exists. userId={}", userId);
			throw new SoundConnectException(ErrorType.PROFILE_ALREADY_EXISTS);
		}
		if (dto.profilePictureMediaId() != null) {
			mediaAssetService.validateAssignableMedia(
					userId,
					dto.profilePictureMediaId(),
					MediaOwnerType.USER,
					userId,
					MediaKind.IMAGE
			);
		}

		ListenerProfile profile = ListenerProfile.builder()
		                                          .user(user)
		                                          .description(dto.description())
		                                          .profilePictureMediaId(dto.profilePictureMediaId())
		                                          .visibilityMode(ListenerVisibilityMode.STANDARD)
		                                          .visibilityChoiceCompleted(false)
		                                          .build();

		ListenerProfile saved = listenerProfileRepository.saveAndFlush(profile);
		log.info("Listener profile created. userId={} profileId={}", userId, saved.getId());
		return toOwnerResponse(saved);
	}

	@Override
	@Transactional
	public ListenerProfileOwnerResponseDto getMyProfile(UUID userId) {
		userEntityFinder.getUser(userId);
		ListenerProfile profile = listenerProfileRepository.findByUserIdForVisibilityRead(userId)
				.orElseGet(() -> listenerProfileProvisioner.ensureExistsForUpdate(userId));
		return toOwnerResponse(profile);
	}

	@Override
	@Transactional
	public ListenerProfileResponseDto getProfileByUserId(UUID userId) {
		userEntityFinder.getUser(userId);
		return toAdministrativeResponse(findByUserIdForVisibilityRead(userId));
	}

	@Override
	@Transactional
	public ListenerProfileOwnerResponseDto updateMyProfile(UUID userId, ListenerSaveRequestDto dto) {
		return toOwnerResponse(updateProfileContent(userId, dto));
	}

	@Override
	@Transactional
	public ListenerProfileResponseDto updateProfile(UUID userId, ListenerSaveRequestDto dto) {
		return toAdministrativeResponse(updateProfileContent(userId, dto));
	}

	@Override
	@Transactional
	public ListenerProfileOwnerResponseDto updateAvatar(UUID userId, ListenerAvatarUpdateRequestDto dto) {
		if (dto == null || !dto.isProfilePictureMediaIdProvided()
				|| dto.expectedVersion() == null || dto.expectedVersion() < 0) {
			throw new SoundConnectException(ErrorType.VALIDATION_ERROR);
		}

		ListenerProfile profile = findOrProvisionForOwnerCommand(userId);
		UUID mediaAssetId = dto.profilePictureMediaId();
		// A response-lost retry is successful without advancing @Version again.
		// Any different state with a stale precondition is an older user intent
		// and must never overwrite a more recent avatar/removal/visibility choice.
		if (Objects.equals(profile.getProfilePictureMediaId(), mediaAssetId)) {
			return toOwnerResponse(profile);
		}
		if (profile.getVersion() != dto.expectedVersion()) {
			log.warn("Stale listener avatar update. userId={} expectedVersion={} actualVersion={}",
					userId, dto.expectedVersion(), profile.getVersion());
			throw new SoundConnectException(ErrorType.LISTENER_PROFILE_VERSION_CONFLICT);
		}
		if (mediaAssetId != null) {
			mediaAssetService.validateAssignableMedia(
					userId,
					mediaAssetId,
					MediaOwnerType.LISTENER_PROFILE,
					profile.getId(),
					MediaKind.IMAGE
			);
		}

		profile.setProfilePictureMediaId(mediaAssetId);
		ListenerProfile updated = listenerProfileRepository.saveAndFlush(profile);
		log.info("Listener avatar updated. userId={} profileId={} version={}",
				userId, profile.getId(), updated.getVersion());
		return toOwnerResponse(updated);
	}

	@Override
	@Transactional
	public ListenerProfileOwnerResponseDto updateVisibility(
			UUID userId,
			ListenerVisibilityUpdateRequestDto dto
	) {
		if (dto == null || dto.visibilityMode() == null || dto.expectedVersion() == null
				|| dto.expectedVersion() < 0) {
			throw new SoundConnectException(ErrorType.VALIDATION_ERROR);
		}

		ListenerProfile profile = findOrProvisionForOwnerCommand(userId);
		ListenerVisibilityMode currentMode = effectiveMode(profile);

		// A completed same-state command is an exact retry and remains successful
		// even when its pre-transition version is now stale. An incomplete profile,
		// however, has no successful selection to retry yet and must still satisfy
		// the compare-and-set precondition before we persist completion.
		if (currentMode == dto.visibilityMode() && profile.isVisibilityChoiceCompleted()) {
			if (currentMode == ListenerVisibilityMode.GHOST) {
				int removedFollowerCount = followGraphMutationService.removeAllIncomingFollowers(userId);
				if (removedFollowerCount > 0) {
					log.warn("Reconciled incoming followers for ghost listener. userId={} removedFollowerCount={}",
							userId, removedFollowerCount);
				}
			}
			return toOwnerResponse(profile);
		}
		if (profile.getVersion() != dto.expectedVersion()) {
			log.warn("Stale listener visibility update. userId={} expectedVersion={} actualVersion={}",
					userId, dto.expectedVersion(), profile.getVersion());
			throw new SoundConnectException(ErrorType.LISTENER_PROFILE_VERSION_CONFLICT);
		}

		int removedFollowerCount = 0;
		if (dto.visibilityMode() == ListenerVisibilityMode.GHOST) {
			removedFollowerCount = followGraphMutationService.removeAllIncomingFollowers(userId);
		}

		boolean modeChanged = currentMode != dto.visibilityMode();
		if (modeChanged) {
			profile.setVisibilityMode(dto.visibilityMode());
			profile.setVisibilityChangedAt(visibilityTimeProvider.now());
		}
		profile.setVisibilityChoiceCompleted(true);
		ListenerProfile updated = listenerProfileRepository.saveAndFlush(profile);
		log.info(
				"Listener visibility selection persisted. userId={} profileId={} from={} to={} "
						+ "modeChanged={} version={} removedFollowerCount={}",
				userId,
				profile.getId(),
				currentMode,
				dto.visibilityMode(),
				modeChanged,
				updated.getVersion(),
				removedFollowerCount
		);
		return toOwnerResponse(updated);
	}

	private ListenerProfile updateProfileContent(UUID userId, ListenerSaveRequestDto dto) {
		validateProfileContentCommand(dto);

		ListenerProfile profile = findByUserIdForUpdate(userId);
		if (profile.isPubliclyRestricted()) {
			log.warn("Listener profile content update rejected while public content is restricted. userId={}", userId);
			throw new SoundConnectException(ErrorType.LISTENER_PROFILE_CONTENT_LOCKED);
		}

		// Avatar writes require the expected-version precondition enforced by the
		// dedicated avatar command. Keep accepting a legacy client echo of the
		// already-current id so bio edits remain backwards compatible, but never
		// let this content endpoint become a CAS bypass for a different avatar.
		if (dto.profilePictureMediaId() != null
				&& !Objects.equals(dto.profilePictureMediaId(), profile.getProfilePictureMediaId())) {
			log.warn("Listener content update attempted an avatar change without a version precondition. userId={}",
					userId);
			throw new SoundConnectException(ErrorType.LISTENER_PROFILE_VERSION_CONFLICT);
		}

		if (dto.description() != null) profile.setDescription(dto.description());

		ListenerProfile updated = listenerProfileRepository.saveAndFlush(profile);
		log.info("Listener profile content updated. userId={} profileId={} version={}",
				userId, profile.getId(), updated.getVersion());
		return updated;
	}

	private void validateProfileContentCommand(ListenerSaveRequestDto dto) {
		if (dto == null || (dto.description() != null
				&& dto.description().length() > ListenerSaveRequestDto.DESCRIPTION_MAX_LENGTH)) {
			throw new SoundConnectException(ErrorType.VALIDATION_ERROR);
		}
	}

	private ListenerProfile findByUserIdForUpdate(UUID userId) {
		userEntityFinder.getUser(userId);
		return listenerProfileRepository.findByUserIdForUpdate(userId)
		                                .orElseThrow(() -> profileNotFoundByUserId(userId));
	}

	private ListenerProfile findOrProvisionForOwnerCommand(UUID userId) {
		userEntityFinder.getUser(userId);
		return listenerProfileRepository.findByUserIdForUpdate(userId)
				.orElseGet(() -> listenerProfileProvisioner.ensureExistsForUpdate(userId));
	}

	private ListenerProfile findByUserIdForVisibilityRead(UUID userId) {
		return listenerProfileRepository.findByUserIdForVisibilityRead(userId)
		                                .orElseThrow(() -> profileNotFoundByUserId(userId));
	}

	private ListenerProfileOwnerResponseDto toOwnerResponse(ListenerProfile profile) {
		boolean restricted = profile.isPubliclyRestricted();
		return new ListenerProfileOwnerResponseDto(
				profile.getId(),
				profile.getUser().getId(),
				profile.getUser().getUsername(),
				effectiveMode(profile),
				profile.isVisibilityChoiceCompleted(),
				profile.getVersion(),
				toApiInstant(profile.getVisibilityChangedAt()),
				restricted ? null : profile.getDescription(),
				profile.getProfilePictureMediaId(),
				resolveProfilePictureUrl(profile.getProfilePictureMediaId()),
				restricted ? null : followService.countFollowers(profile.getUser()),
				restricted ? null : followService.countFollowing(profile.getUser()),
				!restricted,
				!restricted,
				true,
				!restricted
		);
	}

	private ListenerProfilePublicResponseDto toPublicResponse(ListenerProfile profile) {
		boolean ghost = effectiveMode(profile) == ListenerVisibilityMode.GHOST;
		return new ListenerProfilePublicResponseDto(
				profile.getId(),
				profile.getUser().getId(),
				profile.getUser().getUsername(),
				effectiveMode(profile),
				ghost ? null : profile.getDescription(),
				profile.getProfilePictureMediaId(),
				resolveProfilePictureUrl(profile.getProfilePictureMediaId()),
				ghost ? null : followService.countFollowers(profile.getUser()),
				ghost ? null : followService.countFollowing(profile.getUser()),
				ghost,
				!ghost,
				true
		);
	}

	private ListenerProfileResponseDto toAdministrativeResponse(ListenerProfile profile) {
		ListenerProfileResponseDto base = listenerProfileMapper.toDto(profile);
		return new ListenerProfileResponseDto(
				base.id(),
				base.userId(),
				base.username(),
				base.bio(),
				base.profilePictureMediaId(),
				resolveProfilePictureUrl(profile.getProfilePictureMediaId()),
				followService.countFollowers(profile.getUser()),
				followService.countFollowing(profile.getUser()),
				effectiveMode(profile),
				profile.getVersion(),
				toApiInstant(profile.getVisibilityChangedAt())
		);
	}

	private ListenerProfileSearchItemDto toSearchResponse(ListenerProfile profile) {
		boolean ghost = effectiveMode(profile) == ListenerVisibilityMode.GHOST;
		return new ListenerProfileSearchItemDto(
				profile.getId(),
				profile.getUser().getId(),
				profile.getUser().getUsername(),
				ghost ? null : profile.getDescription(),
				resolveProfilePictureUrl(profile.getProfilePictureMediaId()),
				effectiveMode(profile)
		);
	}

	private ListenerVisibilityMode effectiveMode(ListenerProfile profile) {
		return profile.getVisibilityMode() == null
				? ListenerVisibilityMode.STANDARD
				: profile.getVisibilityMode();
	}

	private Instant toApiInstant(LocalDateTime utcDateTime) {
		return utcDateTime == null ? null : utcDateTime.toInstant(ZoneOffset.UTC);
	}

	private SoundConnectException profileNotFoundById(UUID profileId) {
		log.warn("Listener profile not found. profileId={}", profileId);
		return new SoundConnectException(ErrorType.PROFILE_NOT_FOUND);
	}

	private SoundConnectException profileNotFoundByUserId(UUID userId) {
		log.warn("Listener profile not found. userId={}", userId);
		return new SoundConnectException(ErrorType.PROFILE_NOT_FOUND);
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

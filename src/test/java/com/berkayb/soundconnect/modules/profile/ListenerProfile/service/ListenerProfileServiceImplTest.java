package com.berkayb.soundconnect.modules.profile.ListenerProfile.service;

import com.berkayb.soundconnect.modules.follow.service.FollowGraphMutationService;
import com.berkayb.soundconnect.modules.follow.service.FollowService;
import com.berkayb.soundconnect.modules.media.enums.MediaKind;
import com.berkayb.soundconnect.modules.media.enums.MediaOwnerType;
import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.dto.request.ListenerAvatarUpdateRequestDto;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.dto.request.ListenerSaveRequestDto;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.dto.request.ListenerVisibilityUpdateRequestDto;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.dto.response.ListenerProfileResponseDto;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@Tag("service")
class ListenerProfileServiceImplTest {

	@Mock ListenerProfileRepository repo;
	@Mock UserEntityFinder userFinder;
	@Mock ListenerProfileMapper mapper;
	@Mock MediaAssetService mediaAssetService;
	@Mock FollowService followService;
	@Mock FollowGraphMutationService followGraphMutationService;
	@Mock ListenerVisibilityTimeProvider visibilityTimeProvider;
	@Mock PersonalProfileTypePolicy personalProfileTypePolicy;
	@Mock ListenerProfileProvisioner listenerProfileProvisioner;

	@InjectMocks ListenerProfileServiceImpl service;

	private UUID userId;
	private User user;

	@BeforeEach
	void init() {
		userId = UUID.randomUUID();
		user = User.builder().id(userId).username("listener").password("p").build();
	}

	@Test
	void createProfileCreatesStandardProfileAndReturnsOwnerProjection() {
		UUID ppId = UUID.randomUUID();
		ListenerSaveRequestDto dto = new ListenerSaveRequestDto("hello", ppId);
		when(personalProfileTypePolicy.lockAndAssertCanAcquire(userId, RoleEnum.ROLE_LISTENER))
				.thenReturn(user);
		when(repo.findByUserId(userId)).thenReturn(Optional.empty());
		when(repo.saveAndFlush(any(ListenerProfile.class))).thenAnswer(invocation -> {
			ListenerProfile profile = invocation.getArgument(0);
			profile.setId(UUID.randomUUID());
			return profile;
		});

		var result = service.createProfile(userId, dto);

		assertThat(result.visibilityMode()).isEqualTo(ListenerVisibilityMode.STANDARD);
		assertThat(result.visibilityChoiceCompleted()).isFalse();
		assertThat(result.bio()).isNull();
		assertThat(result.profileContentVisible()).isFalse();
		assertThat(result.profileContentEditable()).isFalse();
		assertThat(result.avatarEditable()).isTrue();
		assertThat(result.canReceiveFollowers()).isFalse();
		verify(mediaAssetService).validateAssignableMedia(
				userId, ppId, MediaOwnerType.USER, userId, MediaKind.IMAGE);

		ArgumentCaptor<ListenerProfile> profileCaptor = ArgumentCaptor.forClass(ListenerProfile.class);
		verify(repo).saveAndFlush(profileCaptor.capture());
		assertThat(profileCaptor.getValue().getVisibilityMode()).isEqualTo(ListenerVisibilityMode.STANDARD);
		assertThat(profileCaptor.getValue().isVisibilityChoiceCompleted()).isFalse();
		assertThat(profileCaptor.getValue().getDescription()).isEqualTo("hello");
	}

	@Test
	void createProfileRejectsDuplicate() {
		when(personalProfileTypePolicy.lockAndAssertCanAcquire(userId, RoleEnum.ROLE_LISTENER))
				.thenReturn(user);
		when(repo.findByUserId(userId)).thenReturn(Optional.of(profile(ListenerVisibilityMode.STANDARD, 0)));

		assertThatThrownBy(() -> service.createProfile(userId, new ListenerSaveRequestDto("desc", null)))
				.isInstanceOfSatisfying(SoundConnectException.class, exception ->
						assertThat(exception.getErrorType()).isEqualTo(ErrorType.PROFILE_ALREADY_EXISTS));
	}

	@Test
	void createProfileRejectsOversizedDescriptionBeforeTakingLocksOrValidatingMedia() {
		assertThatThrownBy(() -> service.createProfile(
				userId,
				new ListenerSaveRequestDto(
						"x".repeat(ListenerSaveRequestDto.DESCRIPTION_MAX_LENGTH + 1),
						UUID.randomUUID())))
				.isInstanceOfSatisfying(SoundConnectException.class, exception ->
						assertThat(exception.getErrorType()).isEqualTo(ErrorType.VALIDATION_ERROR));

		verifyNoInteractions(personalProfileTypePolicy, userFinder, repo, mediaAssetService);
	}

	@Test
	void ownerGhostProjectionOmitsShowcaseAndSocialGraphButKeepsAvatar() {
		UUID avatarId = UUID.randomUUID();
		ListenerProfile profile = profile(ListenerVisibilityMode.GHOST, 3);
		profile.setDescription("must-not-leak");
		profile.setProfilePictureMediaId(avatarId);
		when(userFinder.getUser(userId)).thenReturn(user);
		when(repo.findByUserIdForVisibilityRead(userId)).thenReturn(Optional.of(profile));
		when(mediaAssetService.getDisplayUrl(avatarId)).thenReturn("https://cdn.test/avatar.jpg");

		var result = service.getMyProfile(userId);

		assertThat(result.visibilityChoiceCompleted()).isTrue();
		assertThat(result.bio()).isNull();
		assertThat(result.followerCount()).isNull();
		assertThat(result.followingCount()).isNull();
		assertThat(result.profilePictureMediaId()).isEqualTo(avatarId);
		assertThat(result.profilePictureUrl()).isEqualTo("https://cdn.test/avatar.jpg");
		assertThat(result.profileContentVisible()).isFalse();
		assertThat(result.profileContentEditable()).isFalse();
		assertThat(result.avatarEditable()).isTrue();
		assertThat(result.canReceiveFollowers()).isFalse();
		verifyNoInteractions(followService);
	}

	@Test
	void publicGhostProjectionReturnsRestricted200ShapeWithoutHiddenFields() {
		ListenerProfile profile = profile(ListenerVisibilityMode.GHOST, 2);
		profile.setDescription("private bio");
		when(repo.findByIdForVisibilityRead(profile.getId())).thenReturn(Optional.of(profile));

		var result = service.getProfileByProfileId(profile.getId());

		assertThat(result.visibilityMode()).isEqualTo(ListenerVisibilityMode.GHOST);
		assertThat(result.restricted()).isTrue();
		assertThat(result.bio()).isNull();
		assertThat(result.followerCount()).isNull();
		assertThat(result.followingCount()).isNull();
		assertThat(result.canFollow()).isFalse();
		assertThat(result.canMessage()).isTrue();
		verifyNoInteractions(followService);
	}

	@Test
	void firstExplicitStandardChoicePersistsCompletionWithoutChangingTheModeTimestamp() {
		ListenerProfile profile = profile(ListenerVisibilityMode.STANDARD, 0);
		profile.setVisibilityChoiceCompleted(false);
		when(userFinder.getUser(userId)).thenReturn(user);
		when(repo.findByUserIdForUpdate(userId)).thenReturn(Optional.of(profile));
		when(repo.saveAndFlush(profile)).thenAnswer(invocation -> {
			profile.setVersion(1);
			return profile;
		});

		var result = service.updateVisibility(
				userId, new ListenerVisibilityUpdateRequestDto(ListenerVisibilityMode.STANDARD, 0L));

		assertThat(result.visibilityMode()).isEqualTo(ListenerVisibilityMode.STANDARD);
		assertThat(result.visibilityChoiceCompleted()).isTrue();
		assertThat(result.version()).isEqualTo(1);
		assertThat(result.visibilityChangedAt()).isNull();
		verify(repo).saveAndFlush(profile);
		verifyNoInteractions(followGraphMutationService, visibilityTimeProvider);
	}

	@Test
	void visibilityChoiceAtomicallyProvisionsAMissingListenerProfile() {
		ListenerProfile repaired = profile(ListenerVisibilityMode.STANDARD, 0);
		repaired.setVisibilityChoiceCompleted(false);
		when(repo.findByUserIdForUpdate(userId)).thenReturn(Optional.empty());
		when(listenerProfileProvisioner.ensureExistsForUpdate(userId)).thenReturn(repaired);
		when(repo.saveAndFlush(repaired)).thenAnswer(invocation -> {
			repaired.setVersion(1);
			return repaired;
		});

		var result = service.updateVisibility(
				userId, new ListenerVisibilityUpdateRequestDto(ListenerVisibilityMode.STANDARD, 0L));

		assertThat(result.visibilityChoiceCompleted()).isTrue();
		assertThat(result.visibilityMode()).isEqualTo(ListenerVisibilityMode.STANDARD);
		assertThat(result.version()).isEqualTo(1);
		verify(listenerProfileProvisioner).ensureExistsForUpdate(userId);
		verify(repo).saveAndFlush(repaired);
	}

	@Test
	void completedStandardChoiceRetryIsIdempotentWithTheOriginalVersion() {
		ListenerProfile profile = profile(ListenerVisibilityMode.STANDARD, 1);
		when(userFinder.getUser(userId)).thenReturn(user);
		when(repo.findByUserIdForUpdate(userId)).thenReturn(Optional.of(profile));

		var result = service.updateVisibility(
				userId, new ListenerVisibilityUpdateRequestDto(ListenerVisibilityMode.STANDARD, 0L));

		assertThat(result.visibilityChoiceCompleted()).isTrue();
		assertThat(result.version()).isEqualTo(1);
		verify(repo, never()).saveAndFlush(any());
		verifyNoInteractions(followGraphMutationService, visibilityTimeProvider);
	}

	@Test
	void incompleteSameModeChoiceStillRejectsAStaleVersion() {
		ListenerProfile profile = profile(ListenerVisibilityMode.STANDARD, 2);
		profile.setVisibilityChoiceCompleted(false);
		when(userFinder.getUser(userId)).thenReturn(user);
		when(repo.findByUserIdForUpdate(userId)).thenReturn(Optional.of(profile));

		assertThatThrownBy(() -> service.updateVisibility(
				userId, new ListenerVisibilityUpdateRequestDto(ListenerVisibilityMode.STANDARD, 1L)))
				.isInstanceOfSatisfying(SoundConnectException.class, exception ->
						assertThat(exception.getErrorType())
								.isEqualTo(ErrorType.LISTENER_PROFILE_VERSION_CONFLICT));

		assertThat(profile.isVisibilityChoiceCompleted()).isFalse();
		verify(repo, never()).saveAndFlush(any());
		verifyNoInteractions(followGraphMutationService, visibilityTimeProvider);
	}

	@Test
	void publicStandardProjectionKeepsExistingFields() {
		ListenerProfile profile = profile(ListenerVisibilityMode.STANDARD, 2);
		profile.setDescription("public bio");
		when(repo.findByIdForVisibilityRead(profile.getId())).thenReturn(Optional.of(profile));
		when(followService.countFollowers(user)).thenReturn(12L);
		when(followService.countFollowing(user)).thenReturn(8L);

		var result = service.getProfileByProfileId(profile.getId());

		assertThat(result.restricted()).isFalse();
		assertThat(result.bio()).isEqualTo("public bio");
		assertThat(result.followerCount()).isEqualTo(12L);
		assertThat(result.followingCount()).isEqualTo(8L);
		assertThat(result.canFollow()).isTrue();
	}

	@Test
	void pendingChoiceProfileHasNoPublicByIdProjection() {
		ListenerProfile pending = profile(ListenerVisibilityMode.STANDARD, 0);
		pending.setVisibilityChoiceCompleted(false);
		when(repo.findByIdForVisibilityRead(pending.getId())).thenReturn(Optional.of(pending));

		assertThatThrownBy(() -> service.getProfileByProfileId(pending.getId()))
				.isInstanceOfSatisfying(SoundConnectException.class, exception ->
						assertThat(exception.getErrorType()).isEqualTo(ErrorType.PROFILE_NOT_FOUND));

		verifyNoInteractions(followService, mediaAssetService);
	}

	@Test
	void profileContentUpdateIsRejectedAtomicallyInGhostMode() {
		ListenerProfile profile = profile(ListenerVisibilityMode.GHOST, 2);
		when(userFinder.getUser(userId)).thenReturn(user);
		when(repo.findByUserIdForUpdate(userId)).thenReturn(Optional.of(profile));

		assertThatThrownBy(() -> service.updateMyProfile(
				userId, new ListenerSaveRequestDto("new bio", UUID.randomUUID())))
				.isInstanceOfSatisfying(SoundConnectException.class, exception ->
						assertThat(exception.getErrorType()).isEqualTo(ErrorType.LISTENER_PROFILE_CONTENT_LOCKED));

		assertThat(profile.getDescription()).isEqualTo("bio");
		verify(repo, never()).saveAndFlush(any());
		verifyNoInteractions(mediaAssetService);
	}

	@Test
	void profileContentUpdateIsRejectedUntilVisibilityChoiceCompletes() {
		ListenerProfile pending = profile(ListenerVisibilityMode.STANDARD, 0);
		pending.setVisibilityChoiceCompleted(false);
		when(userFinder.getUser(userId)).thenReturn(user);
		when(repo.findByUserIdForUpdate(userId)).thenReturn(Optional.of(pending));

		assertThatThrownBy(() -> service.updateMyProfile(
				userId, new ListenerSaveRequestDto("premature bio", null)))
				.isInstanceOfSatisfying(SoundConnectException.class, exception ->
						assertThat(exception.getErrorType())
								.isEqualTo(ErrorType.LISTENER_PROFILE_CONTENT_LOCKED));

		assertThat(pending.getDescription()).isEqualTo("bio");
		verify(repo, never()).saveAndFlush(any());
	}

	@Test
	void profileContentUpdateRejectsOversizedDescriptionBeforeLoadingTheProfile() {
		assertThatThrownBy(() -> service.updateMyProfile(
				userId,
				new ListenerSaveRequestDto(
						"x".repeat(ListenerSaveRequestDto.DESCRIPTION_MAX_LENGTH + 1),
						null)))
				.isInstanceOfSatisfying(SoundConnectException.class, exception ->
						assertThat(exception.getErrorType()).isEqualTo(ErrorType.VALIDATION_ERROR));

		verifyNoInteractions(userFinder, repo, mediaAssetService, followService);
	}

	@Test
	void standardProfileContentUpdateAcceptsLegacyCurrentAvatarEchoWithoutTreatingItAsAnEdit() {
		UUID currentAvatarId = UUID.randomUUID();
		ListenerProfile profile = profile(ListenerVisibilityMode.STANDARD, 2);
		profile.setProfilePictureMediaId(currentAvatarId);
		when(userFinder.getUser(userId)).thenReturn(user);
		when(repo.findByUserIdForUpdate(userId)).thenReturn(Optional.of(profile));
		when(repo.saveAndFlush(profile)).thenReturn(profile);

		var result = service.updateMyProfile(
				userId, new ListenerSaveRequestDto("new bio", currentAvatarId));

		assertThat(profile.getDescription()).isEqualTo("new bio");
		assertThat(profile.getProfilePictureMediaId()).isEqualTo(currentAvatarId);
		assertThat(result.bio()).isEqualTo("new bio");
		assertThat(result.profilePictureMediaId()).isEqualTo(currentAvatarId);
		verify(mediaAssetService, never()).validateAssignableMedia(any(), any(), any(), any(), any());
	}

	@Test
	void standardProfileContentUpdateRejectsDifferentAvatarWithoutMutatingBio() {
		UUID currentAvatarId = UUID.randomUUID();
		UUID unversionedAvatarId = UUID.randomUUID();
		ListenerProfile profile = profile(ListenerVisibilityMode.STANDARD, 2);
		profile.setProfilePictureMediaId(currentAvatarId);
		when(userFinder.getUser(userId)).thenReturn(user);
		when(repo.findByUserIdForUpdate(userId)).thenReturn(Optional.of(profile));

		assertThatThrownBy(() -> service.updateMyProfile(
				userId, new ListenerSaveRequestDto("new bio", unversionedAvatarId)))
				.isInstanceOfSatisfying(SoundConnectException.class, exception ->
						assertThat(exception.getErrorType())
								.isEqualTo(ErrorType.LISTENER_PROFILE_VERSION_CONFLICT));

		assertThat(profile.getDescription()).isEqualTo("bio");
		assertThat(profile.getProfilePictureMediaId()).isEqualTo(currentAvatarId);
		verify(repo, never()).saveAndFlush(any());
		verifyNoInteractions(mediaAssetService, followService);
	}

	@Test
	void administrativeContentUpdateCannotBypassAvatarCompareAndSet() {
		UUID currentAvatarId = UUID.randomUUID();
		ListenerProfile profile = profile(ListenerVisibilityMode.STANDARD, 2);
		profile.setProfilePictureMediaId(currentAvatarId);
		when(userFinder.getUser(userId)).thenReturn(user);
		when(repo.findByUserIdForUpdate(userId)).thenReturn(Optional.of(profile));

		assertThatThrownBy(() -> service.updateProfile(
				userId, new ListenerSaveRequestDto("admin bio", UUID.randomUUID())))
				.isInstanceOfSatisfying(SoundConnectException.class, exception ->
						assertThat(exception.getErrorType())
								.isEqualTo(ErrorType.LISTENER_PROFILE_VERSION_CONFLICT));

		assertThat(profile.getDescription()).isEqualTo("bio");
		assertThat(profile.getProfilePictureMediaId()).isEqualTo(currentAvatarId);
		verify(repo, never()).saveAndFlush(any());
		verifyNoInteractions(mediaAssetService, followService);
	}

	@Test
	void dedicatedAvatarUpdateRemainsAvailableInGhostMode() {
		UUID newAvatarId = UUID.randomUUID();
		ListenerProfile profile = profile(ListenerVisibilityMode.GHOST, 4);
		when(userFinder.getUser(userId)).thenReturn(user);
		when(repo.findByUserIdForUpdate(userId)).thenReturn(Optional.of(profile));
		when(repo.saveAndFlush(profile)).thenReturn(profile);

		var result = service.updateAvatar(userId, new ListenerAvatarUpdateRequestDto(newAvatarId, 4L));

		assertThat(profile.getProfilePictureMediaId()).isEqualTo(newAvatarId);
		assertThat(result.profilePictureMediaId()).isEqualTo(newAvatarId);
		assertThat(result.avatarEditable()).isTrue();
		verify(mediaAssetService).validateAssignableMedia(
				userId, newAvatarId, MediaOwnerType.LISTENER_PROFILE, profile.getId(), MediaKind.IMAGE);
		verifyNoInteractions(followService);
	}

	@Test
	void dedicatedAvatarUpdateAllowsRemoval() {
		ListenerProfile profile = profile(ListenerVisibilityMode.GHOST, 4);
		profile.setProfilePictureMediaId(UUID.randomUUID());
		when(userFinder.getUser(userId)).thenReturn(user);
		when(repo.findByUserIdForUpdate(userId)).thenReturn(Optional.of(profile));
		when(repo.saveAndFlush(profile)).thenReturn(profile);

		service.updateAvatar(userId, new ListenerAvatarUpdateRequestDto(null, 4L));

		assertThat(profile.getProfilePictureMediaId()).isNull();
		verifyNoInteractions(mediaAssetService, followService);
	}

	@Test
	void dedicatedAvatarUpdateRejectsAStaleDifferentChoice() {
		UUID currentAvatarId = UUID.randomUUID();
		UUID staleAvatarId = UUID.randomUUID();
		ListenerProfile profile = profile(ListenerVisibilityMode.STANDARD, 5);
		profile.setProfilePictureMediaId(currentAvatarId);
		when(userFinder.getUser(userId)).thenReturn(user);
		when(repo.findByUserIdForUpdate(userId)).thenReturn(Optional.of(profile));

		assertThatThrownBy(() -> service.updateAvatar(
				userId, new ListenerAvatarUpdateRequestDto(staleAvatarId, 4L)))
				.isInstanceOfSatisfying(SoundConnectException.class, exception ->
						assertThat(exception.getErrorType()).isEqualTo(ErrorType.LISTENER_PROFILE_VERSION_CONFLICT));

		assertThat(profile.getProfilePictureMediaId()).isEqualTo(currentAvatarId);
		verifyNoInteractions(mediaAssetService, followService);
		verify(repo, never()).saveAndFlush(any());
	}

	@Test
	void dedicatedAvatarRetryIsIdempotentWhenDesiredAvatarAlreadyWon() {
		UUID desiredAvatarId = UUID.randomUUID();
		ListenerProfile profile = profile(ListenerVisibilityMode.GHOST, 5);
		profile.setProfilePictureMediaId(desiredAvatarId);
		when(userFinder.getUser(userId)).thenReturn(user);
		when(repo.findByUserIdForUpdate(userId)).thenReturn(Optional.of(profile));

		var result = service.updateAvatar(
				userId, new ListenerAvatarUpdateRequestDto(desiredAvatarId, 4L));

		assertThat(result.profilePictureMediaId()).isEqualTo(desiredAvatarId);
		assertThat(result.version()).isEqualTo(5L);
		verify(mediaAssetService, never()).validateAssignableMedia(any(), any(), any(), any(), any());
		verifyNoInteractions(followService);
		verify(repo, never()).saveAndFlush(any());
	}

	@Test
	void enablingGhostPurgesIncomingFollowersInsideTheLockedTransition() {
		LocalDateTime changedAt = LocalDateTime.of(2026, 9, 3, 1, 2, 3);
		ListenerProfile profile = profile(ListenerVisibilityMode.STANDARD, 7);
		when(userFinder.getUser(userId)).thenReturn(user);
		when(repo.findByUserIdForUpdate(userId)).thenReturn(Optional.of(profile));
		when(followGraphMutationService.removeAllIncomingFollowers(userId)).thenReturn(6);
		when(visibilityTimeProvider.now()).thenReturn(changedAt);
		when(repo.saveAndFlush(profile)).thenAnswer(invocation -> {
			profile.setVersion(8);
			return profile;
		});

		var result = service.updateVisibility(
				userId, new ListenerVisibilityUpdateRequestDto(ListenerVisibilityMode.GHOST, 7L));

		assertThat(result.visibilityMode()).isEqualTo(ListenerVisibilityMode.GHOST);
		assertThat(result.version()).isEqualTo(8);
		assertThat(result.visibilityChangedAt()).isEqualTo(changedAt.toInstant(ZoneOffset.UTC));
		assertThat(result.bio()).isNull();
		InOrder order = inOrder(repo, followGraphMutationService);
		order.verify(repo).findByUserIdForUpdate(userId);
		order.verify(followGraphMutationService).removeAllIncomingFollowers(userId);
		order.verify(repo).saveAndFlush(profile);
		verifyNoInteractions(followService);
	}

	@Test
	void disablingGhostRestoresContentWithoutRestoringFollowers() {
		ListenerProfile profile = profile(ListenerVisibilityMode.GHOST, 8);
		when(userFinder.getUser(userId)).thenReturn(user);
		when(repo.findByUserIdForUpdate(userId)).thenReturn(Optional.of(profile));
		when(visibilityTimeProvider.now()).thenReturn(LocalDateTime.of(2026, 9, 3, 2, 0));
		when(repo.saveAndFlush(profile)).thenReturn(profile);
		when(followService.countFollowers(user)).thenReturn(0L);
		when(followService.countFollowing(user)).thenReturn(9L);

		var result = service.updateVisibility(
				userId, new ListenerVisibilityUpdateRequestDto(ListenerVisibilityMode.STANDARD, 8L));

		assertThat(result.visibilityMode()).isEqualTo(ListenerVisibilityMode.STANDARD);
		assertThat(result.bio()).isEqualTo("bio");
		assertThat(result.followerCount()).isZero();
		assertThat(result.followingCount()).isEqualTo(9L);
		verifyNoInteractions(followGraphMutationService);
	}

	@Test
	void exactGhostVisibilityRetryIsIdempotentAndReconcilesIncomingFollowerDrift() {
		ListenerProfile profile = profile(ListenerVisibilityMode.GHOST, 9);
		when(userFinder.getUser(userId)).thenReturn(user);
		when(repo.findByUserIdForUpdate(userId)).thenReturn(Optional.of(profile));
		when(followGraphMutationService.removeAllIncomingFollowers(userId)).thenReturn(2);

		var result = service.updateVisibility(
				userId, new ListenerVisibilityUpdateRequestDto(ListenerVisibilityMode.GHOST, 1L));

		assertThat(result.visibilityMode()).isEqualTo(ListenerVisibilityMode.GHOST);
		assertThat(result.version()).isEqualTo(9);
		verify(repo, never()).saveAndFlush(any());
		verify(followGraphMutationService).removeAllIncomingFollowers(userId);
		verifyNoInteractions(visibilityTimeProvider, followService);
	}

	@Test
	void staleVisibilityCommandForDifferentStateIsRejected() {
		ListenerProfile profile = profile(ListenerVisibilityMode.STANDARD, 9);
		when(userFinder.getUser(userId)).thenReturn(user);
		when(repo.findByUserIdForUpdate(userId)).thenReturn(Optional.of(profile));

		assertThatThrownBy(() -> service.updateVisibility(
				userId, new ListenerVisibilityUpdateRequestDto(ListenerVisibilityMode.GHOST, 8L)))
				.isInstanceOfSatisfying(SoundConnectException.class, exception ->
						assertThat(exception.getErrorType()).isEqualTo(ErrorType.LISTENER_PROFILE_VERSION_CONFLICT));

		assertThat(profile.getVisibilityMode()).isEqualTo(ListenerVisibilityMode.STANDARD);
		verify(repo, never()).saveAndFlush(any());
		verifyNoInteractions(followGraphMutationService, visibilityTimeProvider, followService);
	}

	@Test
	void serviceRejectsMalformedVisibilityCommandsBeforeMutation() {
		assertThatThrownBy(() -> service.updateVisibility(
				userId, new ListenerVisibilityUpdateRequestDto(null, 0L)))
				.isInstanceOfSatisfying(SoundConnectException.class, exception ->
						assertThat(exception.getErrorType()).isEqualTo(ErrorType.VALIDATION_ERROR));
		assertThatThrownBy(() -> service.updateVisibility(
				userId, new ListenerVisibilityUpdateRequestDto(ListenerVisibilityMode.GHOST, -1L)))
				.isInstanceOfSatisfying(SoundConnectException.class, exception ->
						assertThat(exception.getErrorType()).isEqualTo(ErrorType.VALIDATION_ERROR));
		assertThatThrownBy(() -> service.updateVisibility(userId, null))
				.isInstanceOfSatisfying(SoundConnectException.class, exception ->
						assertThat(exception.getErrorType()).isEqualTo(ErrorType.VALIDATION_ERROR));

		verifyNoInteractions(userFinder, repo, followGraphMutationService, visibilityTimeProvider);
	}

	@Test
	void listenerSearchUsesSafeDiscoveryQueryAndRedactsGhostBio() {
		ListenerProfile ghost = profile(ListenerVisibilityMode.GHOST, 1);
		ghost.setDescription("hidden keyword");
		when(repo.searchForPublicDiscovery(
				"listener", "listener", ListenerVisibilityMode.GHOST, PageRequest.of(0, 10)))
				.thenReturn(List.of(ghost));

		var result = service.searchProfiles(" listener ");

		assertThat(result).singleElement().satisfies(item -> {
			assertThat(item.username()).isEqualTo("listener");
			assertThat(item.bio()).isNull();
			assertThat(item.visibilityMode()).isEqualTo(ListenerVisibilityMode.GHOST);
		});
	}

	@Test
	void listenerSearchRejectsAnUnboundedQueryBeforeRepositoryAccess() {
		assertThatThrownBy(() -> service.searchProfiles("x".repeat(101)))
				.isInstanceOfSatisfying(SoundConnectException.class, exception ->
						assertThat(exception.getErrorType()).isEqualTo(ErrorType.VALIDATION_ERROR));

		verifyNoInteractions(repo);
	}

	@Test
	void avatarCommandMustExplicitlyStateSetOrRemoveIntent() {
		assertThatThrownBy(() -> service.updateAvatar(userId, new ListenerAvatarUpdateRequestDto()))
				.isInstanceOfSatisfying(SoundConnectException.class, exception ->
						assertThat(exception.getErrorType()).isEqualTo(ErrorType.VALIDATION_ERROR));

		verifyNoInteractions(repo, userFinder, mediaAssetService, followService);
	}

	@Test
	void administrativeProjectionRetainsGhostContentForModeration() {
		ListenerProfile ghost = profile(ListenerVisibilityMode.GHOST, 5);
		ListenerProfileResponseDto mapped = new ListenerProfileResponseDto(
				ghost.getId(), userId, "listener", "bio", null, null, 0, 0,
				ListenerVisibilityMode.GHOST, 5, null);
		when(userFinder.getUser(userId)).thenReturn(user);
		when(repo.findByUserIdForVisibilityRead(userId)).thenReturn(Optional.of(ghost));
		when(mapper.toDto(ghost)).thenReturn(mapped);

		var result = service.getProfileByUserId(userId);

		assertThat(result.bio()).isEqualTo("bio");
		assertThat(result.visibilityMode()).isEqualTo(ListenerVisibilityMode.GHOST);
	}

	@Test
	void getMyProfileAtomicallyRepairsMissingProfileForTheChooser() {
		when(userFinder.getUser(userId)).thenReturn(user);
		when(repo.findByUserIdForVisibilityRead(userId)).thenReturn(Optional.empty());
		ListenerProfile repaired = profile(ListenerVisibilityMode.STANDARD, 0);
		repaired.setVisibilityChoiceCompleted(false);
		repaired.setDescription(null);
		when(listenerProfileProvisioner.ensureExistsForUpdate(userId)).thenReturn(repaired);

		var result = service.getMyProfile(userId);

		assertThat(result.visibilityChoiceCompleted()).isFalse();
		assertThat(result.visibilityMode()).isEqualTo(ListenerVisibilityMode.STANDARD);
		assertThat(result.version()).isZero();
		assertThat(result.profileContentEditable()).isFalse();
		verify(listenerProfileProvisioner).ensureExistsForUpdate(userId);
	}

	private ListenerProfile profile(ListenerVisibilityMode mode, long version) {
		return ListenerProfile.builder()
		                      .id(UUID.randomUUID())
		                      .user(user)
		                      .description("bio")
		                      .visibilityMode(mode)
		                      .visibilityChoiceCompleted(true)
		                      .version(version)
		                      .build();
	}
}

package com.berkayb.soundconnect.modules.profile.ListenerProfile.support;

import com.berkayb.soundconnect.modules.profile.ListenerProfile.entity.ListenerProfile;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.enums.ListenerVisibilityMode;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.repository.ListenerProfileRepository;
import com.berkayb.soundconnect.modules.user.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ListenerVisibilityPolicyTest {

	private final ListenerProfileRepository repository = mock(ListenerProfileRepository.class);
	private final ListenerVisibilityPolicy policy = new ListenerVisibilityPolicy(repository);

	@Test
	void ordinaryReadUsesExistenceProjection() {
		UUID userId = UUID.randomUUID();
		when(repository.existsByUserIdAndVisibilityMode(userId, ListenerVisibilityMode.GHOST)).thenReturn(true);

		assertThat(policy.isGhost(userId)).isTrue();
		verify(repository).existsByUserIdAndVisibilityMode(userId, ListenerVisibilityMode.GHOST);
	}

	@Test
	void followGuardUsesLockedUserLookupAndTreatsNonListenerAsStandard() {
		UUID userId = UUID.randomUUID();
		when(repository.findByUserIdForUpdate(userId)).thenReturn(Optional.empty());

		assertThat(policy.lockAndIsGhost(userId)).isFalse();
		verify(repository).findByUserIdForUpdate(userId);
	}

	@Test
	void profileContentGuardUsesLockedProfileLookup() {
		UUID profileId = UUID.randomUUID();
		ListenerProfile profile = ListenerProfile.builder()
		                                         .id(profileId)
		                                         .visibilityMode(ListenerVisibilityMode.GHOST)
		                                         .build();
		when(repository.findByIdForUpdate(profileId)).thenReturn(Optional.of(profile));

		assertThat(policy.lockAndIsGhostProfile(profileId)).isTrue();
		verify(repository).findByIdForUpdate(profileId);
	}

	@Test
	void publicReadGuardsUseSharedLockLookups() {
		UUID userId = UUID.randomUUID();
		UUID profileId = UUID.randomUUID();
		ListenerProfile profile = ListenerProfile.builder()
		                                         .id(profileId)
		                                         .visibilityMode(ListenerVisibilityMode.GHOST)
		                                         .build();
		when(repository.findByUserIdForVisibilityRead(userId)).thenReturn(Optional.of(profile));
		when(repository.findByIdForVisibilityRead(profileId)).thenReturn(Optional.of(profile));

		assertThat(policy.lockForReadAndIsGhost(userId)).isTrue();
		assertThat(policy.lockForReadAndIsGhostProfile(profileId)).isTrue();
		verify(repository).findByUserIdForVisibilityRead(userId);
		verify(repository).findByIdForVisibilityRead(profileId);
	}

	@Test
	void batchGhostLookupIsNullSafeDeduplicatedAndImmutable() {
		UUID ghostId = UUID.randomUUID();
		UUID standardId = UUID.randomUUID();
		User ghostUser = User.builder().id(ghostId).build();
		User standardUser = User.builder().id(standardId).build();
		ListenerProfile ghostProfile = ListenerProfile.builder()
				.user(ghostUser).visibilityMode(ListenerVisibilityMode.GHOST).build();
		ListenerProfile standardProfile = ListenerProfile.builder()
				.user(standardUser).visibilityMode(ListenerVisibilityMode.STANDARD).build();
		when(repository.findAllByUserIdInForVisibilityRead(Set.of(ghostId, standardId)))
				.thenReturn(java.util.List.of(ghostProfile, standardProfile));

		Set<UUID> result = policy.ghostUserIds(
				Arrays.asList(ghostId, ghostId, null, standardId));

		assertThat(result).containsExactly(ghostId);
		assertThatThrownBy(() -> result.add(UUID.randomUUID()))
				.isInstanceOf(UnsupportedOperationException.class);
		verify(repository).findAllByUserIdInForVisibilityRead(Set.of(ghostId, standardId));
	}

	@Test
	void publicRestrictionBatchSeparatesGhostFromPendingChoice() {
		UUID ghostId = UUID.randomUUID();
		UUID pendingId = UUID.randomUUID();
		UUID standardId = UUID.randomUUID();
		ListenerProfile ghost = ListenerProfile.builder()
				.user(User.builder().id(ghostId).build())
				.visibilityMode(ListenerVisibilityMode.GHOST)
				.visibilityChoiceCompleted(true)
				.build();
		ListenerProfile pending = ListenerProfile.builder()
				.user(User.builder().id(pendingId).build())
				.visibilityMode(ListenerVisibilityMode.STANDARD)
				.visibilityChoiceCompleted(false)
				.build();
		ListenerProfile standard = ListenerProfile.builder()
				.user(User.builder().id(standardId).build())
				.visibilityMode(ListenerVisibilityMode.STANDARD)
				.visibilityChoiceCompleted(true)
				.build();
		Set<UUID> ids = Set.of(ghostId, pendingId, standardId);
		when(repository.findAllByUserIdInForVisibilityRead(ids))
				.thenReturn(java.util.List.of(ghost, pending, standard));

		var result = policy.publicVisibilityRestrictions(ids);

		assertThat(result.ghostUserIds()).containsExactly(ghostId);
		assertThat(result.pendingChoiceUserIds()).containsExactly(pendingId);
	}

	@Test
	void lockedRestrictionGuardTreatsPendingStandardProfileAsRestricted() {
		UUID profileId = UUID.randomUUID();
		ListenerProfile pending = ListenerProfile.builder()
				.id(profileId)
				.visibilityMode(ListenerVisibilityMode.STANDARD)
				.visibilityChoiceCompleted(false)
				.build();
		when(repository.lockContentVisibility(profileId)).thenReturn(Optional.of(new ListenerProfileRepository.ContentVisibility() {
			public String getMode() { return pending.getVisibilityMode().name(); }
			public boolean getChoiceCompleted() { return pending.isVisibilityChoiceCompleted(); }
		}));

		assertThat(policy.lockAndIsPubliclyRestrictedProfile(profileId)).isTrue();
	}

	@Test
	void emptyBatchAvoidsRepositoryRoundTrip() {
		assertThat(policy.ghostUserIds(null)).isEmpty();
		assertThat(policy.ghostUserIds(Set.of())).isEmpty();
		verifyNoInteractions(repository);
	}

	@Test
	void lockGuardsRequireAnExistingTransaction() throws NoSuchMethodException {
		Transactional byUser = ListenerVisibilityPolicy.class
				.getMethod("lockAndIsGhost", UUID.class)
				.getAnnotation(Transactional.class);
		Transactional byProfile = ListenerVisibilityPolicy.class
				.getMethod("lockAndIsGhostProfile", UUID.class)
				.getAnnotation(Transactional.class);
		Transactional readByUser = ListenerVisibilityPolicy.class
				.getMethod("lockForReadAndIsGhost", UUID.class)
				.getAnnotation(Transactional.class);
		Transactional readByProfile = ListenerVisibilityPolicy.class
				.getMethod("lockForReadAndIsGhostProfile", UUID.class)
				.getAnnotation(Transactional.class);
		Transactional batchRead = ListenerVisibilityPolicy.class
				.getMethod("ghostUserIds", java.util.Collection.class)
				.getAnnotation(Transactional.class);
		Transactional restrictionBatchRead = ListenerVisibilityPolicy.class
				.getMethod("publicVisibilityRestrictions", java.util.Collection.class)
				.getAnnotation(Transactional.class);
		Transactional restrictionWrite = ListenerVisibilityPolicy.class
				.getMethod("lockAndIsPubliclyRestrictedProfile", UUID.class)
				.getAnnotation(Transactional.class);
		Transactional restrictionRead = ListenerVisibilityPolicy.class
				.getMethod("lockForReadAndIsPubliclyRestrictedProfile", UUID.class)
				.getAnnotation(Transactional.class);

		assertThat(byUser).isNotNull();
		assertThat(byUser.propagation()).isEqualTo(Propagation.MANDATORY);
		assertThat(byProfile).isNotNull();
		assertThat(byProfile.propagation()).isEqualTo(Propagation.MANDATORY);
		assertThat(readByUser).isNotNull();
		assertThat(readByUser.propagation()).isEqualTo(Propagation.MANDATORY);
		assertThat(readByProfile).isNotNull();
		assertThat(readByProfile.propagation()).isEqualTo(Propagation.MANDATORY);
		assertThat(batchRead).isNotNull();
		assertThat(batchRead.propagation()).isEqualTo(Propagation.MANDATORY);
		assertThat(restrictionBatchRead).isNotNull();
		assertThat(restrictionBatchRead.propagation()).isEqualTo(Propagation.MANDATORY);
		assertThat(restrictionWrite).isNotNull();
		assertThat(restrictionWrite.propagation()).isEqualTo(Propagation.MANDATORY);
		assertThat(restrictionRead).isNotNull();
		assertThat(restrictionRead.propagation()).isEqualTo(Propagation.MANDATORY);
	}
}

package com.berkayb.soundconnect.modules.profile.ListenerProfile.support;

import com.berkayb.soundconnect.modules.profile.ListenerProfile.entity.ListenerProfile;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.enums.ListenerVisibilityMode;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.repository.ListenerProfileRepository;
import com.berkayb.soundconnect.modules.profile.shared.type.PersonalProfileTypePolicy;
import com.berkayb.soundconnect.modules.role.enums.RoleEnum;
import com.berkayb.soundconnect.modules.user.entity.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ListenerProfileProvisionerTest {

	@Mock ListenerProfileRepository listenerProfileRepository;
	@Mock PersonalProfileTypePolicy personalProfileTypePolicy;
	@InjectMocks ListenerProfileProvisioner provisioner;

	@Test
	void existingProfileIsReturnedWithoutCreatingAnotherAggregate() {
		UUID userId = UUID.randomUUID();
		User lockedUser = User.builder().id(userId).build();
		ListenerProfile existing = ListenerProfile.builder()
				.id(UUID.randomUUID())
				.user(lockedUser)
				.visibilityMode(ListenerVisibilityMode.GHOST)
				.visibilityChoiceCompleted(true)
				.build();
		when(personalProfileTypePolicy.lockAndAssertCanAcquire(userId, RoleEnum.ROLE_LISTENER))
				.thenReturn(lockedUser);
		when(listenerProfileRepository.findByUserIdForUpdate(userId)).thenReturn(Optional.of(existing));

		assertThat(provisioner.ensureExistsForUpdate(userId)).isSameAs(existing);

		verify(listenerProfileRepository, never()).saveAndFlush(any());
	}

	@Test
	void missingProfileIsCreatedAsAnEmptyPendingStandardProfileUnderTheUserLock() {
		UUID userId = UUID.randomUUID();
		User lockedUser = User.builder().id(userId).build();
		when(personalProfileTypePolicy.lockAndAssertCanAcquire(userId, RoleEnum.ROLE_LISTENER))
				.thenReturn(lockedUser);
		when(listenerProfileRepository.findByUserIdForUpdate(userId)).thenReturn(Optional.empty());
		when(listenerProfileRepository.saveAndFlush(any(ListenerProfile.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));

		ListenerProfile result = provisioner.ensureExistsForUpdate(userId);

		ArgumentCaptor<ListenerProfile> captor = ArgumentCaptor.forClass(ListenerProfile.class);
		verify(listenerProfileRepository).saveAndFlush(captor.capture());
		assertThat(result).isSameAs(captor.getValue());
		assertThat(result.getUser()).isSameAs(lockedUser);
		assertThat(result.getVisibilityMode()).isEqualTo(ListenerVisibilityMode.STANDARD);
		assertThat(result.isVisibilityChoiceCompleted()).isFalse();
		assertThat(result.getDescription()).isNull();
		assertThat(result.getProfilePictureMediaId()).isNull();
	}
}

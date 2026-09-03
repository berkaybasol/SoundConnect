package com.berkayb.soundconnect.modules.profile.ListenerProfile.support;

import com.berkayb.soundconnect.modules.profile.ListenerProfile.repository.ListenerProfileRepository;
import com.berkayb.soundconnect.modules.role.entity.Role;
import com.berkayb.soundconnect.modules.role.enums.RoleEnum;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ListenerProfileChoiceStatusReaderTest {

	@Mock ListenerProfileRepository listenerProfileRepository;
	@InjectMocks ListenerProfileChoiceStatusReader reader;

	@Test
	void activeListenerRequiresChoiceWhenCompletedProfileDoesNotExist() {
		User listener = activeUserWithRole(RoleEnum.ROLE_LISTENER);
		when(listenerProfileRepository
				.existsByUserIdAndVisibilityChoiceCompletedTrue(listener.getId()))
				.thenReturn(false);

		assertThat(reader.requiresChoice(listener)).isTrue();
		verify(listenerProfileRepository)
				.existsByUserIdAndVisibilityChoiceCompletedTrue(listener.getId());
	}

	@Test
	void activeListenerDoesNotRequireChoiceAfterCompletion() {
		User listener = activeUserWithRole(RoleEnum.ROLE_LISTENER);
		when(listenerProfileRepository
				.existsByUserIdAndVisibilityChoiceCompletedTrue(listener.getId()))
				.thenReturn(true);

		assertThat(reader.requiresChoice(listener)).isFalse();
	}

	@Test
	void nonListenerAndInactiveAccountsNeverEnterTheListenerChooser() {
		User musician = activeUserWithRole(RoleEnum.ROLE_MUSICIAN);
		User inactiveListener = activeUserWithRole(RoleEnum.ROLE_LISTENER);
		inactiveListener.setStatus(UserStatus.INACTIVE);

		assertThat(reader.requiresChoice(musician)).isFalse();
		assertThat(reader.requiresChoice(inactiveListener)).isFalse();
		assertThat(reader.requiresChoice(null)).isFalse();
		verifyNoInteractions(listenerProfileRepository);
	}

	private User activeUserWithRole(RoleEnum role) {
		return User.builder()
				.id(UUID.randomUUID())
				.status(UserStatus.ACTIVE)
				.roles(Set.of(Role.builder().name(role.name()).build()))
				.build();
	}
}

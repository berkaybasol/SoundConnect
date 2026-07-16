package com.berkayb.soundconnect.auth.service;

import com.berkayb.soundconnect.auth.dto.request.GoogleCompleteProfileRequestDto;
import com.berkayb.soundconnect.auth.security.JwtTokenProvider;
import com.berkayb.soundconnect.modules.profile.shared.factory.ProfileFactory;
import com.berkayb.soundconnect.modules.role.entity.Role;
import com.berkayb.soundconnect.modules.role.enums.RoleEnum;
import com.berkayb.soundconnect.modules.role.repository.RoleRepository;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.AuthProvider;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashSet;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

@ExtendWith(MockitoExtension.class)
class GoogleCompleteProfileServiceTest {

	@Mock RoleRepository roleRepository;
	@Mock ProfileFactory profileFactory;
	@Mock UserRepository userRepository;
	@Mock JwtTokenProvider jwtTokenProvider;
	@InjectMocks GoogleCompleteProfileService service;

	@Test
	void assignsAllowedRoleToRolelessGoogleAccount() {
		UUID userId = UUID.randomUUID();
		User user = onboardingUser(userId);
		Role musician = Role.builder().name(RoleEnum.ROLE_MUSICIAN.name()).build();
		when(userRepository.findByIdForUpdate(userId)).thenReturn(Optional.of(user));
		when(roleRepository.findByName(RoleEnum.ROLE_MUSICIAN.name())).thenReturn(Optional.of(musician));
		when(jwtTokenProvider.generateToken(any())).thenReturn("renewed-token");

		var response = service.completeProfileWithRole(
				userId,
				new GoogleCompleteProfileRequestDto(RoleEnum.ROLE_MUSICIAN)
		);

		assertThat(user.getRoles()).containsExactly(musician);
		assertThat(response.token()).isEqualTo("renewed-token");
		assertThat(response.roles()).containsExactly(RoleEnum.ROLE_MUSICIAN.name());
		verify(userRepository).save(user);
		verify(profileFactory).createProfileIfNeeded(user, RoleEnum.ROLE_MUSICIAN);
	}

	@Test
	void neverAllowsOwnerRoleFromGoogleOnboarding() {
		UUID userId = UUID.randomUUID();
		when(userRepository.findByIdForUpdate(userId)).thenReturn(Optional.of(onboardingUser(userId)));

		SoundConnectException exception = catchThrowableOfType(
				() -> service.completeProfileWithRole(
						userId,
						new GoogleCompleteProfileRequestDto(RoleEnum.ROLE_OWNER)
				),
				SoundConnectException.class
		);

		assertThat(exception.getErrorType()).isEqualTo(ErrorType.FORBIDDEN_ACCESS);
		verify(roleRepository, never()).findByName(RoleEnum.ROLE_OWNER.name());
		verify(userRepository, never()).save(any());
	}

	@Test
	void genericRoleUserCannotBecomeARepeatablePlaceholder() {
		UUID userId = UUID.randomUUID();
		when(userRepository.findByIdForUpdate(userId)).thenReturn(Optional.of(onboardingUser(userId)));

		SoundConnectException exception = catchThrowableOfType(
				() -> service.completeProfileWithRole(
						userId,
						new GoogleCompleteProfileRequestDto(RoleEnum.ROLE_USER)
				),
				SoundConnectException.class
		);

		assertThat(exception.getErrorType()).isEqualTo(ErrorType.FORBIDDEN_ACCESS);
		verify(roleRepository, never()).findByName(RoleEnum.ROLE_USER.name());
		verify(userRepository, never()).save(any());
	}

	@Test
	void localAccountCannotUseGoogleCompletionEndpoint() {
		UUID userId = UUID.randomUUID();
		User user = onboardingUser(userId);
		user.setProvider(AuthProvider.LOCAL);
		when(userRepository.findByIdForUpdate(userId)).thenReturn(Optional.of(user));

		SoundConnectException exception = catchThrowableOfType(
				() -> service.completeProfileWithRole(
						userId,
						new GoogleCompleteProfileRequestDto(RoleEnum.ROLE_MUSICIAN)
				),
				SoundConnectException.class
		);

		assertThat(exception.getErrorType()).isEqualTo(ErrorType.FORBIDDEN_ACCESS);
		verify(userRepository, never()).save(user);
	}

	private User onboardingUser(UUID userId) {
		return User.builder()
				.id(userId)
				.username("google@example.com")
				.email("google@example.com")
				.password("random-bcrypt")
				.provider(AuthProvider.GOOGLE)
				.status(UserStatus.ACTIVE)
				.emailVerified(true)
				.roles(new HashSet<>())
				.build();
	}
}

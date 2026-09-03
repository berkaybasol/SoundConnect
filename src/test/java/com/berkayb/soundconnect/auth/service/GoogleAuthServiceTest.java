package com.berkayb.soundconnect.auth.service;

import com.berkayb.soundconnect.auth.dto.request.GoogleAuthRequestDto;
import com.berkayb.soundconnect.auth.model.VerifiedGoogleIdentity;
import com.berkayb.soundconnect.auth.ratelimit.AuthAccountRateLimitGuard;
import com.berkayb.soundconnect.auth.security.GoogleIdTokenValidator;
import com.berkayb.soundconnect.auth.security.JwtTokenProvider;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.support.ListenerProfileChoiceStatusReader;
import com.berkayb.soundconnect.modules.role.entity.Role;
import com.berkayb.soundconnect.modules.role.enums.RoleEnum;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.AuthProvider;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GoogleAuthServiceTest {

	@Mock UserRepository userRepository;
	@Mock JwtTokenProvider jwtTokenProvider;
	@Mock GoogleIdTokenValidator googleIdTokenValidator;
	@Mock PasswordEncoder passwordEncoder;
	@Mock AuthAccountRateLimitGuard accountRateLimitGuard;
	@Mock ListenerProfileChoiceStatusReader listenerProfileChoiceStatusReader;
	@InjectMocks GoogleAuthService googleAuthService;

	@Test
	void createsRolelessGoogleOnboardingAccountWithUnusableLocalPassword() {
		when(googleIdTokenValidator.verify("google-token"))
				.thenReturn(new VerifiedGoogleIdentity("google-subject-1", "New@Example.com", "New User"));
		when(userRepository.findByProviderAndProviderSubject(AuthProvider.GOOGLE, "google-subject-1"))
				.thenReturn(Optional.empty());
		when(userRepository.findByEmailForUpdate("new@example.com")).thenReturn(Optional.empty());
		when(userRepository.existsByUsername(anyString())).thenReturn(false);
		when(passwordEncoder.encode(anyString())).thenReturn("random-bcrypt");
		when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
			User user = invocation.getArgument(0);
			user.setId(UUID.randomUUID());
			return user;
		});
		when(jwtTokenProvider.generateToken(any())).thenReturn("soundconnect-token");

		var response = googleAuthService.loginWithGoogle(new GoogleAuthRequestDto("google-token"));

		ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
		verify(userRepository).save(captor.capture());
		User saved = captor.getValue();
		assertThat(saved.getProvider()).isEqualTo(AuthProvider.GOOGLE);
		assertThat(saved.getProviderSubject()).isEqualTo("google-subject-1");
		assertThat(saved.getUsername()).startsWith("g_").isNotEqualTo(saved.getEmail());
		assertThat(saved.getStatus()).isEqualTo(UserStatus.ACTIVE);
		assertThat(saved.getEmailVerified()).isTrue();
		assertThat(saved.getPassword()).isEqualTo("random-bcrypt");
		assertThat(saved.getRoles()).isEmpty();
		assertThat(response.getData().token()).isEqualTo("soundconnect-token");
		assertThat(response.getData().roles()).isEmpty();
		assertThat(response.getData().requiresListenerProfileChoice()).isFalse();
	}

	@Test
	void existingListenerGoogleLoginCarriesTheServerAuthoritativeChooserDecision() {
		Role listenerRole = Role.builder().name(RoleEnum.ROLE_LISTENER.name()).build();
		User existing = googleUser(Set.of(listenerRole));
		existing.setProviderSubject("listener-subject");
		when(googleIdTokenValidator.verify("google-token"))
				.thenReturn(new VerifiedGoogleIdentity(
						"listener-subject", existing.getEmail(), "Listener"));
		when(userRepository.findByProviderAndProviderSubject(
				AuthProvider.GOOGLE, "listener-subject"))
				.thenReturn(Optional.of(existing));
		when(jwtTokenProvider.generateToken(any())).thenReturn("token");
		when(listenerProfileChoiceStatusReader.requiresChoice(existing)).thenReturn(true);

		var response = googleAuthService.loginWithGoogle(new GoogleAuthRequestDto("google-token"));

		assertThat(response.getData().requiresListenerProfileChoice()).isTrue();
		verify(listenerProfileChoiceStatusReader).requiresChoice(existing);
	}

	@Test
	void clearsOnlyLegacyGoogleRoleUserPlaceholder() {
		Role legacyRole = Role.builder().name("ROLE_USER").build();
		User existing = googleUser(Set.of(legacyRole));
		when(googleIdTokenValidator.verify("google-token"))
				.thenReturn(new VerifiedGoogleIdentity("google-subject-2", existing.getEmail(), "Existing"));
		when(userRepository.findByProviderAndProviderSubject(AuthProvider.GOOGLE, "google-subject-2"))
				.thenReturn(Optional.empty());
		when(userRepository.findByEmailForUpdate(existing.getEmail())).thenReturn(Optional.of(existing));
		when(userRepository.save(existing)).thenReturn(existing);
		when(jwtTokenProvider.generateToken(any())).thenReturn("token");

		var response = googleAuthService.loginWithGoogle(new GoogleAuthRequestDto("google-token"));

		assertThat(existing.getRoles()).isEmpty();
		assertThat(existing.getProviderSubject()).isEqualTo("google-subject-2");
		assertThat(response.getData().roles()).isEmpty();
		verify(userRepository).save(existing);
	}

	@Test
	void rejectsLocalAccountWithSameEmail() {
		User local = googleUser(Set.of());
		local.setProvider(AuthProvider.LOCAL);
		when(googleIdTokenValidator.verify("google-token"))
				.thenReturn(new VerifiedGoogleIdentity("google-subject-3", local.getEmail(), "Local"));
		when(userRepository.findByProviderAndProviderSubject(AuthProvider.GOOGLE, "google-subject-3"))
				.thenReturn(Optional.empty());
		when(userRepository.findByEmailForUpdate(local.getEmail())).thenReturn(Optional.of(local));

		SoundConnectException exception = catchThrowableOfType(
				() -> googleAuthService.loginWithGoogle(new GoogleAuthRequestDto("google-token")),
				SoundConnectException.class
		);

		assertThat(exception.getErrorType()).isEqualTo(ErrorType.UNAUTHORIZED);
		verify(jwtTokenProvider, never()).generateToken(any());
	}

	@Test
	void rejectsEmailMatchWhenItIsAlreadyBoundToAnotherGoogleSubject() {
		User existing = googleUser(Set.of());
		existing.setProviderSubject("original-subject");
		when(googleIdTokenValidator.verify("google-token"))
				.thenReturn(new VerifiedGoogleIdentity("different-subject", existing.getEmail(), "Attacker"));
		when(userRepository.findByProviderAndProviderSubject(AuthProvider.GOOGLE, "different-subject"))
				.thenReturn(Optional.empty());
		when(userRepository.findByEmailForUpdate(existing.getEmail())).thenReturn(Optional.of(existing));

		SoundConnectException exception = catchThrowableOfType(
				() -> googleAuthService.loginWithGoogle(new GoogleAuthRequestDto("google-token")),
				SoundConnectException.class
		);

		assertThat(exception.getErrorType()).isEqualTo(ErrorType.UNAUTHORIZED);
		verify(jwtTokenProvider, never()).generateToken(any());
	}

	@Test
	void stableSubjectAuthenticatesExistingAccountEvenWhenProviderEmailChanges() {
		User existing = googleUser(Set.of());
		existing.setProviderSubject("stable-subject");
		when(googleIdTokenValidator.verify("google-token"))
				.thenReturn(new VerifiedGoogleIdentity("stable-subject", "new-address@example.com", "Existing"));
		when(userRepository.findByProviderAndProviderSubject(AuthProvider.GOOGLE, "stable-subject"))
				.thenReturn(Optional.of(existing));
		when(jwtTokenProvider.generateToken(any())).thenReturn("token");

		var response = googleAuthService.loginWithGoogle(new GoogleAuthRequestDto("google-token"));

		assertThat(response.getData().token()).isEqualTo("token");
		verify(userRepository, never()).findByEmailForUpdate("new-address@example.com");
	}

	private User googleUser(Set<Role> roles) {
		return User.builder()
				.id(UUID.randomUUID())
				.username("google@example.com")
				.email("google@example.com")
				.password("random-bcrypt")
				.provider(AuthProvider.GOOGLE)
				.status(UserStatus.ACTIVE)
				.emailVerified(true)
				.roles(roles)
				.build();
	}
}

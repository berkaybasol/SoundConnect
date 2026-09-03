package com.berkayb.soundconnect.auth.service;

import com.berkayb.soundconnect.auth.otp.dto.request.VerifyCodeRequestDto;
import com.berkayb.soundconnect.auth.otp.service.OtpMailService;
import com.berkayb.soundconnect.auth.otp.service.OtpService;
import com.berkayb.soundconnect.auth.ratelimit.AuthAccountRateLimitGuard;
import com.berkayb.soundconnect.auth.security.JwtTokenProvider;
import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.application.studioapplication.service.StudioApplicationService;
import com.berkayb.soundconnect.modules.application.venueapplication.service.VenueApplicationService;
import com.berkayb.soundconnect.modules.profile.shared.factory.ProfileFactory;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.support.ListenerProfileChoiceStatusReader;
import com.berkayb.soundconnect.modules.role.entity.Role;
import com.berkayb.soundconnect.modules.role.enums.RoleEnum;
import com.berkayb.soundconnect.modules.role.repository.RoleRepository;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceVerifyCodeTest {

	@Mock JwtTokenProvider jwtTokenProvider;
	@Mock UserRepository userRepository;
	@Mock PasswordEncoder passwordEncoder;
	@Mock RoleRepository roleRepository;
	@Mock ProfileFactory profileFactory;
	@Mock ListenerProfileChoiceStatusReader listenerProfileChoiceStatusReader;
	@Mock OtpService otpService;
	@Mock OtpMailService otpMailService;
	@Mock VenueApplicationService venueApplicationService;
	@Mock StudioApplicationService studioApplicationService;
	@Mock AuthAccountRateLimitGuard accountRateLimitGuard;
	@InjectMocks AuthService authService;

	private final VerifyCodeRequestDto request =
			new VerifyCodeRequestDto(" User@Example.com ", "123456");

	@Test
	void unknownAndWrongCodeUseTheSameGenericPublicError() {
		when(otpService.verifyOtp("user@example.com", "123456")).thenReturn(false);
		when(userRepository.findByEmailForUpdate("user@example.com")).thenReturn(Optional.empty());

		SoundConnectException exception = catchThrowableOfType(
				() -> authService.verifyCode(request), SoundConnectException.class
		);

		assertThat(exception.getErrorType()).isEqualTo(ErrorType.VALIDATION_ERROR);
		assertThat(exception.getDetails()).containsExactly("Dogrulama kodu gecersiz veya suresi dolmus.");
		verify(userRepository, never()).saveAndFlush(any());
		verifyNoInteractions(jwtTokenProvider);
	}

	@Test
	void alreadyVerifiedAccountDoesNotRevealItsState() {
		User verified = User.builder().emailVerified(true).status(UserStatus.ACTIVE).build();
		when(otpService.verifyOtp("user@example.com", "123456")).thenReturn(false);
		when(userRepository.findByEmailForUpdate("user@example.com")).thenReturn(Optional.of(verified));

		SoundConnectException exception = catchThrowableOfType(
				() -> authService.verifyCode(request), SoundConnectException.class
		);

		assertThat(exception.getErrorType()).isEqualTo(ErrorType.VALIDATION_ERROR);
		assertThat(exception.getDetails()).containsExactly("Dogrulama kodu gecersiz veya suresi dolmus.");
		verify(userRepository, never()).saveAndFlush(any());
		verifyNoInteractions(jwtTokenProvider);
	}

	@Test
	void validCodeStillActivatesEligibleNonListenerWithoutCreatingASession() {
		User user = activeCandidateWithRole(RoleEnum.ROLE_MUSICIAN);
		when(otpService.verifyOtp("user@example.com", "123456")).thenReturn(true);
		when(userRepository.findByEmailForUpdate("user@example.com")).thenReturn(Optional.of(user));

		var response = authService.verifyCode(request);

		assertThat(response.getCode()).isEqualTo(200);
		assertThat(response.getData()).isNull();
		assertThat(user.getEmailVerified()).isTrue();
		assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
		verify(userRepository).saveAndFlush(user);
		verifyNoInteractions(jwtTokenProvider);
	}

	@Test
	void validCodeReturnsSessionOnlyAfterActiveListenerWasFlushed() {
		User user = activeCandidateWithRole(RoleEnum.ROLE_LISTENER);
		when(otpService.verifyOtp("user@example.com", "123456")).thenReturn(true);
		when(userRepository.findByEmailForUpdate("user@example.com")).thenReturn(Optional.of(user));
		when(jwtTokenProvider.generateToken(any(UserDetailsImpl.class))).thenReturn("listener-token");
		when(listenerProfileChoiceStatusReader.requiresChoice(user)).thenReturn(true);

		var response = authService.verifyCode(request);

		assertThat(response.getCode()).isEqualTo(200);
		assertThat(response.getData()).isNotNull();
		assertThat(response.getData().token()).isEqualTo("listener-token");
		assertThat(response.getData().status()).isEqualTo(UserStatus.ACTIVE);
		assertThat(response.getData().userId()).isEqualTo(user.getId());
		assertThat(response.getData().username()).isEqualTo("listener");
		assertThat(response.getData().roles()).containsExactly(RoleEnum.ROLE_LISTENER.name());
		assertThat(response.getData().requiresListenerProfileChoice()).isTrue();
		var ordered = inOrder(userRepository, jwtTokenProvider);
		ordered.verify(userRepository).saveAndFlush(user);
		ordered.verify(jwtTokenProvider).generateToken(any(UserDetailsImpl.class));
	}

	@Test
	void validCodeVerifiesButNeverReopensARejectedStudioAccount() {
		User user = activeCandidateWithRole(RoleEnum.ROLE_LISTENER);
		user.setStatus(UserStatus.REJECTED_STUDIO_REQUEST);
		when(otpService.verifyOtp("user@example.com", "123456")).thenReturn(true);
		when(userRepository.findByEmailForUpdate("user@example.com"))
				.thenReturn(Optional.of(user));

		var response = authService.verifyCode(request);

		assertThat(user.getEmailVerified()).isTrue();
		assertThat(user.getStatus()).isEqualTo(UserStatus.REJECTED_STUDIO_REQUEST);
		assertThat(response.getData()).isNull();
		verify(userRepository).saveAndFlush(user);
		verifyNoInteractions(jwtTokenProvider);
	}

	@Test
	void pendingListenerRoleNeverReceivesSessionUntilAccountIsActive() {
		User user = activeCandidateWithRole(RoleEnum.ROLE_LISTENER);
		user.setStatus(UserStatus.PENDING_STUDIO_REQUEST);
		when(otpService.verifyOtp("user@example.com", "123456")).thenReturn(true);
		when(userRepository.findByEmailForUpdate("user@example.com"))
				.thenReturn(Optional.of(user));

		var response = authService.verifyCode(request);

		assertThat(user.getEmailVerified()).isTrue();
		assertThat(user.getStatus()).isEqualTo(UserStatus.PENDING_STUDIO_REQUEST);
		assertThat(response.getData()).isNull();
		verify(userRepository).saveAndFlush(user);
		verifyNoInteractions(jwtTokenProvider);
	}

	@Test
	void pendingVenueStatusNeverReceivesSessionEvenWithListenerRoleDrift() {
		User user = activeCandidateWithRole(RoleEnum.ROLE_LISTENER);
		user.setStatus(UserStatus.PENDING_VENUE_REQUEST);
		when(otpService.verifyOtp("user@example.com", "123456")).thenReturn(true);
		when(userRepository.findByEmailForUpdate("user@example.com"))
				.thenReturn(Optional.of(user));

		var response = authService.verifyCode(request);

		assertThat(user.getEmailVerified()).isTrue();
		assertThat(user.getStatus()).isEqualTo(UserStatus.PENDING_VENUE_REQUEST);
		assertThat(response.getData()).isNull();
		verify(userRepository).saveAndFlush(user);
		verifyNoInteractions(jwtTokenProvider);
	}

	private User activeCandidateWithRole(RoleEnum roleName) {
		Role role = Role.builder().name(roleName.name()).build();
		return User.builder()
				.id(UUID.randomUUID())
				.username("listener")
				.email("user@example.com")
				.emailVerified(false)
				.status(UserStatus.INACTIVE)
				.roles(Set.of(role))
				.build();
	}
}

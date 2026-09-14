package com.berkayb.soundconnect.auth.service;

import com.berkayb.soundconnect.auth.dto.request.LoginRequestDto;
import com.berkayb.soundconnect.auth.dto.request.UsernameAvailabilityRequestDto;
import com.berkayb.soundconnect.auth.otp.service.OtpMailService;
import com.berkayb.soundconnect.auth.otp.service.OtpService;
import com.berkayb.soundconnect.auth.otp.dto.request.VerifyCodeRequestDto;
import com.berkayb.soundconnect.auth.passwordreset.dto.request.ResetPasswordRequestDto;
import com.berkayb.soundconnect.auth.passwordreset.service.PasswordResetMailService;
import com.berkayb.soundconnect.auth.passwordreset.service.PasswordResetService;
import com.berkayb.soundconnect.auth.ratelimit.AuthAccountRateLimitGuard;
import com.berkayb.soundconnect.auth.security.JwtTokenProvider;
import com.berkayb.soundconnect.modules.application.venueapplication.service.VenueApplicationService;
import com.berkayb.soundconnect.modules.application.studioapplication.service.StudioApplicationService;
import com.berkayb.soundconnect.modules.profile.shared.factory.ProfileFactory;
import com.berkayb.soundconnect.modules.profile.shared.resolver.service.PublicProfileResolverService;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.support.ListenerProfileChoiceStatusReader;
import com.berkayb.soundconnect.modules.role.entity.Role;
import com.berkayb.soundconnect.modules.role.enums.RoleEnum;
import com.berkayb.soundconnect.modules.role.repository.RoleRepository;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.EmailVerificationRequiredException;
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
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceSecurityTest {

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

	@Test
	void interruptedRegistrationReturnsCanonicalOtpDestinationOnlyAfterPasswordVerification() {
		User user = unverifiedUser();
		when(userRepository.findByUsername("berna")).thenReturn(Optional.of(user));
		when(passwordEncoder.matches("secret", "encoded")).thenReturn(true);

		EmailVerificationRequiredException exception = catchThrowableOfType(
				() -> authService.login(new LoginRequestDto(" BeRNa ", "secret")),
				EmailVerificationRequiredException.class);

		assertThat(exception.getErrorType()).isEqualTo(ErrorType.EMAIL_VERIFICATION_REQUIRED);
		assertThat(exception.getEmail()).isEqualTo("berna@example.com");
		assertThat(user.getEmailVerified()).isFalse();
		assertThat(user.getStatus()).isEqualTo(UserStatus.INACTIVE);
		verifyNoInteractions(jwtTokenProvider, otpService, otpMailService);
		verify(userRepository, never()).save(any());
		verify(userRepository, never()).saveAndFlush(any());
	}

	@Test
	void unverifiedAccountWithWrongPasswordDoesNotRevealEmailOrRecoveryState() {
		User user = unverifiedUser();
		when(userRepository.findByUsername("berna")).thenReturn(Optional.of(user));
		when(passwordEncoder.matches("wrong", "encoded")).thenReturn(false);

		SoundConnectException exception = catchThrowableOfType(
				() -> authService.login(new LoginRequestDto("berna", "wrong")),
				SoundConnectException.class);

		assertThat(exception.getErrorType()).isEqualTo(ErrorType.INVALID_CREDENTIALS);
		assertThat(exception).isNotInstanceOf(EmailVerificationRequiredException.class);
		assertThat(exception.getDetails()).isNull();
		verifyNoInteractions(jwtTokenProvider, otpService, otpMailService);
	}

	@Test
	void erasedAccountCannotResumeVerificationEvenWithMatchingPassword() {
		User user = unverifiedUser();
		user.setErasedAt(LocalDateTime.now());
		when(userRepository.findByUsername("berna")).thenReturn(Optional.of(user));
		when(passwordEncoder.matches("secret", "encoded")).thenReturn(true);

		SoundConnectException exception = catchThrowableOfType(
				() -> authService.login(new LoginRequestDto("berna", "secret")),
				SoundConnectException.class);

		assertThat(exception.getErrorType()).isEqualTo(ErrorType.ACCOUNT_DELETED);
		assertThat(exception).isNotInstanceOf(EmailVerificationRequiredException.class);
		assertThat(exception.getDetails()).isNull();
		verifyNoInteractions(jwtTokenProvider, otpService, otpMailService);
	}

	@Test
	void verifiedInactiveAccountCannotUseEmailRecoveryToBypassItsStatus() {
		User user = unverifiedUser();
		user.setEmailVerified(true);
		when(userRepository.findByUsername("berna")).thenReturn(Optional.of(user));
		when(passwordEncoder.matches("secret", "encoded")).thenReturn(true);

		SoundConnectException exception = catchThrowableOfType(
				() -> authService.login(new LoginRequestDto("berna", "secret")),
				SoundConnectException.class);

		assertThat(exception.getErrorType()).isEqualTo(ErrorType.FORBIDDEN_ACCESS);
		assertThat(exception).isNotInstanceOf(EmailVerificationRequiredException.class);
		assertThat(exception.getDetails()).isNull();
		verifyNoInteractions(jwtTokenProvider, otpService, otpMailService);
	}

	@Test
	void passwordResetAfterInterruptedRegistrationStillRequiresOtpBeforeTheNewPasswordCanLogIn() {
		User user = unverifiedUser();
		user.setId(UUID.randomUUID());
		user.setRoles(Set.of(Role.builder().name(RoleEnum.ROLE_MUSICIAN.name()).build()));
		when(userRepository.findByUsername("berna")).thenReturn(Optional.of(user));
		when(userRepository.findByIdForUpdate(user.getId())).thenReturn(Optional.of(user));
		when(otpService.verifyPasswordResetOtp("berna@example.com", "654321")).thenReturn(true);
		when(passwordEncoder.encode("new-password")).thenReturn("new-hash");
		PasswordResetService passwordResetService = new PasswordResetService(
				userRepository, otpService, mock(PasswordResetMailService.class), passwordEncoder,
				accountRateLimitGuard, mock(PublicProfileResolverService.class));

		var resetResponse = passwordResetService.resetPassword(new ResetPasswordRequestDto(
				"berna", "654321", "new-password", "new-password"));

		assertThat(resetResponse.getSuccess()).isTrue();
		assertThat(user.getPassword()).isEqualTo("new-hash");
		assertThat(user.getEmailVerified()).isFalse();
		assertThat(user.getStatus()).isEqualTo(UserStatus.INACTIVE);
		when(passwordEncoder.matches("new-password", "new-hash")).thenReturn(true);
		when(passwordEncoder.matches("secret", "new-hash")).thenReturn(false);

		SoundConnectException oldPasswordError = catchThrowableOfType(
				() -> authService.login(new LoginRequestDto("berna", "secret")),
				SoundConnectException.class);
		assertThat(oldPasswordError.getErrorType()).isEqualTo(ErrorType.INVALID_CREDENTIALS);
		EmailVerificationRequiredException pendingError = catchThrowableOfType(
				() -> authService.login(new LoginRequestDto("berna", "new-password")),
				EmailVerificationRequiredException.class);
		assertThat(pendingError.getEmail()).isEqualTo("berna@example.com");
		verifyNoInteractions(jwtTokenProvider, otpMailService);

		when(otpService.verifyOtp("berna@example.com", "123456")).thenReturn(true);
		when(userRepository.findByEmailForUpdate("berna@example.com")).thenReturn(Optional.of(user));
		var verificationResponse = authService.verifyCode(
				new VerifyCodeRequestDto(pendingError.getEmail(), "123456"));
		assertThat(verificationResponse.getData()).isNull();
		assertThat(user.getEmailVerified()).isTrue();
		assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);

		when(jwtTokenProvider.generateToken(any())).thenReturn("musician-token");
		var loginResponse = authService.login(new LoginRequestDto("berna", "new-password"));

		assertThat(loginResponse.getSuccess()).isTrue();
		assertThat(loginResponse.getData().token()).isEqualTo("musician-token");
		assertThat(loginResponse.getData().status()).isEqualTo(UserStatus.ACTIVE);
	}

	private static User unverifiedUser() {
		return User.builder()
				.username("berna")
				.email(" Berna@Example.COM ")
				.password("encoded")
				.emailVerified(false)
				.status(UserStatus.INACTIVE)
				.build();
	}

	@Test
	void activeListenerLoginCarriesTheServerAuthoritativeChooserDecision() {
		User listener = User.builder()
				.id(UUID.randomUUID())
				.username("listener")
				.email("listener@example.com")
				.password("encoded")
				.emailVerified(true)
				.status(UserStatus.ACTIVE)
				.roles(Set.of(Role.builder().name(RoleEnum.ROLE_LISTENER.name()).build()))
				.build();
		when(userRepository.findByUsername("listener")).thenReturn(Optional.of(listener));
		when(passwordEncoder.matches("secret", "encoded")).thenReturn(true);
		when(jwtTokenProvider.generateToken(any())).thenReturn("token");
		when(listenerProfileChoiceStatusReader.requiresChoice(listener)).thenReturn(true);

		var response = authService.login(new LoginRequestDto("listener", "secret"));

		assertThat(response.getData().requiresListenerProfileChoice()).isTrue();
		verify(listenerProfileChoiceStatusReader).requiresChoice(listener);
	}

	@Test
	void usernameAvailabilityNormalizesAndReportsExistingUsername() {
		when(userRepository.existsByUsername("berkay")).thenReturn(true);

		var response = authService.usernameAvailability(
				new UsernameAvailabilityRequestDto(" BeRKay "));

		assertThat(response.getData().username()).isEqualTo("berkay");
		assertThat(response.getData().available()).isFalse();
		assertThat(response.getMessage()).isEqualTo(
				"Bu kullanıcı adı zaten kullanılıyor.");
		verify(accountRateLimitGuard).checkUsernameAvailability("berkay");
	}

	@Test
	void usernameAvailabilityReportsUnusedCanonicalUsername() {
		when(userRepository.existsByUsername("new-user")).thenReturn(false);

		var response = authService.usernameAvailability(
				new UsernameAvailabilityRequestDto("NEW-USER"));

		assertThat(response.getData().available()).isTrue();
		assertThat(response.getMessage()).isEqualTo(
				"Kullanıcı adı kullanılabilir.");
	}

	@Test
	void pendingVenueCannotAuthenticateEvenAfterEmailVerification() {
		User pendingVenue = User.builder()
				.username("pending-venue")
				.email("venue@example.com")
				.password("encoded")
				.emailVerified(true)
				.status(UserStatus.PENDING_VENUE_REQUEST)
				.build();
		when(userRepository.findByUsername("pending-venue")).thenReturn(Optional.of(pendingVenue));
		when(passwordEncoder.matches("secret", "encoded")).thenReturn(true);

		SoundConnectException exception = catchThrowableOfType(
				() -> authService.login(new LoginRequestDto("pending-venue", "secret")),
				SoundConnectException.class
		);

		assertThat(exception.getErrorType()).isEqualTo(ErrorType.PENDING_VENUE_APPROVAL);
		verifyNoInteractions(jwtTokenProvider);
	}

	@Test
	void pendingVenueWithWrongPasswordDoesNotRevealAccountStatus() {
		User pendingVenue = User.builder()
				.username("pending-venue")
				.email("venue@example.com")
				.password("encoded")
				.emailVerified(true)
				.status(UserStatus.PENDING_VENUE_REQUEST)
				.build();
		when(userRepository.findByUsername("pending-venue")).thenReturn(Optional.of(pendingVenue));
		when(passwordEncoder.matches("wrong", "encoded")).thenReturn(false);

		SoundConnectException exception = catchThrowableOfType(
				() -> authService.login(new LoginRequestDto("pending-venue", "wrong")),
				SoundConnectException.class
		);

		assertThat(exception.getErrorType()).isEqualTo(ErrorType.INVALID_CREDENTIALS);
		verifyNoInteractions(jwtTokenProvider);
	}

	@Test
	void pendingStudioCannotAuthenticateEvenAfterEmailVerification() {
		User pendingStudio = User.builder()
				.username("pending-studio")
				.email("studio@example.com")
				.password("encoded")
				.emailVerified(true)
				.status(UserStatus.PENDING_STUDIO_REQUEST)
				.build();
		when(userRepository.findByUsername("pending-studio")).thenReturn(Optional.of(pendingStudio));
		when(passwordEncoder.matches("secret", "encoded")).thenReturn(true);

		SoundConnectException exception = catchThrowableOfType(
				() -> authService.login(new LoginRequestDto("pending-studio", "secret")),
				SoundConnectException.class
		);

		assertThat(exception.getErrorType()).isEqualTo(ErrorType.PENDING_STUDIO_APPROVAL);
		verifyNoInteractions(jwtTokenProvider);
	}

	@Test
	void rejectedStudioGetsAStableDecisionErrorOnlyAfterPasswordVerification() {
		User rejectedStudio = User.builder()
				.username("rejected-studio")
				.email("studio@example.com")
				.password("encoded")
				.emailVerified(true)
				.status(UserStatus.REJECTED_STUDIO_REQUEST)
				.build();
		when(userRepository.findByUsername("rejected-studio"))
				.thenReturn(Optional.of(rejectedStudio));
		when(passwordEncoder.matches("secret", "encoded")).thenReturn(true);

		SoundConnectException exception = catchThrowableOfType(
				() -> authService.login(new LoginRequestDto("rejected-studio", "secret")),
				SoundConnectException.class
		);

		assertThat(exception.getErrorType()).isEqualTo(ErrorType.STUDIO_APPLICATION_REJECTED);
		verifyNoInteractions(jwtTokenProvider);
	}

	@Test
	void rejectedStudioWithWrongPasswordDoesNotRevealTheDecision() {
		User rejectedStudio = User.builder()
				.username("rejected-studio")
				.password("encoded")
				.status(UserStatus.REJECTED_STUDIO_REQUEST)
				.build();
		when(userRepository.findByUsername("rejected-studio"))
				.thenReturn(Optional.of(rejectedStudio));
		when(passwordEncoder.matches("wrong", "encoded")).thenReturn(false);

		SoundConnectException exception = catchThrowableOfType(
				() -> authService.login(new LoginRequestDto("rejected-studio", "wrong")),
				SoundConnectException.class
		);

		assertThat(exception.getErrorType()).isEqualTo(ErrorType.INVALID_CREDENTIALS);
		verifyNoInteractions(jwtTokenProvider);
	}

	@Test
	void unknownUsernameStillPerformsDummyPasswordCheck() {
		when(userRepository.findByUsername("missing")).thenReturn(Optional.empty());

		SoundConnectException exception = catchThrowableOfType(
				() -> authService.login(new LoginRequestDto(" MiSsInG ", "wrong")),
				SoundConnectException.class
		);

		assertThat(exception.getErrorType()).isEqualTo(ErrorType.INVALID_CREDENTIALS);
		verify(accountRateLimitGuard).checkLogin("missing");
		verify(userRepository).findByUsername("missing");
		verify(passwordEncoder).matches(
				"wrong",
				"$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy"
		);
		verifyNoInteractions(jwtTokenProvider);
	}
}

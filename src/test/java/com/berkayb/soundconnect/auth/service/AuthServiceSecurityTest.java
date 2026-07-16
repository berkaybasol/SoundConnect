package com.berkayb.soundconnect.auth.service;

import com.berkayb.soundconnect.auth.dto.request.LoginRequestDto;
import com.berkayb.soundconnect.auth.otp.service.OtpMailService;
import com.berkayb.soundconnect.auth.otp.service.OtpService;
import com.berkayb.soundconnect.auth.ratelimit.AuthAccountRateLimitGuard;
import com.berkayb.soundconnect.auth.security.JwtTokenProvider;
import com.berkayb.soundconnect.modules.application.venueapplication.service.VenueApplicationService;
import com.berkayb.soundconnect.modules.profile.shared.factory.ProfileFactory;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceSecurityTest {

	@Mock JwtTokenProvider jwtTokenProvider;
	@Mock UserRepository userRepository;
	@Mock PasswordEncoder passwordEncoder;
	@Mock RoleRepository roleRepository;
	@Mock ProfileFactory profileFactory;
	@Mock OtpService otpService;
	@Mock OtpMailService otpMailService;
	@Mock VenueApplicationService venueApplicationService;
	@Mock AuthAccountRateLimitGuard accountRateLimitGuard;
	@InjectMocks AuthService authService;

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

		assertThat(exception.getErrorType()).isEqualTo(ErrorType.FORBIDDEN_ACCESS);
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
	void unknownUsernameStillPerformsDummyPasswordCheck() {
		when(userRepository.findByUsername("missing")).thenReturn(Optional.empty());

		SoundConnectException exception = catchThrowableOfType(
				() -> authService.login(new LoginRequestDto("missing", "wrong")),
				SoundConnectException.class
		);

		assertThat(exception.getErrorType()).isEqualTo(ErrorType.INVALID_CREDENTIALS);
		verify(passwordEncoder).matches(
				"wrong",
				"$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy"
		);
		verifyNoInteractions(jwtTokenProvider);
	}
}

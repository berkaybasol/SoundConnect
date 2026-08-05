package com.berkayb.soundconnect.auth.service;

import com.berkayb.soundconnect.auth.otp.dto.request.VerifyCodeRequestDto;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceVerifyCodeTest {

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
		verify(userRepository, never()).save(org.mockito.ArgumentMatchers.any());
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
	}

	@Test
	void validCodeStillActivatesAnEligibleAccount() {
		User user = User.builder().emailVerified(false).status(UserStatus.INACTIVE).build();
		when(otpService.verifyOtp("user@example.com", "123456")).thenReturn(true);
		when(userRepository.findByEmailForUpdate("user@example.com")).thenReturn(Optional.of(user));

		var response = authService.verifyCode(request);

		assertThat(response.getCode()).isEqualTo(200);
		assertThat(user.getEmailVerified()).isTrue();
		assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
		verify(userRepository).save(user);
	}

	@Test
	void validCodeVerifiesButNeverReopensARejectedStudioAccount() {
		User user = User.builder()
				.emailVerified(false)
				.status(UserStatus.REJECTED_STUDIO_REQUEST)
				.build();
		when(otpService.verifyOtp("user@example.com", "123456")).thenReturn(true);
		when(userRepository.findByEmailForUpdate("user@example.com"))
				.thenReturn(Optional.of(user));

		authService.verifyCode(request);

		assertThat(user.getEmailVerified()).isTrue();
		assertThat(user.getStatus()).isEqualTo(UserStatus.REJECTED_STUDIO_REQUEST);
		verify(userRepository).save(user);
	}
}

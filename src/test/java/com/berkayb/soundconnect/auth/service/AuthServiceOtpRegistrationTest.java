package com.berkayb.soundconnect.auth.service;

import com.berkayb.soundconnect.auth.dto.request.RegisterRequestDto;
import com.berkayb.soundconnect.auth.dto.response.RegisterResponseDto;
import com.berkayb.soundconnect.auth.otp.service.OtpMailService;
import com.berkayb.soundconnect.auth.otp.service.OtpService;
import com.berkayb.soundconnect.auth.ratelimit.AuthAccountRateLimitGuard;
import com.berkayb.soundconnect.auth.security.JwtTokenProvider;
import com.berkayb.soundconnect.modules.application.venueapplication.service.VenueApplicationService;
import com.berkayb.soundconnect.modules.profile.shared.factory.ProfileFactory;
import com.berkayb.soundconnect.modules.role.entity.Role;
import com.berkayb.soundconnect.modules.role.enums.RoleEnum;
import com.berkayb.soundconnect.modules.role.repository.RoleRepository;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceOtpRegistrationTest {

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
	void initialRegistrationClaimPreventsASecondConcurrentMailAndCode() {
		RegisterRequestDto request = new RegisterRequestDto(
				"listener",
				"User@Example.com",
				"password123",
				"password123",
				RoleEnum.ROLE_LISTENER,
				null,
				null,
				null,
				null,
				null,
				null
		);
		Role listenerRole = Role.builder().name(RoleEnum.ROLE_LISTENER.name()).build();
		OtpService.OtpIssueClaim winner = new OtpService.OtpIssueClaim(true, "123456", 0L);
		OtpService.OtpIssueClaim loser = new OtpService.OtpIssueClaim(false, null, 29L);
		when(roleRepository.findByName(RoleEnum.ROLE_LISTENER.name())).thenReturn(Optional.of(listenerRole));
		when(passwordEncoder.encode("password123")).thenReturn("encoded");
		when(otpService.acquireInitialOtp("user@example.com")).thenReturn(winner, loser);
		when(otpService.getOtpTimeLeftSeconds("user@example.com")).thenReturn(180L);

		BaseResponse<RegisterResponseDto> first = authService.register(request);
		BaseResponse<RegisterResponseDto> second = authService.register(request);

		assertThat(first.getData().mailQueued()).isTrue();
		assertThat(second.getData().mailQueued()).isFalse();
		verify(otpMailService, times(1)).sendVerificationMail("user@example.com", "123456");
		verify(otpService, times(2)).acquireInitialOtp("user@example.com");
	}

	@Test
	void venueRegistrationWithoutNeighborhoodIsRejectedBeforeUserCreation() {
		RegisterRequestDto request = new RegisterRequestDto(
				"venue-owner",
				"venue@example.com",
				"password123",
				"password123",
				RoleEnum.ROLE_VENUE,
				"Venue",
				"Address",
				"05551234567",
				UUID.randomUUID().toString(),
				UUID.randomUUID().toString(),
				null
		);

		assertThatThrownBy(() -> authService.register(request))
				.isInstanceOfSatisfying(SoundConnectException.class,
						exception -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.VALIDATION_ERROR));
		verify(userRepository, never()).save(any());
		verify(venueApplicationService, never()).createApplication(any(), any());
	}
}

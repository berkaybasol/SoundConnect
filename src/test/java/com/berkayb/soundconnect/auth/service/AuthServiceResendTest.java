package com.berkayb.soundconnect.auth.service;

import com.berkayb.soundconnect.auth.otp.dto.request.ResendCodeRequestDto;
import com.berkayb.soundconnect.auth.otp.dto.response.ResendCodeResponseDto;
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
import com.berkayb.soundconnect.shared.response.BaseResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceResendTest {

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

	private final ResendCodeRequestDto request = new ResendCodeRequestDto("User@Example.com");
	private final User unverifiedUser = User.builder()
			.email("user@example.com")
			.emailVerified(false)
			.status(UserStatus.INACTIVE)
			.build();

	@BeforeEach
	void setUp() {
		lenient().when(userRepository.findByEmail("user@example.com"))
				.thenReturn(Optional.of(unverifiedUser));
	}

	@Test
	void onlyAtomicClaimWinnerCanQueueMail() {
		OtpService.OtpIssueClaim winner = new OtpService.OtpIssueClaim(
				true,
				"123456",
				0L
		);
		OtpService.OtpIssueClaim loser = new OtpService.OtpIssueClaim(
				false,
				null,
				24L
		);
		when(otpService.acquireDecoyResendOtp("user@example.com")).thenReturn(winner, loser);
		when(otpService.acquireResendOtp("user@example.com")).thenReturn(winner);
		when(otpService.getDecoyOtpTimeLeftSeconds("user@example.com")).thenReturn(180L, 179L);
		when(otpService.getDecoyResendCooldownLeftSeconds("user@example.com")).thenReturn(30L);

		BaseResponse<ResendCodeResponseDto> first = authService.resendCode(request);
		BaseResponse<ResendCodeResponseDto> second = authService.resendCode(request);

		assertThat(first.getCode()).isEqualTo(200);
		assertThat(first.getData().mailQueued()).isFalse();
		assertThat(second.getCode()).isEqualTo(429);
		assertThat(second.getData().mailQueued()).isFalse();
		assertThat(second.getData().cooldownSeconds()).isEqualTo(24L);
		verify(otpMailService, times(1)).sendVerificationMail("user@example.com", "123456");
		verify(otpService, times(2)).acquireDecoyResendOtp("user@example.com");
		verify(otpService, times(1)).acquireResendOtp("user@example.com");
	}

	@Test
	void rejectedClaimReturnsCooldownWithoutGeneratingOrQueuingAnotherCode() {
		OtpService.OtpIssueClaim rejected = new OtpService.OtpIssueClaim(
				false,
				null,
				17L
		);
		when(otpService.acquireDecoyResendOtp("user@example.com")).thenReturn(rejected);
		when(otpService.getDecoyOtpTimeLeftSeconds("user@example.com")).thenReturn(151L);

		BaseResponse<ResendCodeResponseDto> response = authService.resendCode(request);

		assertThat(response.getCode()).isEqualTo(429);
		assertThat(response.getSuccess()).isFalse();
		assertThat(response.getData()).isEqualTo(new ResendCodeResponseDto(151L, false, 17L));
		verifyNoInteractions(otpMailService);
		verify(otpService, never()).acquireResendOtp("user@example.com");
		verify(otpService, never()).acquireInitialOtp("user@example.com");
	}

	@Test
	void queueFailureDoesNotTurnThePublicResponseIntoAnAccountOracle() {
		OtpService.OtpIssueClaim winner = new OtpService.OtpIssueClaim(
				true,
				"123456",
				0L
		);
		when(otpService.acquireDecoyResendOtp("user@example.com")).thenReturn(winner);
		when(otpService.acquireResendOtp("user@example.com")).thenReturn(winner);
		doThrow(new IllegalStateException("queue unavailable"))
				.when(otpMailService).sendVerificationMail("user@example.com", "123456");
		when(otpService.getDecoyOtpTimeLeftSeconds("user@example.com")).thenReturn(180L);
		when(otpService.getDecoyResendCooldownLeftSeconds("user@example.com")).thenReturn(30L);

		BaseResponse<ResendCodeResponseDto> response = authService.resendCode(request);

		assertThat(response.getCode()).isEqualTo(200);
		assertThat(response.getData()).isEqualTo(new ResendCodeResponseDto(180L, false, 30L));
	}

	@Test
	void internalRegistrationCooldownDoesNotChangeThePublicContract() {
		OtpService.OtpIssueClaim publicWinner =
				new OtpService.OtpIssueClaim(true, "654321", 0L);
		OtpService.OtpIssueClaim deliveryCooldown =
				new OtpService.OtpIssueClaim(false, null, 19L);
		when(otpService.acquireDecoyResendOtp("user@example.com")).thenReturn(publicWinner);
		when(otpService.acquireResendOtp("user@example.com")).thenReturn(deliveryCooldown);
		when(otpService.getDecoyOtpTimeLeftSeconds("user@example.com")).thenReturn(180L);
		when(otpService.getDecoyResendCooldownLeftSeconds("user@example.com")).thenReturn(30L);

		BaseResponse<ResendCodeResponseDto> response = authService.resendCode(request);

		assertThat(response.getCode()).isEqualTo(200);
		assertThat(response.getData()).isEqualTo(new ResendCodeResponseDto(180L, false, 30L));
		verifyNoInteractions(otpMailService);
	}

	@Test
	void unknownAccountGetsTheSameGenericClaimContractWithoutMail() {
		when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.empty());
		OtpService.OtpIssueClaim winner = new OtpService.OtpIssueClaim(true, "654321", 0L);
		when(otpService.acquireDecoyResendOtp("user@example.com")).thenReturn(winner);
		when(otpService.getDecoyOtpTimeLeftSeconds("user@example.com")).thenReturn(180L);
		when(otpService.getDecoyResendCooldownLeftSeconds("user@example.com")).thenReturn(30L);

		BaseResponse<ResendCodeResponseDto> response = authService.resendCode(request);

		assertThat(response.getCode()).isEqualTo(200);
		assertThat(response.getData()).isEqualTo(new ResendCodeResponseDto(180L, false, 30L));
		verifyNoInteractions(otpMailService);
	}

	@Test
	void verifiedAccountGetsTheSameGenericClaimContractWithoutMail() {
		unverifiedUser.setEmailVerified(true);
		OtpService.OtpIssueClaim winner = new OtpService.OtpIssueClaim(true, "654321", 0L);
		when(otpService.acquireDecoyResendOtp("user@example.com")).thenReturn(winner);
		when(otpService.getDecoyOtpTimeLeftSeconds("user@example.com")).thenReturn(180L);
		when(otpService.getDecoyResendCooldownLeftSeconds("user@example.com")).thenReturn(30L);

		BaseResponse<ResendCodeResponseDto> response = authService.resendCode(request);

		assertThat(response.getCode()).isEqualTo(200);
		assertThat(response.getData()).isEqualTo(new ResendCodeResponseDto(180L, false, 30L));
		verifyNoInteractions(otpMailService);
	}
}

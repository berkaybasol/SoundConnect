package com.berkayb.soundconnect.auth.ratelimit;

import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.RateLimitedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthAccountRateLimitGuardTest {

	@Mock AuthRateLimiter rateLimiter;
	private AuthRateLimitProperties properties;
	private AuthAccountRateLimitGuard guard;

	@BeforeEach
	void setUp() {
		properties = new AuthRateLimitProperties();
		guard = new AuthAccountRateLimitGuard(rateLimiter, properties);
	}

	@Test
	void canonicalizesEmailBeforeApplyingAccountDimension() {
		when(rateLimiter.checkAccount("otp-resend", "user@example.com", properties.getOtpResend()))
				.thenReturn(AuthRateLimiter.Decision.permit());

		guard.checkOtpResend(" User@Example.COM ");

		verify(rateLimiter).checkAccount("otp-resend", "user@example.com", properties.getOtpResend());
	}

	@Test
	void blockedAccountDimensionUsesTheStandard429Exception() {
		when(rateLimiter.checkAccount("login", "target", properties.getLogin()))
				.thenReturn(AuthRateLimiter.Decision.block(27L));

		RateLimitedException exception = catchThrowableOfType(
				() -> guard.checkLogin("TARGET"), RateLimitedException.class
		);

		assertThat(exception.getErrorType()).isEqualTo(ErrorType.AUTH_RATE_LIMITED);
		assertThat(exception.getRetryAfterSeconds()).isEqualTo(27L);
	}

	@Test
	void loginUsesCrossRuntimeSimpleLowercaseAccountKey() {
		when(rateLimiter.checkAccount("login", "iuser", properties.getLogin()))
				.thenReturn(AuthRateLimiter.Decision.permit());

		guard.checkLogin(" \u0130USER ");

		verify(rateLimiter).checkAccount("login", "iuser", properties.getLogin());
	}

	@Test
	void passwordResetRequestAndConfirmUseCanonicalEmailAndSeparatePolicies() {
		when(rateLimiter.checkAccount(
				"password-reset-request",
				"user@example.com",
				properties.getPasswordResetRequest()))
				.thenReturn(AuthRateLimiter.Decision.permit());
		when(rateLimiter.checkAccount(
				"password-reset-confirm",
				"user@example.com",
				properties.getPasswordResetConfirm()))
				.thenReturn(AuthRateLimiter.Decision.permit());

		guard.checkPasswordResetRequest(" User@Example.COM ");
		guard.checkPasswordResetConfirm(" User@Example.COM ");

		verify(rateLimiter).checkAccount(
				"password-reset-request",
				"user@example.com",
				properties.getPasswordResetRequest());
		verify(rateLimiter).checkAccount(
				"password-reset-confirm",
				"user@example.com",
				properties.getPasswordResetConfirm());
	}

	@Test
	void discoveryChecksUseCanonicalKeysAndSeparatePolicies() {
		when(rateLimiter.checkAccount(
				"username-availability",
				"candidate",
				properties.getUsernameAvailability()))
				.thenReturn(AuthRateLimiter.Decision.permit());
		when(rateLimiter.checkAccount(
				"password-reset-lookup",
				"user-id:123",
				properties.getPasswordResetLookup()))
				.thenReturn(AuthRateLimiter.Decision.permit());

		guard.checkUsernameAvailability(" CANDIDATE ");
		guard.checkPasswordResetLookup("user-id:123");

		verify(rateLimiter).checkAccount(
				"username-availability",
				"candidate",
				properties.getUsernameAvailability());
		verify(rateLimiter).checkAccount(
				"password-reset-lookup",
				"user-id:123",
				properties.getPasswordResetLookup());
	}
}

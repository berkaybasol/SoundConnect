package com.berkayb.soundconnect.auth.ratelimit;

import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.RateLimitedException;
import com.berkayb.soundconnect.shared.exception.ServiceUnavailableRetryException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

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

	@ParameterizedTest
	@ValueSource(strings = {"login", "google-login", "register", "otp-verify", "otp-resend",
			"username-availability", "password-reset-lookup", "password-reset-request",
			"password-reset-confirm", "account-deletion"})
	void everyAccountDimensionRejectsUnavailableProtectionWith503(String bucket) {
		when(rateLimiter.checkAccount(eq(bucket), any(), any()))
				.thenReturn(AuthRateLimiter.Decision.unavailable());

		ServiceUnavailableRetryException exception = catchThrowableOfType(() -> {
			switch (bucket) {
				case "login" -> guard.checkLogin("secret-user");
				case "google-login" -> guard.checkGoogleLogin("secret-subject");
				case "register" -> guard.checkRegister("secret@example.test");
				case "otp-verify" -> guard.checkOtpVerify("secret@example.test");
				case "otp-resend" -> guard.checkOtpResend("secret@example.test");
				case "username-availability" -> guard.checkUsernameAvailability("secret-user");
				case "password-reset-lookup" -> guard.checkPasswordResetLookup("user-id:secret");
				case "password-reset-request" -> guard.checkPasswordResetRequest("secret@example.test");
				case "password-reset-confirm" -> guard.checkPasswordResetConfirm("secret@example.test");
				case "account-deletion" -> guard.checkAccountDeletion("secret-user-id");
				default -> throw new AssertionError("Unknown test bucket");
			}
		}, ServiceUnavailableRetryException.class);

		assertThat(exception.getErrorType()).isEqualTo(ErrorType.AUTH_RATE_LIMIT_UNAVAILABLE);
		assertThat(exception.getRetryAfterSeconds()).isEqualTo(5L);
		assertThat(exception.getMessage()).doesNotContain("secret");
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

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
		when(rateLimiter.checkAccount("login", "TARGET", properties.getLogin()))
				.thenReturn(AuthRateLimiter.Decision.block(27L));

		RateLimitedException exception = catchThrowableOfType(
				() -> guard.checkLogin("TARGET"), RateLimitedException.class
		);

		assertThat(exception.getErrorType()).isEqualTo(ErrorType.AUTH_RATE_LIMITED);
		assertThat(exception.getRetryAfterSeconds()).isEqualTo(27L);
	}
}

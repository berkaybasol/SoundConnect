package com.berkayb.soundconnect.auth.ratelimit;

import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.RateLimitedException;
import com.berkayb.soundconnect.shared.util.EmailUtils;
import com.berkayb.soundconnect.shared.util.UsernameUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Distributed credential-abuse guard keyed by a one-way account digest. The
 * existing servlet filter remains the independent client-IP dimension.
 */
@Component
@RequiredArgsConstructor
public class AuthAccountRateLimitGuard {

	private final AuthRateLimiter rateLimiter;
	private final AuthRateLimitProperties properties;

	public void checkLogin(String username) {
		check("login", UsernameUtils.normalize(username), properties.getLogin());
	}

	public void checkAccountDeletion(String userId) {
		check("account-deletion", userId, properties.getLogin());
	}

	public void checkRegister(String email) {
		check("register", EmailUtils.normalize(email), properties.getRegister());
	}

	public void checkOtpVerify(String email) {
		check("otp-verify", EmailUtils.normalize(email), properties.getOtpVerify());
	}

	public void checkOtpResend(String email) {
		check("otp-resend", EmailUtils.normalize(email), properties.getOtpResend());
	}

	public void checkUsernameAvailability(String username) {
		check(
				"username-availability",
				UsernameUtils.normalize(username),
				properties.getUsernameAvailability());
	}

	public void checkPasswordResetLookup(String identifier) {
		check(
				"password-reset-lookup",
				identifier,
				properties.getPasswordResetLookup());
	}

	public void checkPasswordResetRequest(String email) {
		check(
				"password-reset-request",
				EmailUtils.normalize(email),
				properties.getPasswordResetRequest());
	}

	public void checkPasswordResetConfirm(String email) {
		check(
				"password-reset-confirm",
				EmailUtils.normalize(email),
				properties.getPasswordResetConfirm());
	}

	public void checkGoogleLogin(String providerSubject) {
		check("google-login", providerSubject, properties.getGoogleLogin());
	}

	private void check(
			String bucket,
			String accountIdentifier,
			AuthRateLimitProperties.Policy policy
	) {
		AuthRateLimiter.Decision decision = rateLimiter.checkAccount(bucket, accountIdentifier, policy);
		if (!decision.allowed()) {
			throw new RateLimitedException(ErrorType.AUTH_RATE_LIMITED, decision.retryAfterSeconds());
		}
	}

}

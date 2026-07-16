package com.berkayb.soundconnect.auth.ratelimit;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Validated
@ConfigurationProperties(prefix = "app.security.auth-rate-limit")
public class AuthRateLimitProperties {

	private boolean enabled = true;

	@NotBlank
	private String keyPrefix = "soundconnect:auth-rate-limit";

	/**
	 * Proxy networks that are allowed to supply Forwarded/X-Forwarded-For.
	 * Empty by default so an Internet client can never choose its rate-limit key.
	 */
	private List<String> trustedProxyCidrs = new ArrayList<>();

	@NotNull
	private ForwardedHeader forwardedHeader = ForwardedHeader.FORWARDED;

	@Valid
	@NotNull
	private Policy login = new Policy(10, Duration.ofMinutes(1));

	@Valid
	@NotNull
	private Policy googleLogin = new Policy(10, Duration.ofMinutes(1));

	@Valid
	@NotNull
	private Policy register = new Policy(5, Duration.ofMinutes(5));

	@Valid
	@NotNull
	private Policy otpVerify = new Policy(10, Duration.ofMinutes(5));

	@Valid
	@NotNull
	private Policy otpResend = new Policy(3, Duration.ofMinutes(5));

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public String getKeyPrefix() {
		return keyPrefix;
	}

	public void setKeyPrefix(String keyPrefix) {
		this.keyPrefix = keyPrefix;
	}

	public List<String> getTrustedProxyCidrs() {
		return trustedProxyCidrs;
	}

	public void setTrustedProxyCidrs(List<String> trustedProxyCidrs) {
		this.trustedProxyCidrs = trustedProxyCidrs == null
				? new ArrayList<>()
				: new ArrayList<>(trustedProxyCidrs);
	}

	public ForwardedHeader getForwardedHeader() {
		return forwardedHeader;
	}

	public void setForwardedHeader(ForwardedHeader forwardedHeader) {
		this.forwardedHeader = forwardedHeader;
	}

	public Policy getLogin() {
		return login;
	}

	public void setLogin(Policy login) {
		this.login = login;
	}

	public Policy getGoogleLogin() {
		return googleLogin;
	}

	public void setGoogleLogin(Policy googleLogin) {
		this.googleLogin = googleLogin;
	}

	public Policy getRegister() {
		return register;
	}

	public void setRegister(Policy register) {
		this.register = register;
	}

	public Policy getOtpVerify() {
		return otpVerify;
	}

	public void setOtpVerify(Policy otpVerify) {
		this.otpVerify = otpVerify;
	}

	public Policy getOtpResend() {
		return otpResend;
	}

	public void setOtpResend(Policy otpResend) {
		this.otpResend = otpResend;
	}

	public static class Policy {

		@Min(1)
		private int limit;

		@NotNull
		private Duration window;

		public Policy() {
		}

		public Policy(int limit, Duration window) {
			this.limit = limit;
			this.window = window;
		}

		public int getLimit() {
			return limit;
		}

		public void setLimit(int limit) {
			this.limit = limit;
		}

		public Duration getWindow() {
			return window;
		}

		public void setWindow(Duration window) {
			this.window = window;
		}

		@AssertTrue(message = "rate-limit window must be at least one second")
		public boolean isWindowValid() {
			return window != null && window.compareTo(Duration.ofSeconds(1)) >= 0;
		}
	}

	public enum ForwardedHeader {
		FORWARDED,
		X_FORWARDED_FOR
	}
}

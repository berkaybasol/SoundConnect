package com.berkayb.soundconnect.auth.ratelimit;

import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.security.SecurityErrorResponseWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

import static com.berkayb.soundconnect.shared.constant.EndPoints.Auth.BASE;
import static com.berkayb.soundconnect.shared.constant.EndPoints.Auth.FORGOT_PASSWORD;
import static com.berkayb.soundconnect.shared.constant.EndPoints.Auth.GOOGLE_SIGN_IN;
import static com.berkayb.soundconnect.shared.constant.EndPoints.Auth.LOGIN;
import static com.berkayb.soundconnect.shared.constant.EndPoints.Auth.PASSWORD_RESET_ACCOUNT;
import static com.berkayb.soundconnect.shared.constant.EndPoints.Auth.REGISTER;
import static com.berkayb.soundconnect.shared.constant.EndPoints.Auth.RESEND_CODE;
import static com.berkayb.soundconnect.shared.constant.EndPoints.Auth.RESET_PASSWORD;
import static com.berkayb.soundconnect.shared.constant.EndPoints.Auth.USERNAME_AVAILABILITY;
import static com.berkayb.soundconnect.shared.constant.EndPoints.Auth.VERIFY_CODE;

@RequiredArgsConstructor
public class AuthRateLimitFilter extends OncePerRequestFilter {

	private static final String LOGIN_BUCKET = "login";
	private static final String GOOGLE_LOGIN_BUCKET = "google-login";
	private static final String REGISTER_BUCKET = "register";
	private static final String OTP_VERIFY_BUCKET = "otp-verify";
	private static final String OTP_RESEND_BUCKET = "otp-resend";
	private static final String USERNAME_AVAILABILITY_BUCKET = "username-availability";
	private static final String PASSWORD_RESET_LOOKUP_BUCKET = "password-reset-lookup";
	private static final String PASSWORD_RESET_REQUEST_BUCKET = "password-reset-request";
	private static final String PASSWORD_RESET_CONFIRM_BUCKET = "password-reset-confirm";

	private static final Map<String, String> BUCKET_BY_PATH = Map.ofEntries(
			Map.entry(BASE + LOGIN, LOGIN_BUCKET),
			Map.entry(BASE + GOOGLE_SIGN_IN, GOOGLE_LOGIN_BUCKET),
			Map.entry(BASE + REGISTER, REGISTER_BUCKET),
			Map.entry(BASE + VERIFY_CODE, OTP_VERIFY_BUCKET),
			Map.entry(BASE + RESEND_CODE, OTP_RESEND_BUCKET),
			Map.entry(BASE + USERNAME_AVAILABILITY, USERNAME_AVAILABILITY_BUCKET),
			Map.entry(BASE + PASSWORD_RESET_ACCOUNT, PASSWORD_RESET_LOOKUP_BUCKET),
			Map.entry(BASE + FORGOT_PASSWORD, PASSWORD_RESET_REQUEST_BUCKET),
			Map.entry(BASE + RESET_PASSWORD, PASSWORD_RESET_CONFIRM_BUCKET)
	);

	private final AuthRateLimiter rateLimiter;
	private final AuthRateLimitProperties properties;
	private final SecurityErrorResponseWriter responseWriter;
	private final TrustedProxyClientAddressResolver clientAddressResolver;

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !properties.isEnabled()
				|| !"POST".equalsIgnoreCase(request.getMethod())
				|| !BUCKET_BY_PATH.containsKey(requestPath(request));
	}

	@Override
	protected void doFilterInternal(
			HttpServletRequest request,
			HttpServletResponse response,
			FilterChain filterChain
	) throws ServletException, IOException {
		String bucket = BUCKET_BY_PATH.get(requestPath(request));
		AuthRateLimiter.Decision decision = rateLimiter.check(
				bucket,
				clientAddressResolver.resolve(request),
				policyFor(bucket)
		);

		if (!decision.allowed()) {
			response.setHeader("Retry-After", Long.toString(decision.retryAfterSeconds()));
			responseWriter.write(request, response, ErrorType.AUTH_RATE_LIMITED);
			return;
		}

		filterChain.doFilter(request, response);
	}

	private AuthRateLimitProperties.Policy policyFor(String bucket) {
		return switch (bucket) {
			case LOGIN_BUCKET -> properties.getLogin();
			case GOOGLE_LOGIN_BUCKET -> properties.getGoogleLogin();
			case REGISTER_BUCKET -> properties.getRegister();
			case OTP_VERIFY_BUCKET -> properties.getOtpVerify();
			case OTP_RESEND_BUCKET -> properties.getOtpResend();
			case USERNAME_AVAILABILITY_BUCKET -> properties.getUsernameAvailability();
			case PASSWORD_RESET_LOOKUP_BUCKET -> properties.getPasswordResetLookup();
			case PASSWORD_RESET_REQUEST_BUCKET -> properties.getPasswordResetRequest();
			case PASSWORD_RESET_CONFIRM_BUCKET -> properties.getPasswordResetConfirm();
			default -> throw new IllegalArgumentException("Unknown authentication rate-limit bucket");
		};
	}

	private static String requestPath(HttpServletRequest request) {
		String servletPath = request.getServletPath();
		String path = servletPath == null || servletPath.isBlank()
				? request.getRequestURI()
				: servletPath;
		if (path == null) return "";
		if ((servletPath == null || servletPath.isBlank())
				&& request.getContextPath() != null
				&& !request.getContextPath().isBlank()
				&& path.startsWith(request.getContextPath())) {
			path = path.substring(request.getContextPath().length());
		}
		return stripMatrixParameters(path);
	}

	private static String stripMatrixParameters(String path) {
		StringBuilder normalized = new StringBuilder(path.length());
		boolean skippingParameter = false;
		for (int index = 0; index < path.length(); index++) {
			char character = path.charAt(index);
			if (character == ';') {
				skippingParameter = true;
			} else if (character == '/') {
				skippingParameter = false;
				normalized.append(character);
			} else if (!skippingParameter) {
				normalized.append(character);
			}
		}
		return normalized.toString();
	}
}

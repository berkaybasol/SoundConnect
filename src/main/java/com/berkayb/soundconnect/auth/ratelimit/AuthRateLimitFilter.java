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
import static com.berkayb.soundconnect.shared.constant.EndPoints.Auth.GOOGLE_SIGN_IN;
import static com.berkayb.soundconnect.shared.constant.EndPoints.Auth.LOGIN;
import static com.berkayb.soundconnect.shared.constant.EndPoints.Auth.REGISTER;
import static com.berkayb.soundconnect.shared.constant.EndPoints.Auth.RESEND_CODE;
import static com.berkayb.soundconnect.shared.constant.EndPoints.Auth.VERIFY_CODE;

@RequiredArgsConstructor
public class AuthRateLimitFilter extends OncePerRequestFilter {

	private static final String LOGIN_BUCKET = "login";
	private static final String GOOGLE_LOGIN_BUCKET = "google-login";
	private static final String REGISTER_BUCKET = "register";
	private static final String OTP_VERIFY_BUCKET = "otp-verify";
	private static final String OTP_RESEND_BUCKET = "otp-resend";

	private static final Map<String, String> BUCKET_BY_PATH = Map.of(
			BASE + LOGIN, LOGIN_BUCKET,
			BASE + GOOGLE_SIGN_IN, GOOGLE_LOGIN_BUCKET,
			BASE + REGISTER, REGISTER_BUCKET,
			BASE + VERIFY_CODE, OTP_VERIFY_BUCKET,
			BASE + RESEND_CODE, OTP_RESEND_BUCKET
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

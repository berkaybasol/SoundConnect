package com.berkayb.soundconnect.auth.ratelimit;

import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.security.SecurityErrorResponseWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthRateLimitFilterTest {

	@Mock AuthRateLimiter rateLimiter;
	@Mock SecurityErrorResponseWriter responseWriter;
	@Mock HttpServletRequest request;
	@Mock HttpServletResponse response;
	@Mock FilterChain filterChain;

	private AuthRateLimitProperties properties;
	private AuthRateLimitFilter filter;

	@BeforeEach
	void setUp() {
		properties = new AuthRateLimitProperties();
		filter = new AuthRateLimitFilter(
				rateLimiter,
				properties,
				responseWriter,
				new TrustedProxyClientAddressResolver(properties.getTrustedProxyCidrs())
		);
	}

	@Test
	void emitsContractErrorAndRetryAfterWhenRequestIsLimited() throws Exception {
		when(request.getServletPath()).thenReturn("/api/v1/auth/login");
		when(request.getRemoteAddr()).thenReturn("203.0.113.1");
		when(rateLimiter.check("login", "203.0.113.1", properties.getLogin()))
				.thenReturn(AuthRateLimiter.Decision.block(41L));

		filter.doFilterInternal(request, response, filterChain);

		verify(response).setHeader("Retry-After", "41");
		verify(responseWriter).write(request, response, ErrorType.AUTH_RATE_LIMITED);
		verify(filterChain, never()).doFilter(request, response);
	}

	@Test
	void continuesWhenTheRequestIsWithinTheLimit() throws Exception {
		when(request.getServletPath()).thenReturn("/api/v1/auth/google-sign-in");
		when(request.getRemoteAddr()).thenReturn("203.0.113.2");
		when(rateLimiter.check("google-login", "203.0.113.2", properties.getGoogleLogin()))
				.thenReturn(AuthRateLimiter.Decision.permit());

		filter.doFilterInternal(request, response, filterChain);

		verify(filterChain).doFilter(request, response);
		verify(responseWriter, never()).write(request, response, ErrorType.AUTH_RATE_LIMITED);
	}

	@Test
	void ignoresForwardingHeadersFromAnUntrustedPeer() throws Exception {
		when(request.getServletPath()).thenReturn("/api/v1/auth/login");
		when(request.getRemoteAddr()).thenReturn("198.51.100.25");
		lenient().when(request.getHeader("Forwarded")).thenReturn("for=203.0.113.99");
		when(rateLimiter.check("login", "198.51.100.25", properties.getLogin()))
				.thenReturn(AuthRateLimiter.Decision.permit());

		filter.doFilterInternal(request, response, filterChain);

		verify(rateLimiter).check("login", "198.51.100.25", properties.getLogin());
	}

	@Test
	void usesTheFirstUntrustedHopFromTheRightOfATrustedProxyChain() throws Exception {
		properties.setTrustedProxyCidrs(List.of("10.0.0.0/8", "2001:db8:ffff::/48"));
		filter = new AuthRateLimitFilter(
				rateLimiter,
				properties,
				responseWriter,
				new TrustedProxyClientAddressResolver(properties.getTrustedProxyCidrs())
		);
		when(request.getServletPath()).thenReturn("/api/v1/auth/login");
		when(request.getRemoteAddr()).thenReturn("10.0.0.8");
		when(request.getHeader("Forwarded"))
				.thenReturn("for=192.0.2.44;proto=https, for=10.1.2.3");
		when(rateLimiter.check("login", "192.0.2.44", properties.getLogin()))
				.thenReturn(AuthRateLimiter.Decision.permit());

		filter.doFilterInternal(request, response, filterChain);

		verify(rateLimiter).check("login", "192.0.2.44", properties.getLogin());
	}

	@Test
	void fallsBackToTheDirectPeerWhenTheTrustedProxyHeaderIsMalformed() throws Exception {
		properties.setTrustedProxyCidrs(List.of("10.0.0.0/8"));
		filter = new AuthRateLimitFilter(
				rateLimiter,
				properties,
				responseWriter,
				new TrustedProxyClientAddressResolver(properties.getTrustedProxyCidrs())
		);
		when(request.getServletPath()).thenReturn("/api/v1/auth/login");
		when(request.getRemoteAddr()).thenReturn("10.0.0.8");
		when(request.getHeader("Forwarded")).thenReturn("for=_hidden");
		when(rateLimiter.check("login", "10.0.0.8", properties.getLogin()))
				.thenReturn(AuthRateLimiter.Decision.permit());

		filter.doFilterInternal(request, response, filterChain);

		verify(rateLimiter).check("login", "10.0.0.8", properties.getLogin());
	}

	@Test
	void appliesTheSameBucketWhenMvcStripsMatrixParameters() throws Exception {
		when(request.getServletPath()).thenReturn("/api/v1/auth/login;source=mobile");
		when(request.getRemoteAddr()).thenReturn("203.0.113.10");
		when(rateLimiter.check("login", "203.0.113.10", properties.getLogin()))
				.thenReturn(AuthRateLimiter.Decision.permit());

		filter.doFilterInternal(request, response, filterChain);

		verify(rateLimiter).check("login", "203.0.113.10", properties.getLogin());
		verify(filterChain).doFilter(request, response);
	}
}

package com.berkayb.soundconnect.auth.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TrustedProxyClientAddressResolverTest {

	@Test
	void parsesQuotedRfcForwardedIpv6AndTrustedHops() {
		HttpServletRequest request = mock(HttpServletRequest.class);
		when(request.getRemoteAddr()).thenReturn("2001:db8:ffff::10");
		when(request.getHeader("Forwarded"))
				.thenReturn("for=\"[2001:db8:1234::7]:443\";proto=https, for=\"[2001:db8:ffff::20]\"");

		String result = new TrustedProxyClientAddressResolver(List.of("2001:db8:ffff::/48"))
				.resolve(request);

		assertThat(result).isEqualTo("2001:db8:1234:0:0:0:0:7");
	}

	@Test
	void xForwardedForSpoofToTheLeftCannotReplaceTheActualConnectingClient() {
		HttpServletRequest request = mock(HttpServletRequest.class);
		when(request.getRemoteAddr()).thenReturn("10.0.0.9");
		when(request.getHeader("X-Forwarded-For"))
				.thenReturn("203.0.113.200, 198.51.100.42");

		String result = new TrustedProxyClientAddressResolver(
				List.of("10.0.0.0/8"),
				AuthRateLimitProperties.ForwardedHeader.X_FORWARDED_FOR
		)
				.resolve(request);

		assertThat(result).isEqualTo("198.51.100.42");
	}

	@Test
	void invalidConfiguredCidrFailsStartupConstruction() {
		assertThatThrownBy(() -> new TrustedProxyClientAddressResolver(List.of("10.0.0.0/99")))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void combinesRepeatedForwardedHeaderFieldsBeforeWalkingFromTheTrustedEdge() {
		HttpServletRequest request = mock(HttpServletRequest.class);
		when(request.getRemoteAddr()).thenReturn("10.0.0.9");
		when(request.getHeaders("Forwarded")).thenReturn(java.util.Collections.enumeration(List.of(
				"for=203.0.113.200",
				"for=198.51.100.42"
		)));

		String result = new TrustedProxyClientAddressResolver(List.of("10.0.0.0/8"))
				.resolve(request);

		assertThat(result).isEqualTo("198.51.100.42");
	}

	@Test
	void ignoresAnUnselectedHeaderEvenWhenTheClientInjectedIt() {
		HttpServletRequest request = mock(HttpServletRequest.class);
		when(request.getRemoteAddr()).thenReturn("10.0.0.9");
		when(request.getHeader("X-Forwarded-For")).thenReturn("198.51.100.42");
		lenient().when(request.getHeader("Forwarded")).thenReturn("for=203.0.113.200");

		String result = new TrustedProxyClientAddressResolver(
				List.of("10.0.0.0/8"),
				AuthRateLimitProperties.ForwardedHeader.X_FORWARDED_FOR
		).resolve(request);

		assertThat(result).isEqualTo("198.51.100.42");
	}
}

package com.berkayb.soundconnect.shared.config;

import com.berkayb.soundconnect.auth.ratelimit.AuthRateLimitFilter;
import com.berkayb.soundconnect.auth.security.JwtAuthenticationFilter;
import com.berkayb.soundconnect.shared.security.RestAccessDeniedHandler;
import com.berkayb.soundconnect.shared.security.RestAuthenticationEntryPoint;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.DefaultCorsProcessor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class SecurityConfigCorsTest {

	@Test
	void corsUsesExplicitOriginsAndSupportsPatchWithoutCredentials() {
		CorsProperties properties = new CorsProperties();
		properties.setAllowedOriginPatterns(List.of("https://app.soundconnect.example"));
		SecurityConfig securityConfig = new SecurityConfig(
				mock(JwtAuthenticationFilter.class),
				mock(AuthRateLimitFilter.class),
				properties,
				mock(RestAuthenticationEntryPoint.class),
				mock(RestAccessDeniedHandler.class)
		);

		CorsConfiguration cors = securityConfig.corsConfigurationSource()
				.getCorsConfiguration(new MockHttpServletRequest("OPTIONS", "/api/v1/messages"));

		assertThat(cors).isNotNull();
		assertThat(cors.getAllowedOriginPatterns()).containsExactly("https://app.soundconnect.example");
		assertThat(cors.getAllowedMethods()).contains("PATCH");
		assertThat(cors.getAllowCredentials()).isFalse();
	}

	@Test
	void announcementSourceHeaderPassesAllowedOriginPreflight() throws Exception {
		CorsProperties properties = new CorsProperties();
		properties.setAllowedOriginPatterns(List.of("https://app.soundconnect.example"));
		SecurityConfig securityConfig = new SecurityConfig(
				mock(JwtAuthenticationFilter.class), mock(AuthRateLimitFilter.class), properties,
				mock(RestAuthenticationEntryPoint.class), mock(RestAccessDeniedHandler.class));
		MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/v1/likes");
		request.addHeader("Origin", "https://app.soundconnect.example");
		request.addHeader("Access-Control-Request-Method", "POST");
		request.addHeader("Access-Control-Request-Headers", "authorization,content-type,x-announcement-source");
		MockHttpServletResponse response = new MockHttpServletResponse();
		CorsConfiguration cors = securityConfig.corsConfigurationSource().getCorsConfiguration(request);

		assertThat(new DefaultCorsProcessor().processRequest(cors, request, response)).isTrue();
		assertThat(response.getHeader("Access-Control-Allow-Origin"))
				.isEqualTo("https://app.soundconnect.example");
		assertThat(response.getHeader("Access-Control-Allow-Headers"))
				.containsIgnoringCase("x-announcement-source");
		assertThat(cors.checkOrigin("https://untrusted.example")).isNull();
		assertThat(cors.checkHeaders(List.of("X-Unrecognized-Header"))).isNull();
	}

	@Test
	void bareWildcardOriginFailsClosed() {
		CorsProperties properties = new CorsProperties();
		properties.setAllowedOriginPatterns(List.of("*"));
		SecurityConfig securityConfig = new SecurityConfig(
				mock(JwtAuthenticationFilter.class),
				mock(AuthRateLimitFilter.class),
				properties,
				mock(RestAuthenticationEntryPoint.class),
				mock(RestAccessDeniedHandler.class)
		);

		assertThatThrownBy(securityConfig::corsConfigurationSource)
				.isInstanceOf(IllegalStateException.class);
	}
}

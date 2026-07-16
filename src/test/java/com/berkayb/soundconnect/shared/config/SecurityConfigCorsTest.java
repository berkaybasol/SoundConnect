package com.berkayb.soundconnect.shared.config;

import com.berkayb.soundconnect.auth.ratelimit.AuthRateLimitFilter;
import com.berkayb.soundconnect.auth.security.JwtAuthenticationFilter;
import com.berkayb.soundconnect.shared.security.RestAccessDeniedHandler;
import com.berkayb.soundconnect.shared.security.RestAuthenticationEntryPoint;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;

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

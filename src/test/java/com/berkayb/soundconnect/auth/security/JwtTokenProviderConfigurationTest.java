package com.berkayb.soundconnect.auth.security;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtTokenProviderConfigurationTest {

	@Test
	void rejectsWeakSigningSecretAtStartup() {
		JwtTokenProvider provider = configuredProvider("too-short", 60_000, "soundconnect");

		assertThatThrownBy(provider::validateConfiguration)
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("at least 32");
	}

	@Test
	void acceptsStrongCompleteConfiguration() {
		JwtTokenProvider provider = configuredProvider(
				"0123456789abcdef0123456789abcdef",
				60_000,
				"soundconnect"
		);

		assertThatCode(provider::validateConfiguration).doesNotThrowAnyException();
	}

	private JwtTokenProvider configuredProvider(String secret, long expiration, String issuer) {
		JwtTokenProvider provider = new JwtTokenProvider();
		ReflectionTestUtils.setField(provider, "jwtSecret", secret);
		ReflectionTestUtils.setField(provider, "jwtExpiration", expiration);
		ReflectionTestUtils.setField(provider, "jwtIssuer", issuer);
		return provider;
	}
}

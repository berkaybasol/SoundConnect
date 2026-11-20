package com.berkayb.soundconnect.auth.security;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
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

	@Test void recoverySigningKeyRejectsOldTokensAndPreservesSessionRevisionInNewToken() {
		var before = configuredProvider("0123456789abcdef0123456789abcdef", 60_000, "soundconnect");
		var after = configuredProvider("fedcba9876543210fedcba9876543210", 60_000, "soundconnect");
		var user = new com.berkayb.soundconnect.modules.user.entity.User();
		user.setId(java.util.UUID.fromString("11111111-1111-4111-8111-111111111111"));
		user.setRoles(java.util.Set.of()); user.setPermissions(java.util.Set.of());
		ReflectionTestUtils.setField(user, "sessionVersion", 7L);
		var details = UserDetailsImpl.fromUser(user);
		var oldToken = before.generateToken(details);
		assertThat(before.validateToken(oldToken)).isTrue();
		assertThat(after.validateToken(oldToken)).isFalse();
		var newToken = after.generateToken(details);
		assertThat(after.validateToken(newToken)).isTrue();
		assertThat(after.getSessionVersionFromToken(newToken)).isEqualTo(7L);
	}
}

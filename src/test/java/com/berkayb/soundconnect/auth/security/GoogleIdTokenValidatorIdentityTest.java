package com.berkayb.soundconnect.auth.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GoogleIdTokenValidatorIdentityTest {

	@Test
	void acceptsAStableGoogleSubjectAndRejectsBlankOrUnsafeValues() {
		assertThat(GoogleIdTokenValidator.isValidSubject("109876543210987654321")).isTrue();
		assertThat(GoogleIdTokenValidator.isValidSubject("")).isFalse();
		assertThat(GoogleIdTokenValidator.isValidSubject(" subject ")).isFalse();
		assertThat(GoogleIdTokenValidator.isValidSubject("subject\u0000suffix")).isFalse();
	}

	@Test
	void requiresOneBoundedMailboxAddress() {
		assertThat(GoogleIdTokenValidator.isPlausibleEmail("user@example.com")).isTrue();
		assertThat(GoogleIdTokenValidator.isPlausibleEmail("missing-at.example.com")).isFalse();
		assertThat(GoogleIdTokenValidator.isPlausibleEmail("a@b@example.com")).isFalse();
		assertThat(GoogleIdTokenValidator.isPlausibleEmail("x".repeat(250) + "@x.test")).isFalse();
	}
}

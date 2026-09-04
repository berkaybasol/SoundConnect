package com.berkayb.soundconnect.modules.spotify.config;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SpotifyPropertiesTest {

	private static jakarta.validation.ValidatorFactory validatorFactory;
	private static Validator validator;

	@BeforeAll
	static void createValidator() {
		validatorFactory = Validation.buildDefaultValidatorFactory();
		validator = validatorFactory.getValidator();
	}

	@AfterAll
	static void closeValidator() {
		validatorFactory.close();
	}

	@Test
	void defaultsUseABoundedOEmbedPoolAndPayloadBudget() {
		SpotifyProperties properties = new SpotifyProperties();

		assertThat(validator.validate(properties)).isEmpty();
		assertThat(properties.getHttp().getOEmbedMaxConnections()).isEqualTo(32);
		assertThat(properties.getHttp().getOEmbedPendingAcquireMaxCount()).isEqualTo(64);
		assertThat(properties.getHttp().getOEmbedPendingAcquireTimeoutMs()).isEqualTo(750);
		assertThat(properties.getHttp().getOEmbedMaxResponseBytes()).isEqualTo(65_536);
	}

	@Test
	void rejectsAnAcquireTimeoutLongerThanTheHttpResponseTimeout() {
		SpotifyProperties properties = new SpotifyProperties();
		properties.getHttp().setResponseTimeoutMs(500);
		properties.getHttp().setOEmbedPendingAcquireTimeoutMs(501);

		assertThat(validator.validate(properties))
				.anyMatch(violation -> violation.getPropertyPath().toString()
						.equals("http.pendingAcquireTimeoutBounded"));
	}

	@Test
	void rejectsAPoolSmallerThanOneLegalFourPlaylistBatch() {
		SpotifyProperties properties = new SpotifyProperties();
		properties.getHttp().setOEmbedMaxConnections(3);

		assertThat(validator.validate(properties))
				.anyMatch(violation -> violation.getPropertyPath().toString()
						.equals("http.oEmbedMaxConnections"));
	}

	@Test
	void rejectsUnboundedPoolQueueAndPayloadOverrides() {
		SpotifyProperties properties = new SpotifyProperties();
		properties.getHttp().setOEmbedMaxConnections(65);
		properties.getHttp().setOEmbedPendingAcquireMaxCount(257);
		properties.getHttp().setOEmbedMaxResponseBytes(262_145);

		assertThat(validator.validate(properties))
				.extracting(violation -> violation.getPropertyPath().toString())
				.contains(
						"http.oEmbedMaxConnections",
						"http.oEmbedPendingAcquireMaxCount",
						"http.oEmbedMaxResponseBytes"
				);
	}
}

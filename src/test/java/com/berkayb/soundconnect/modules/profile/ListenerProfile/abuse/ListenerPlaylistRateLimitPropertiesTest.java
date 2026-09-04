package com.berkayb.soundconnect.modules.profile.ListenerProfile.abuse;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ListenerPlaylistRateLimitPropertiesTest {

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
	void defaultsEnforceAtMostTenRequestsPerMinute() {
		ListenerPlaylistRateLimitProperties properties =
				new ListenerPlaylistRateLimitProperties();

		assertThat(validator.validate(properties)).isEmpty();
		assertThat(properties.getLimit()).isEqualTo(10);
		assertThat(properties.getWindow()).isEqualTo(Duration.ofMinutes(1));
	}

	@Test
	void rejectsAnOverrideThatWouldAllowMoreThanTenRequests() {
		ListenerPlaylistRateLimitProperties properties =
				new ListenerPlaylistRateLimitProperties();
		properties.setLimit(11);

		assertThat(validator.validate(properties))
				.anyMatch(violation -> violation.getPropertyPath().toString().equals("limit"));
	}

	@Test
	void rejectsAWindowThatWouldWeakenThePerMinuteProtection() {
		ListenerPlaylistRateLimitProperties properties =
				new ListenerPlaylistRateLimitProperties();
		properties.setWindow(Duration.ofSeconds(59));

		assertThat(validator.validate(properties))
				.anyMatch(violation -> violation.getPropertyPath().toString().equals("windowValid"));

		properties.setWindow(Duration.ofHours(2));
		assertThat(validator.validate(properties))
				.anyMatch(violation -> violation.getPropertyPath().toString().equals("windowValid"));
	}
}

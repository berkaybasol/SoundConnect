package com.berkayb.soundconnect.modules.follow.outbox;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class FollowNotificationOutboxPropertiesTest {
	private final Validator validator = Validation
			.buildDefaultValidatorFactory()
			.getValidator();

	@Test
	void defaultsAreValidAndBounded() {
		FollowNotificationOutboxProperties properties =
				new FollowNotificationOutboxProperties();

		assertThat(validator.validate(properties)).isEmpty();
	}

	@Test
	void rejectsRetryWindowThatExceedsTheHealthAgeThreshold() {
		FollowNotificationOutboxProperties properties =
				new FollowNotificationOutboxProperties();
		properties.setRetryMaxDelay(Duration.ofHours(1));
		properties.setHealthUndeliveredAgeThreshold(Duration.ofMinutes(30));

		assertThat(validator.validate(properties))
				.anyMatch(violation -> "timingConfigurationSafe".equals(violation.getPropertyPath().toString()));
	}
}

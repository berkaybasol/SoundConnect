package com.berkayb.soundconnect.modules.event.performer.outbox;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class EventPerformerNotificationOutboxPropertiesTest {
	private final Validator validator = Validation
			.buildDefaultValidatorFactory()
			.getValidator();

	@Test
	void defaultsAreValidAndBounded() {
		EventPerformerNotificationOutboxProperties properties =
				new EventPerformerNotificationOutboxProperties();

		assertThat(validator.validate(properties)).isEmpty();
	}

	@Test
	void rejectsRetryWindowThatExceedsTheHealthAgeThreshold() {
		EventPerformerNotificationOutboxProperties properties =
				new EventPerformerNotificationOutboxProperties();
		properties.setRetryMaxDelay(Duration.ofHours(1));
		properties.setHealthUndeliveredAgeThreshold(Duration.ofMinutes(30));

		assertThat(validator.validate(properties))
				.anyMatch(violation -> "timingConfigurationSafe".equals(violation.getPropertyPath().toString()));
	}
}

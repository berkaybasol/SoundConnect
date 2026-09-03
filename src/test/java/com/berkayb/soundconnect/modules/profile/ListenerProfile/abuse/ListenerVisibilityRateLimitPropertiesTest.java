package com.berkayb.soundconnect.modules.profile.ListenerProfile.abuse;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ListenerVisibilityRateLimitPropertiesTest {

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
	void defaultsAreSafeAndValid() {
		assertThat(validator.validate(new ListenerVisibilityRateLimitProperties())).isEmpty();
	}

	@Test
	void rejectsAProdOverrideThatWouldEffectivelyDisableBurstProtection() {
		ListenerVisibilityRateLimitProperties properties =
				new ListenerVisibilityRateLimitProperties();
		properties.setBurstCapacity(11);

		assertThat(validator.validate(properties))
				.anyMatch(violation -> violation.getPropertyPath().toString().equals("burstCapacity"));
	}

	@Test
	void rejectsUnsafeOrAccidentallyProductLikeRefillWindows() {
		ListenerVisibilityRateLimitProperties properties =
				new ListenerVisibilityRateLimitProperties();
		properties.setRefillPeriod(Duration.ofSeconds(1));
		assertThat(validator.validate(properties))
				.anyMatch(violation -> violation.getPropertyPath().toString().equals("refillPeriodValid"));

		properties.setRefillPeriod(Duration.ofMinutes(2));
		assertThat(validator.validate(properties))
				.anyMatch(violation -> violation.getPropertyPath().toString().equals("refillPeriodValid"));
	}
}

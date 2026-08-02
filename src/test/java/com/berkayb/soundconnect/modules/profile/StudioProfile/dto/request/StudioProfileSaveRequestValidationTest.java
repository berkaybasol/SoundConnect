package com.berkayb.soundconnect.modules.profile.StudioProfile.dto.request;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StudioProfileSaveRequestValidationTest {

	private static ValidatorFactory validatorFactory;
	private static Validator validator;

	@BeforeAll
	static void setUpValidator() {
		validatorFactory = Validation.buildDefaultValidatorFactory();
		validator = validatorFactory.getValidator();
	}

	@AfterAll
	static void closeValidator() {
		validatorFactory.close();
	}

	@Test
	void urlLimitsMatchTheInheritedDatabaseColumns() {
		StudioProfileSaveRequestDto valid = requestWithWebsite("x".repeat(255));
		StudioProfileSaveRequestDto tooLong = requestWithWebsite("x".repeat(256));

		assertThat(validator.validate(valid)).isEmpty();
		assertThat(validator.validate(tooLong))
				.extracting(violation -> violation.getPropertyPath().toString())
				.containsExactly("website");
	}

	private StudioProfileSaveRequestDto requestWithWebsite(String website) {
		return new StudioProfileSaveRequestDto(
				"Studio", null, null, null, null, website,
				null, null, null, null, null, null, null
		);
	}
}

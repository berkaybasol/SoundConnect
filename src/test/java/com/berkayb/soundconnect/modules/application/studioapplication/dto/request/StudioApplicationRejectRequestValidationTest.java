package com.berkayb.soundconnect.modules.application.studioapplication.dto.request;

import com.berkayb.soundconnect.modules.application.admin.ApplicationRejectionRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StudioApplicationRejectRequestValidationTest {
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
	void reasonIsRequiredAndBoundedForJsonRequestBody() {
		assertThat(validator.validate(new ApplicationRejectionRequest("Uygun bulunmadı")))
				.isEmpty();
		assertThat(validator.validate(new ApplicationRejectionRequest(" ")))
				.extracting(violation -> violation.getPropertyPath().toString())
				.contains("reason");
		assertThat(validator.validate(new ApplicationRejectionRequest("r".repeat(501))))
				.extracting(violation -> violation.getPropertyPath().toString())
				.contains("reason");
	}
}

package com.berkayb.soundconnect.auth.dto.request;

import com.berkayb.soundconnect.modules.role.enums.RoleEnum;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RegisterStudioRequestValidationTest {

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
	void nonStudioRegistrationMayOmitStudioFields() {
		assertThat(validator.validate(request(
				RoleEnum.ROLE_LISTENER, null, null, null))).isEmpty();
	}

	@Test
	void studioRegistrationAcceptsFieldsWithinPersistenceLimits() {
		assertThat(validator.validate(request(
				RoleEnum.ROLE_STUDIO, "Devo Studio", "Moda Caddesi", "05551234567")))
				.isEmpty();
		assertThat(validator.validate(request(
				RoleEnum.ROLE_STUDIO, "Devo Studio", "Moda Caddesi", "+90 (555) 123 45 67")))
				.isEmpty();
	}

	@Test
	void studioRegistrationRejectsOversizedPersistenceFields() {
		assertThat(validator.validate(request(
				RoleEnum.ROLE_STUDIO, "s".repeat(101), "a".repeat(256), "05551234567")))
				.extracting(violation -> violation.getPropertyPath().toString())
				.contains("studioName", "studioAddress");
	}

	@Test
	void studioRegistrationRejectsMalformedPhone() {
		assertThat(validator.validate(request(
				RoleEnum.ROLE_STUDIO, "Devo Studio", "Moda Caddesi", "0555-call-me")))
				.extracting(violation -> violation.getPropertyPath().toString())
				.contains("studioPhone");
	}

	private static RegisterRequestDto request(
			RoleEnum role,
			String studioName,
			String studioAddress,
			String studioPhone
	) {
		return new RegisterRequestDto(
				"studio-owner",
				"studio@example.com",
				"password123",
				"password123",
				role,
				null,
				null,
				null,
				"city-id",
				"district-id",
				"neighborhood-id",
				studioName,
				studioAddress,
				studioPhone
		);
	}
}

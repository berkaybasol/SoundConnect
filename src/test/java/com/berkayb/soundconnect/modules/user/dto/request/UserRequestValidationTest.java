package com.berkayb.soundconnect.modules.user.dto.request;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class UserRequestValidationTest {

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
	void adminSaveRequiresAValidBoundedIdentityAndPassword() {
		UserSaveRequestDto invalid = new UserSaveRequestDto(
				" ", "not-an-email", null, "short"
		);

		assertThat(validator.validate(invalid))
				.extracting(violation -> violation.getPropertyPath().toString())
				.contains("username", "email", "roleId", "password");
	}

	@Test
	void optionalUpdateFieldsAreValidatedWhenPresent() {
		UserUpdateRequestDto invalid = new UserUpdateRequestDto(
				"x", "short", "not-an-email", UUID.randomUUID()
		);

		assertThat(validator.validate(invalid))
				.extracting(violation -> violation.getPropertyPath().toString())
				.contains("username", "password", "email");
		assertThat(validator.validate(new UserUpdateRequestDto(null, null, null, null))).isEmpty();
	}

	@Test
	void adminCreateAndUpdateUseTheCanonicalBcryptCompatiblePasswordBounds() {
		UUID roleId = UUID.randomUUID();

		assertThat(validator.validate(new UserSaveRequestDto(
				"listener", "listener@example.com", roleId, "p".repeat(8)
		))).isEmpty();
		assertThat(validator.validate(new UserSaveRequestDto(
				"listener", "listener@example.com", roleId, "p".repeat(72)
		))).isEmpty();
		assertThat(validator.validate(new UserUpdateRequestDto(
				null, "p".repeat(8), null, null
		))).isEmpty();
		assertThat(validator.validate(new UserUpdateRequestDto(
				null, "p".repeat(72), null, null
		))).isEmpty();

		assertPasswordViolation(new UserSaveRequestDto(
				"listener", "listener@example.com", roleId, "p".repeat(7)
		));
		assertPasswordViolation(new UserSaveRequestDto(
				"listener", "listener@example.com", roleId, "p".repeat(73)
		));
		assertPasswordViolation(new UserUpdateRequestDto(
				null, "p".repeat(7), null, null
		));
		assertPasswordViolation(new UserUpdateRequestDto(
				null, "p".repeat(73), null, null
		));
	}

	@Test
	void adminCreateAndUpdateEnforceTheBcryptLimitInUtf8Bytes() {
		UUID roleId = UUID.randomUUID();
		String exactlySeventyTwoBytes = "ş".repeat(36);
		String overSeventyTwoBytes = "ş".repeat(37);

		assertThat(validator.validate(new UserSaveRequestDto(
				"listener", "listener@example.com", roleId, exactlySeventyTwoBytes
		))).isEmpty();
		assertThat(validator.validate(new UserUpdateRequestDto(
				null, exactlySeventyTwoBytes, null, null
		))).isEmpty();

		assertPasswordViolation(new UserSaveRequestDto(
				"listener", "listener@example.com", roleId, overSeventyTwoBytes
		));
		assertPasswordViolation(new UserUpdateRequestDto(
				null, overSeventyTwoBytes, null, null
		));
	}

	private static void assertPasswordViolation(Object request) {
		assertThat(validator.validate(request))
				.extracting(violation -> violation.getPropertyPath().toString())
				.contains("password");
	}
}

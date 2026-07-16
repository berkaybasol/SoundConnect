package com.berkayb.soundconnect.auth.dto.request;

import com.berkayb.soundconnect.modules.role.enums.RoleEnum;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordRequestValidationTest {

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
	void loginAcceptsLegacyPasswordsAndTheBcryptAsciiBoundary() {
		assertThat(validator.validate(new LoginRequestDto("listener", "old"))).isEmpty();
		assertThat(validator.validate(new LoginRequestDto("listener", "p".repeat(72)))).isEmpty();
	}

	@Test
	void loginRejectsBlankAndOverlongAsciiPasswords() {
		assertPasswordViolation(new LoginRequestDto("listener", " "));
		assertPasswordViolation(new LoginRequestDto("listener", "p".repeat(73)));
	}

	@Test
	void loginEnforcesTheBcryptLimitInUtf8Bytes() {
		assertThat(validator.validate(new LoginRequestDto("listener", "ş".repeat(36)))).isEmpty();
		assertPasswordViolation(new LoginRequestDto("listener", "ş".repeat(37)));
	}

	@Test
	void registrationKeepsTheMinimumAndEnforcesTheAsciiMaximum() {
		assertThat(validator.validate(registerRequest("p".repeat(8)))).isEmpty();
		assertThat(validator.validate(registerRequest("p".repeat(72)))).isEmpty();
		assertPasswordViolation(registerRequest("p".repeat(7)));
		assertPasswordViolation(registerRequest("p".repeat(73)));
	}

	@Test
	void registrationEnforcesTheBcryptLimitInUtf8Bytes() {
		assertThat(validator.validate(registerRequest("ş".repeat(36)))).isEmpty();
		assertPasswordViolation(registerRequest("ş".repeat(37)));
	}

	private static RegisterRequestDto registerRequest(String password) {
		return new RegisterRequestDto(
				"listener",
				"listener@example.com",
				password,
				password,
				RoleEnum.ROLE_LISTENER,
				null,
				null,
				null,
				null,
				null,
				null
		);
	}

	private static void assertPasswordViolation(Object request) {
		assertThat(validator.validate(request))
				.extracting(violation -> violation.getPropertyPath().toString())
				.contains("password");
	}
}

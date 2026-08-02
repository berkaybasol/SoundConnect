package com.berkayb.soundconnect.auth.validation;

import com.berkayb.soundconnect.auth.dto.request.LoginRequestDto;
import com.berkayb.soundconnect.auth.dto.request.RegisterRequestDto;
import com.berkayb.soundconnect.modules.role.enums.RoleEnum;
import com.berkayb.soundconnect.modules.user.dto.request.UserSaveRequestDto;
import com.berkayb.soundconnect.modules.user.dto.request.UserUpdateRequestDto;
import com.berkayb.soundconnect.modules.user.dto.request.UsernameChangeRequestDto;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class UsernameRequestValidationTest {
	private static final ValidatorFactory VALIDATOR_FACTORY = Validation.buildDefaultValidatorFactory();
	private static final Validator VALIDATOR = VALIDATOR_FACTORY.getValidator();

	@AfterAll
	static void closeValidatorFactory() {
		VALIDATOR_FACTORY.close();
	}

	@Test
	void usernameBearingRequestsExposeTheCanonicalValue() {
		assertThat(new LoginRequestDto(" BeRKay ", "secret").username()).isEqualTo("berkay");
		assertThat(register(" BeRKay ").username()).isEqualTo("berkay");
		assertThat(new UserSaveRequestDto(
				" BeRKay ", "user@example.com", UUID.randomUUID(), "password123"
		).username()).isEqualTo("berkay");
		assertThat(new UserUpdateRequestDto(" BeRKay ", null, null, null).username()).isEqualTo("berkay");
		assertThat(new UsernameChangeRequestDto(" BeRKay ").username()).isEqualTo("berkay");
	}

	@Test
	void beanValidationMeasuresCanonicalLengthForEveryUsernameWriteRequest() {
		assertUsernameViolation(register(" a "));
		assertUsernameViolation(new UserSaveRequestDto(
				" a ", "user@example.com", UUID.randomUUID(), "password123"
		));
		assertUsernameViolation(new UserUpdateRequestDto(" a ", null, null, null));
		assertUsernameViolation(new UsernameChangeRequestDto(" a "));
	}

	@Test
	void loginValidationAlsoMeasuresCanonicalLength() {
		assertUsernameViolation(new LoginRequestDto(" a ", "secret"));
	}

	private static RegisterRequestDto register(String username) {
		return new RegisterRequestDto(
				username,
				"user@example.com",
				"password123",
				"password123",
				RoleEnum.ROLE_LISTENER,
				null, null, null, null, null, null
		);
	}

	private static void assertUsernameViolation(Object request) {
		assertThat(VALIDATOR.validate(request))
				.anySatisfy(violation ->
						assertThat(violation.getPropertyPath().toString()).isEqualTo("username"));
	}
}

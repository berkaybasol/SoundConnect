package com.berkayb.soundconnect.tools.simulation;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SimulationPropertiesTest {

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
	void acceptsExplicitSafeConfiguration() {
		assertThat(validator.validate(safeProperties())).isEmpty();
	}

	@Test
	void rejectsAccountCapsAboveFifty() {
		SimulationProperties properties = safeProperties();
		properties.setMaxAccounts(51);

		assertThat(validator.validate(properties))
				.anyMatch(violation -> violation.getPropertyPath().toString().equals("maxAccounts"));
	}

	@Test
	void rejectsDeliverableEmailSuffixes() {
		SimulationProperties properties = safeProperties();
		properties.setEmailSuffix("@example.com");

		assertThat(validator.validate(properties))
				.anyMatch(violation -> violation.getPropertyPath().toString().equals("emailSuffix"));
	}

	@Test
	void requiresExplicitDestructiveResetAcknowledgement() {
		SimulationProperties properties = safeProperties();
		properties.setDestructiveResetAcknowledged(false);

		assertThat(validator.validate(properties))
				.anyMatch(violation -> violation.getPropertyPath().toString()
						.equals("destructiveResetContractSatisfied"));
	}

	@Test
	void nonDestructiveModesDoNotRequireResetAcknowledgement() {
		SimulationProperties properties = safeProperties();
		properties.setMode(SimulationMode.RESUME);
		properties.setDestructiveResetAcknowledged(false);

		assertThat(validator.validate(properties)).isEmpty();
	}

	@Test
	void pausedModeDoesNotRequireACommonPassword() {
		SimulationProperties properties = safeProperties();
		properties.setMode(SimulationMode.PAUSE);
		properties.setDestructiveResetAcknowledged(false);
		properties.setCommonPassword(null);

		assertThat(validator.validate(properties)).isEmpty();
	}

	@Test
	void rejectsInvalidExpectedDatabaseNames() {
		SimulationProperties properties = safeProperties();
		properties.setExpectedPostgresqlDatabase("");

		assertThat(validator.validate(properties))
				.anyMatch(violation -> violation.getPropertyPath().toString()
						.equals("expectedPostgresqlDatabase"));

		properties.setExpectedPostgresqlDatabase("soundconnect/other");

		assertThat(validator.validate(properties))
				.anyMatch(violation -> violation.getPropertyPath().toString()
						.equals("expectedPostgresqlDatabase"));
	}

	@Test
	void requiresAConfiguredCommonPasswordWithinBcryptByteLimit() {
		SimulationProperties properties = safeProperties();
		properties.setCommonPassword(" ");
		assertThat(validator.validate(properties))
				.anyMatch(violation -> violation.getPropertyPath().toString()
						.equals("commonPasswordContractSatisfied"));

		properties.setCommonPassword("ş".repeat(40));
		assertThat(validator.validate(properties))
				.anyMatch(violation -> violation.getPropertyPath().toString().equals("commonPassword"));
	}

	static SimulationProperties safeProperties() {
		SimulationProperties properties = new SimulationProperties();
		properties.setEnabled(true);
		properties.setMode(SimulationMode.FRESH);
		properties.setMaxAccounts(50);
		properties.setEmailSuffix("@simulation.soundconnect.invalid");
		properties.setExpectedPostgresqlDatabase("soundconnect");
		properties.setDestructiveResetAcknowledged(true);
		properties.setCommonPassword("local-only-password");
		properties.setReportDirectory(Path.of("build", "reports", "simulation"));
		properties.setMailCaptureCapacity(100);
		return properties;
	}
}

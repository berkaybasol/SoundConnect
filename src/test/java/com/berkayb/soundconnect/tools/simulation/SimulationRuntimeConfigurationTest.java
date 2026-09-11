package com.berkayb.soundconnect.tools.simulation;

import com.berkayb.soundconnect.auth.otp.service.OtpMailService;
import com.berkayb.soundconnect.shared.mail.adapter.MailSenderClient;
import com.berkayb.soundconnect.tools.simulation.runtime.SimulationMailCaptureClient;
import com.berkayb.soundconnect.tools.simulation.runtime.SimulationOtpCaptureMailService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

class SimulationRuntimeConfigurationTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
			.withUserConfiguration(SimulationRuntimeConfiguration.class, CompetingMailConfiguration.class);

	@Test
	void defaultFalseFlagLeavesSimulationAdaptersAbsentWithoutTouchingDatasource() {
		contextRunner
				.withInitializer(context -> context.getEnvironment()
						.setActiveProfiles("local", "simulation"))
				.run(context -> {
					assertThat(context).hasNotFailed();
					assertThat(context).doesNotHaveBean(SimulationRuntimeGuard.class);
					assertThat(context).doesNotHaveBean(SimulationOtpCaptureMailService.class);
					assertThat(context).doesNotHaveBean(SimulationMailCaptureClient.class);
				});
	}

	@Test
	void explicitSafeActivationSelectsPrimaryNoSendAdapters() throws Exception {
		DataSource dataSource = SimulationRuntimeGuardTest.dataSource(
				"PostgreSQL", "jdbc:postgresql://localhost:5432/soundconnect");

		contextRunner
				.withBean(DataSource.class, () -> dataSource)
				.withInitializer(context -> context.getEnvironment()
						.setActiveProfiles("local", "simulation"))
				.withPropertyValues(safePropertyValues())
				.run(context -> {
					assertThat(context).hasNotFailed();
					assertThat(context.getBean(OtpMailService.class))
							.isInstanceOf(SimulationOtpCaptureMailService.class);
					assertThat(context.getBean(MailSenderClient.class))
							.isInstanceOf(SimulationMailCaptureClient.class);
					assertThat(context.getBeansOfType(OtpMailService.class)).hasSize(2);
					assertThat(context.getBeansOfType(MailSenderClient.class)).hasSize(2);
				});
	}

	@Test
	void enabledFlagWithoutLocalProfileFailsContextStartup() throws Exception {
		DataSource dataSource = SimulationRuntimeGuardTest.dataSource(
				"PostgreSQL", "jdbc:postgresql://localhost:5432/soundconnect");

		contextRunner
				.withBean(DataSource.class, () -> dataSource)
				.withInitializer(context -> context.getEnvironment().setActiveProfiles("simulation"))
				.withPropertyValues(safePropertyValues())
				.run(context -> {
					assertThat(context).hasFailed();
					assertThat(context.getStartupFailure())
							.hasRootCauseMessage("Local simulation requires both local and simulation profiles");
				});
	}

	@Test
	void enabledFlagWithoutDestructiveAcknowledgementFailsBinding() throws Exception {
		DataSource dataSource = SimulationRuntimeGuardTest.dataSource(
				"PostgreSQL", "jdbc:postgresql://localhost:5432/soundconnect");

		contextRunner
				.withBean(DataSource.class, () -> dataSource)
				.withInitializer(context -> context.getEnvironment()
						.setActiveProfiles("local", "simulation"))
				.withPropertyValues(
						"app.simulation.enabled=true",
						"app.simulation.mode=FRESH",
						"app.simulation.max-accounts=50",
						"app.simulation.email-suffix=@simulation.soundconnect.invalid",
						"app.simulation.expected-postgresql-database=soundconnect",
						"app.simulation.destructive-reset-acknowledged=false",
						"app.simulation.common-password=local-only-password",
						"app.simulation.report-directory=build/reports/simulation")
				.run(context -> assertThat(context).hasFailed());
	}

	private static String[] safePropertyValues() {
		return new String[]{
				"app.simulation.enabled=true",
				"app.simulation.mode=FRESH",
				"app.simulation.max-accounts=50",
				"app.simulation.email-suffix=@simulation.soundconnect.invalid",
				"app.simulation.expected-postgresql-database=soundconnect",
				"app.simulation.destructive-reset-acknowledged=true",
				"app.simulation.common-password=local-only-password",
				"app.simulation.report-directory=build/reports/simulation",
				"app.simulation.mail-capture-capacity=100"
		};
	}

	@Configuration(proxyBeanMethods = false)
	static class CompetingMailConfiguration {
		@Bean
		OtpMailService ordinaryOtpMailService() {
			return (to, code) -> { };
		}

		@Bean
		MailSenderClient ordinaryMailSenderClient() {
			return (to, subject, textBody, htmlBody) -> { };
		}
	}
}

package com.berkayb.soundconnect.tools.simulation;

import com.berkayb.soundconnect.tools.simulation.runtime.SimulationMailCaptureClient;
import com.berkayb.soundconnect.tools.simulation.runtime.SimulationOtpCaptureMailService;
import com.berkayb.soundconnect.tools.simulation.runtime.SimulationRecipientPolicy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;

import javax.sql.DataSource;
import java.time.Clock;

/**
 * Loads only on an explicit flag. The guard then requires both local and
 * simulation profiles, preventing a mis-profiled flag from silently enabling
 * either capture adapter.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SimulationProperties.class)
@ConditionalOnProperty(prefix = "app.simulation", name = "enabled", havingValue = "true", matchIfMissing = false)
public class SimulationRuntimeConfiguration {

	@Bean
	SimulationRuntimeGuard simulationRuntimeGuard(
			Environment environment,
			SimulationProperties properties,
			DataSource dataSource
	) {
		return new SimulationRuntimeGuard(environment, properties, dataSource);
	}

	@Bean
	SimulationRecipientPolicy simulationRecipientPolicy(SimulationProperties properties) {
		return new SimulationRecipientPolicy(properties);
	}

	@Bean
	@Primary
	SimulationOtpCaptureMailService simulationOtpMailService(
			SimulationRuntimeGuard runtimeGuard,
			SimulationRecipientPolicy recipientPolicy,
			SimulationProperties properties
	) {
		return new SimulationOtpCaptureMailService(runtimeGuard, recipientPolicy, properties);
	}

	@Bean
	@Primary
	SimulationMailCaptureClient simulationMailSenderClient(
			SimulationRuntimeGuard runtimeGuard,
			SimulationRecipientPolicy recipientPolicy,
			SimulationProperties properties
	) {
		return new SimulationMailCaptureClient(
				runtimeGuard,
				recipientPolicy,
				properties,
				Clock.systemUTC()
		);
	}
}

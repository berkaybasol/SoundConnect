package com.berkayb.soundconnect.tools.simulation;

import com.berkayb.soundconnect.tools.simulation.report.SimulationRunReportWriter;
import com.berkayb.soundconnect.tools.simulation.behavior.SimulationBehaviorProperties;
import com.berkayb.soundconnect.tools.simulation.seed.follow.SimulationFollowPlanner;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifestLoader;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import java.time.Clock;

/** Stateless collaborators for the guarded local world materializer. */
@Configuration(proxyBeanMethods = false)
@Profile("local & simulation")
@ConditionalOnProperty(prefix = "app.simulation", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(SimulationBehaviorProperties.class)
public class SimulationSeedInfrastructureConfiguration {

	@Bean
	SimulationWorldManifestLoader simulationWorldManifestLoader(ObjectMapper objectMapper) {
		return new SimulationWorldManifestLoader(objectMapper);
	}

	@Bean
	SimulationFollowPlanner simulationFollowPlanner() {
		return new SimulationFollowPlanner();
	}

	@Bean
	SimulationRunReportWriter simulationRunReportWriter(ObjectMapper objectMapper) {
		return new SimulationRunReportWriter(objectMapper);
	}

	@Bean
	Clock simulationClock() {
		return Clock.systemUTC();
	}
}

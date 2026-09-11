package com.berkayb.soundconnect.tools.simulation.seed.media;

import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.service.BandService;
import com.berkayb.soundconnect.modules.profile.shared.media.service.ProfileMediaService;
import com.berkayb.soundconnect.modules.track.service.TrackService;
import com.berkayb.soundconnect.tools.simulation.SimulationProperties;
import com.berkayb.soundconnect.tools.simulation.SimulationRuntimeGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

import java.time.Clock;
import java.time.Duration;

/** Wires the media seam only inside the explicitly guarded simulation runtime. */
@Configuration(proxyBeanMethods = false)
@Profile("local & simulation")
@ConditionalOnProperty(
		prefix = "app.simulation",
		name = "enabled",
		havingValue = "true",
		matchIfMissing = false
)
@EnableConfigurationProperties(SimulationMediaProperties.class)
public class SimulationMediaSeedConfiguration {

	@Bean
	@Primary
	SimulationFileStorageClient simulationFileStorageClient(
			SimulationRuntimeGuard runtimeGuard,
			SimulationProperties properties,
			SimulationMediaProperties mediaProperties,
			Environment environment
	) {
		Duration uploadTtl = Duration.ofSeconds(environment.getProperty(
				"cloud.storage.presign.uploadExpirySeconds", Integer.class, 900));
		Duration downloadTtl = Duration.ofSeconds(environment.getProperty(
				"cloud.storage.presign.downloadExpirySeconds", Integer.class, 300));
		return new SimulationFileStorageClient(
				runtimeGuard,
				properties.getReportDirectory(),
				Clock.systemUTC(),
				uploadTtl,
				downloadTtl,
				mediaProperties.normalizedPublicBaseUrl(),
				mediaProperties.getMaxResponseBytes(),
				mediaProperties.getMaxPublicCapabilities(),
				properties.getMode());
	}

	@Bean
	SimulationMediaCheckpointStore simulationMediaCheckpointStore(
			SimulationRuntimeGuard runtimeGuard,
			SimulationProperties properties,
			ObjectMapper objectMapper
	) {
		return new SimulationMediaCheckpointStore(
				runtimeGuard, properties, objectMapper, Clock.systemUTC());
	}

	@Bean
	SimulationMediaSeeder simulationMediaSeeder(
			SimulationRuntimeGuard runtimeGuard,
			SimulationProperties properties,
			SimulationMediaCheckpointStore checkpointStore,
			MediaAssetService mediaAssetService,
			SimulationFileStorageClient storageClient,
			BandService bandService,
			TrackService trackService,
			ProfileMediaService profileMediaService
	) {
		return new SimulationMediaSeeder(
				runtimeGuard,
				properties,
				checkpointStore,
				mediaAssetService,
				storageClient,
				bandService,
				trackService,
				profileMediaService);
	}
}

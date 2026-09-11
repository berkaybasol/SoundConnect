package com.berkayb.soundconnect.tools.simulation.seed.media;

import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.media.storage.StorageClient;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.service.BandService;
import com.berkayb.soundconnect.modules.profile.shared.media.service.ProfileMediaService;
import com.berkayb.soundconnect.modules.track.service.TrackService;
import com.berkayb.soundconnect.tools.simulation.SimulationProperties;
import com.berkayb.soundconnect.tools.simulation.SimulationRuntimeGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SimulationMediaSeedConfigurationTest {

	@TempDir
	Path temporaryDirectory;

	@Test
	void adapterAndSeederLoadOnlyWithBothProfilesAndExplicitFlag() {
		context("local,simulation", true).run(context -> {
			assertThat(context).hasSingleBean(SimulationFileStorageClient.class);
			assertThat(context).hasSingleBean(SimulationMediaSeeder.class);
			assertThat(context).hasSingleBean(SimulationMediaCheckpointStore.class);
			assertThat(context).hasSingleBean(SimulationMediaReadController.class);
			assertThat(context.getBean(StorageClient.class))
					.isInstanceOf(SimulationFileStorageClient.class);
			assertThat(context.getBean(SimulationPresignedUploadSink.class))
					.isSameAs(context.getBean(SimulationFileStorageClient.class));
		});

		context("local", true).run(context -> {
			assertThat(context).doesNotHaveBean(SimulationFileStorageClient.class);
			assertThat(context).doesNotHaveBean(SimulationMediaSeeder.class);
			assertThat(context).doesNotHaveBean(SimulationMediaReadController.class);
		});

		context("local,simulation", false).run(context -> {
			assertThat(context).doesNotHaveBean(SimulationFileStorageClient.class);
			assertThat(context).doesNotHaveBean(SimulationMediaSeeder.class);
			assertThat(context).doesNotHaveBean(SimulationMediaReadController.class);
		});
	}

	@Test
	void unsafePublicOriginAndUnboundedResponseConfigurationFailClosed() {
		context("local,simulation", true)
				.withPropertyValues("app.simulation.media.public-base-url=file:///tmp/media")
				.run(context -> assertThat(context).hasFailed());

		context("local,simulation", true)
				.withPropertyValues("app.simulation.media.max-response-bytes=67108865")
				.run(context -> assertThat(context).hasFailed());
	}

	private ApplicationContextRunner context(String profiles, boolean enabled) {
		return new ApplicationContextRunner()
				.withUserConfiguration(
						SimulationMediaSeedConfiguration.class,
						SimulationMediaReadController.class,
						TestBeans.class)
				.withPropertyValues(
						"spring.profiles.active=" + profiles,
						"app.simulation.enabled=" + enabled,
						"test.simulation.report-directory="
								+ temporaryDirectory.toAbsolutePath().toString().replace('\\', '/'));
	}

	@Configuration(proxyBeanMethods = false)
	static class TestBeans {

		@Bean
		SimulationRuntimeGuard simulationRuntimeGuard() {
			return mock(SimulationRuntimeGuard.class);
		}

		@Bean
		SimulationProperties simulationProperties(org.springframework.core.env.Environment environment) {
			SimulationProperties properties = new SimulationProperties();
			properties.setEnabled(true);
			properties.setExpectedPostgresqlDatabase("soundconnect");
			properties.setCommonPassword("simulation-password");
			properties.setReportDirectory(Path.of(environment.getRequiredProperty(
					"test.simulation.report-directory")));
			return properties;
		}

		@Bean
		ObjectMapper objectMapper() {
			return new ObjectMapper().findAndRegisterModules();
		}

		@Bean
		MediaAssetService mediaAssetService() {
			return mock(MediaAssetService.class);
		}

		@Bean
		TrackService trackService() {
			return mock(TrackService.class);
		}

		@Bean
		BandService bandService() {
			return mock(BandService.class);
		}

		@Bean
		ProfileMediaService profileMediaService() {
			return mock(ProfileMediaService.class);
		}
	}
}

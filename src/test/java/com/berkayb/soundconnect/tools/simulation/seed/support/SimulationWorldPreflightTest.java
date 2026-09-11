package com.berkayb.soundconnect.tools.simulation.seed.support;

import com.berkayb.soundconnect.modules.instrument.repository.InstrumentRepository;
import com.berkayb.soundconnect.modules.instrument.entity.Instrument;
import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.location.entity.District;
import com.berkayb.soundconnect.modules.location.entity.Neighborhood;
import com.berkayb.soundconnect.modules.role.entity.Role;
import com.berkayb.soundconnect.modules.role.repository.RoleRepository;
import com.berkayb.soundconnect.tools.simulation.SimulationProperties;
import com.berkayb.soundconnect.tools.simulation.SimulationRuntimeGuard;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifestLoader;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SimulationWorldPreflightTest {

	@TempDir
	Path reports;

	@Test
	void rejectsManifestBeforeDependencyReadsWhenAccountCapIsTooLow() {
		SimulationProperties properties = new SimulationProperties();
		properties.setMaxAccounts(49);
		properties.setEmailSuffix("@soundconnect.invalid");
		properties.setReportDirectory(reports);
		SimulationWorldPreflight preflight = new SimulationWorldPreflight(
				mock(SimulationRuntimeGuard.class), properties,
				mock(SimulationLocationResolver.class), mock(InstrumentRepository.class),
				mock(RoleRepository.class), mock(RedisConnectionFactory.class));
		SimulationWorldManifest manifest = new SimulationWorldManifestLoader(new ObjectMapper()).loadDefault();

		assertThatThrownBy(() -> preflight.verify(manifest))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("account cap");
	}

	@Test
	void rejectsCaptureSuffixMismatchBeforeResetCanRun() {
		SimulationProperties properties = new SimulationProperties();
		properties.setMaxAccounts(50);
		properties.setEmailSuffix("@another.invalid");
		properties.setReportDirectory(reports);
		RedisConnectionFactory redis = mock(RedisConnectionFactory.class);
		RedisConnection connection = mock(RedisConnection.class);
		SimulationWorldPreflight preflight = new SimulationWorldPreflight(
				mock(SimulationRuntimeGuard.class), properties,
				mock(SimulationLocationResolver.class), mock(InstrumentRepository.class),
				mock(RoleRepository.class), redis);

		assertThatThrownBy(() -> preflight.verify(
				new SimulationWorldManifestLoader(new ObjectMapper()).loadDefault()))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("capture suffix");
	}

	@Test
	void resolvesEveryCatalogDependencyAfterInfrastructureHealthChecks() {
		SimulationProperties properties = new SimulationProperties();
		properties.setMaxAccounts(50);
		properties.setEmailSuffix("@soundconnect.invalid");
		properties.setReportDirectory(reports);
		SimulationRuntimeGuard guard = mock(SimulationRuntimeGuard.class);
		SimulationLocationResolver locations = mock(SimulationLocationResolver.class);
		InstrumentRepository instruments = mock(InstrumentRepository.class);
		RoleRepository roles = mock(RoleRepository.class);
		RedisConnectionFactory redis = mock(RedisConnectionFactory.class);
		RedisConnection connection = mock(RedisConnection.class);

		City city = City.builder().id(UUID.randomUUID()).name("İstanbul").build();
		District district = District.builder().id(UUID.randomUUID()).name("Kadıköy").city(city).build();
		Neighborhood neighborhood = Neighborhood.builder().id(UUID.randomUUID())
				.name("Caferağa").district(district).build();
		when(locations.resolve(any())).thenReturn(
				new SimulationResolvedLocation(city, district, neighborhood, null));
		when(instruments.findByNameIgnoreCase(anyString())).thenAnswer(invocation -> Optional.of(
				Instrument.builder().name(invocation.getArgument(0)).build()));
		when(roles.findByName(anyString())).thenAnswer(invocation -> Optional.of(
				Role.builder().id(UUID.randomUUID()).name(invocation.getArgument(0)).build()));
		when(redis.getConnection()).thenReturn(connection);
		when(connection.ping()).thenReturn("PONG");
		SimulationWorldManifest manifest = new SimulationWorldManifestLoader(new ObjectMapper()).loadDefault();

		SimulationPreflightResult result = new SimulationWorldPreflight(
				guard, properties, locations, instruments, roles, redis).verify(manifest);

		assertThat(result.locationsByAccountKey()).hasSize(50);
		assertThat(result.instrumentsByName()).isNotEmpty();
	}
}

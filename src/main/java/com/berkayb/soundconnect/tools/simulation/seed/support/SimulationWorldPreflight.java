package com.berkayb.soundconnect.tools.simulation.seed.support;

import com.berkayb.soundconnect.modules.instrument.entity.Instrument;
import com.berkayb.soundconnect.modules.instrument.repository.InstrumentRepository;
import com.berkayb.soundconnect.modules.role.enums.RoleEnum;
import com.berkayb.soundconnect.modules.role.repository.RoleRepository;
import com.berkayb.soundconnect.tools.simulation.SimulationProperties;
import com.berkayb.soundconnect.tools.simulation.SimulationRuntimeGuard;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Proves every immutable dependency required by account materialization before FRESH may erase
 * disposable application data.
 */
@Component
@Profile("local & simulation")
@ConditionalOnProperty(prefix = "app.simulation", name = "enabled", havingValue = "true")
public final class SimulationWorldPreflight {

	private final SimulationRuntimeGuard runtimeGuard;
	private final SimulationProperties properties;
	private final SimulationLocationResolver locationResolver;
	private final InstrumentRepository instrumentRepository;
	private final RoleRepository roleRepository;
	private final RedisConnectionFactory redisConnectionFactory;

	public SimulationWorldPreflight(
			SimulationRuntimeGuard runtimeGuard,
			SimulationProperties properties,
			SimulationLocationResolver locationResolver,
			InstrumentRepository instrumentRepository,
			RoleRepository roleRepository,
			RedisConnectionFactory redisConnectionFactory
	) {
		this.runtimeGuard = Objects.requireNonNull(runtimeGuard, "runtimeGuard");
		this.properties = Objects.requireNonNull(properties, "properties");
		this.locationResolver = Objects.requireNonNull(locationResolver, "locationResolver");
		this.instrumentRepository = Objects.requireNonNull(instrumentRepository, "instrumentRepository");
		this.roleRepository = Objects.requireNonNull(roleRepository, "roleRepository");
		this.redisConnectionFactory = Objects.requireNonNull(redisConnectionFactory, "redisConnectionFactory");
	}

	public SimulationPreflightResult verify(SimulationWorldManifest manifest) {
		runtimeGuard.assertRuntimeAllowed();
		Objects.requireNonNull(manifest, "manifest");
		if (manifest.accounts().size() > properties.getMaxAccounts()) {
			throw new IllegalStateException("Simulation manifest exceeds configured account cap");
		}
		String suffix = properties.getEmailSuffix().toLowerCase(Locale.ROOT);
		for (SimulationWorldManifest.Account account : manifest.accounts()) {
			if (account.email() == null || !account.email().toLowerCase(Locale.ROOT).endsWith(suffix)) {
				throw new IllegalStateException(
						"Simulation account email is outside the configured capture suffix: " + account.key());
			}
		}

		verifyRoles();
		verifyRedis();
		verifyReportDirectory(properties.getReportDirectory());

		Map<String, SimulationResolvedLocation> locations = new LinkedHashMap<>();
		for (SimulationWorldManifest.Account account : manifest.accounts()) {
			SimulationResolvedLocation previous = locations.put(account.key(),
					locationResolver.resolve(account.location()));
			if (previous != null) {
				throw new IllegalStateException("Duplicate account key reached simulation preflight");
			}
		}

		Map<String, Instrument> instruments = new LinkedHashMap<>();
		manifest.accounts().stream()
				.flatMap(account -> account.instrumentNames().stream())
				.distinct()
				.sorted()
				.forEach(name -> instruments.put(name, instrumentRepository.findByNameIgnoreCase(name)
						.orElseThrow(() -> new IllegalStateException(
								"Simulation instrument is absent from the seeded catalog: " + name))));
		return new SimulationPreflightResult(locations, instruments);
	}

	private void verifyRoles() {
		for (RoleEnum role : EnumSet.of(
				RoleEnum.ROLE_ADMIN,
				RoleEnum.ROLE_MUSICIAN,
				RoleEnum.ROLE_LISTENER,
				RoleEnum.ROLE_VENUE,
				RoleEnum.ROLE_STUDIO)) {
			if (roleRepository.findByName(role.name()).isEmpty()) {
				throw new IllegalStateException("Simulation requires seeded role: " + role.name());
			}
		}
	}

	private void verifyRedis() {
		try (RedisConnection connection = redisConnectionFactory.getConnection()) {
			String response = connection.ping();
			if (!"PONG".equalsIgnoreCase(response)) {
				throw new IllegalStateException("Simulation Redis preflight did not receive PONG");
			}
		} catch (RuntimeException failure) {
			throw new IllegalStateException("Simulation requires reachable local Redis before reset", failure);
		}
	}

	private static void verifyReportDirectory(Path configured) {
		if (configured == null) throw new IllegalStateException("Simulation report directory is required");
		Path directory = configured.toAbsolutePath().normalize();
		try {
			Files.createDirectories(directory);
			Path probe = Files.createTempFile(directory, ".soundconnect-simulation-preflight-", ".tmp");
			Files.delete(probe);
		} catch (IOException failure) {
			throw new IllegalStateException("Simulation report directory is not writable: " + directory, failure);
		}
	}
}

package com.berkayb.soundconnect.tools.simulation;

import org.springframework.core.env.Environment;

import javax.sql.DataSource;
import java.net.URI;
import java.net.URISyntaxException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Issues the runtime safety boundary used by every simulation-only adapter.
 * Construction validates the datasource without mutating it; each adapter call
 * rechecks the profile/property boundary so the no-send seam cannot be reused as
 * an unguarded general-purpose component.
 */
public final class SimulationRuntimeGuard {

	private static final String LOCAL_PROFILE = "local";
	private static final String SIMULATION_PROFILE = "simulation";
	private static final String PRODUCTION_PROFILE = "prod";
	private static final String POSTGRESQL_PRODUCT = "PostgreSQL";

	private final Environment environment;
	private final SimulationProperties properties;
	private final VerifiedDatabase verifiedDatabase;

	public SimulationRuntimeGuard(
			Environment environment,
			SimulationProperties properties,
			DataSource dataSource
	) {
		this.environment = environment;
		this.properties = properties;
		assertActivationBoundary();
		this.verifiedDatabase = verifyDatabase(dataSource);
	}

	/**
	 * Required at every simulation-only side-effect boundary.
	 */
	public void assertRuntimeAllowed() {
		assertActivationBoundary();
		if (verifiedDatabase == null) {
			throw new IllegalStateException("Simulation datasource has not been verified");
		}
	}

	public String verifiedDatabaseName() {
		assertRuntimeAllowed();
		return verifiedDatabase.databaseName();
	}

	private void assertActivationBoundary() {
		Set<String> profiles = Arrays.stream(environment.getActiveProfiles())
				.map(value -> value.toLowerCase(Locale.ROOT))
				.collect(Collectors.toUnmodifiableSet());
		if (profiles.contains(PRODUCTION_PROFILE)) {
			throw new IllegalStateException("Local simulation must never run with the prod profile");
		}
		if (!profiles.contains(LOCAL_PROFILE) || !profiles.contains(SIMULATION_PROFILE)) {
			throw new IllegalStateException("Local simulation requires both local and simulation profiles");
		}
		if (!properties.isEnabled()) {
			throw new IllegalStateException("Local simulation requires app.simulation.enabled=true");
		}
		if (properties.getMode() == SimulationMode.FRESH
				&& !properties.isDestructiveResetAcknowledged()) {
			throw new IllegalStateException(
					"Local simulation FRESH mode requires explicit destructive reset acknowledgement");
		}
		if (properties.getMaxAccounts() < 1 || properties.getMaxAccounts() > 50) {
			throw new IllegalStateException("Local simulation account cap must be between 1 and 50");
		}
		assertLoopbackInfrastructure();
	}

	private void assertLoopbackInfrastructure() {
		String redisUrl = environment.getProperty("spring.data.redis.url");
		if (hasText(redisUrl)) {
			requireLoopbackEndpoint(redisUrl, "Redis URL");
		} else {
			requireLoopbackHost(
					environment.getProperty("spring.data.redis.host", "localhost"), "Redis host");
		}
		rejectAlternateRedisTopology("spring.data.redis.cluster.nodes", "Redis cluster");
		rejectAlternateRedisTopology("spring.data.redis.sentinel.nodes", "Redis sentinel");

		String rabbitAddresses = environment.getProperty("spring.rabbitmq.addresses");
		if (hasText(rabbitAddresses)) {
			for (String address : rabbitAddresses.split(",")) {
				requireLoopbackEndpoint(address, "RabbitMQ address");
			}
		} else {
			requireLoopbackHost(
					environment.getProperty("spring.rabbitmq.host", "localhost"), "RabbitMQ host");
		}
	}

	private void rejectAlternateRedisTopology(String property, String label) {
		if (hasText(environment.getProperty(property))) {
			throw new IllegalStateException("Local simulation does not allow " + label + " endpoints");
		}
	}

	private static void requireLoopbackEndpoint(String endpoint, String label) {
		String candidate = safeTrim(endpoint);
		if (!candidate.contains("://")) candidate = "tcp://" + candidate;
		try {
			URI uri = new URI(candidate);
			requireLoopbackHost(uri.getHost(), label);
		} catch (URISyntaxException exception) {
			throw new IllegalStateException("Local simulation " + label + " is invalid", exception);
		}
	}

	private static void requireLoopbackHost(String host, String label) {
		if (!isLoopbackHost(host)) {
			throw new IllegalStateException("Local simulation requires a loopback " + label);
		}
	}

	private VerifiedDatabase verifyDatabase(DataSource dataSource) {
		if (dataSource == null) {
			throw new IllegalStateException("Local simulation requires a datasource");
		}
		try (Connection connection = dataSource.getConnection()) {
			DatabaseMetaData metadata = connection.getMetaData();
			if (metadata == null) {
				throw new IllegalStateException("Local simulation could not inspect datasource metadata");
			}
			String product = metadata.getDatabaseProductName();
			if (!POSTGRESQL_PRODUCT.equalsIgnoreCase(safeTrim(product))) {
				throw new IllegalStateException("Local simulation requires PostgreSQL");
			}

			DatabaseAddress address = parsePostgresqlUrl(metadata.getURL());
			if (!isLoopbackHost(address.host())) {
				throw new IllegalStateException("Local simulation requires a loopback PostgreSQL host");
			}
			if (!properties.getExpectedPostgresqlDatabase().equals(address.databaseName())) {
				throw new IllegalStateException("Local simulation datasource database does not match the configured database");
			}
			return new VerifiedDatabase(address.databaseName());
		} catch (SQLException exception) {
			throw new IllegalStateException("Local simulation could not verify datasource metadata", exception);
		}
	}

	private static DatabaseAddress parsePostgresqlUrl(String jdbcUrl) {
		if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:postgresql://")) {
			throw new IllegalStateException("Local simulation requires a network PostgreSQL JDBC URL");
		}
		try {
			URI uri = new URI(jdbcUrl.substring("jdbc:".length()));
			if (!"postgresql".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
				throw new IllegalStateException("Local simulation PostgreSQL JDBC URL is invalid");
			}
			String path = uri.getPath();
			if (path == null || path.length() < 2 || path.indexOf('/', 1) >= 0) {
				throw new IllegalStateException("Local simulation PostgreSQL JDBC URL must name one database");
			}
			String database = path.substring(1);
			if (database.isBlank()) {
				throw new IllegalStateException("Local simulation PostgreSQL JDBC URL must name one database");
			}
			return new DatabaseAddress(uri.getHost(), database);
		} catch (URISyntaxException exception) {
			throw new IllegalStateException("Local simulation PostgreSQL JDBC URL is invalid", exception);
		}
	}

	private static boolean isLoopbackHost(String host) {
		String normalized = safeTrim(host).toLowerCase(Locale.ROOT);
		if ("localhost".equals(normalized)
				|| "::1".equals(normalized)
				|| "[::1]".equals(normalized)
				|| "0:0:0:0:0:0:0:1".equals(normalized)) {
			return true;
		}
		String[] octets = normalized.split("\\.", -1);
		if (octets.length != 4 || !"127".equals(octets[0])) {
			return false;
		}
		for (int index = 1; index < octets.length; index++) {
			try {
				int value = Integer.parseInt(octets[index]);
				if (value < 0 || value > 255) return false;
			} catch (NumberFormatException exception) {
				return false;
			}
		}
		return true;
	}

	private static String safeTrim(String value) {
		return value == null ? "" : value.trim();
	}

	private static boolean hasText(String value) {
		return value != null && !value.isBlank();
	}

	private record DatabaseAddress(String host, String databaseName) {
	}

	private record VerifiedDatabase(String databaseName) {
	}
}

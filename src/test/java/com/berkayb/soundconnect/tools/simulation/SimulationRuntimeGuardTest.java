package com.berkayb.soundconnect.tools.simulation;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SimulationRuntimeGuardTest {

	@Test
	void acceptsExplicitLocalSimulationAgainstExpectedLoopbackPostgresql() throws Exception {
		SimulationRuntimeGuard guard = new SimulationRuntimeGuard(
				environment("local", "simulation"),
				SimulationPropertiesTest.safeProperties(),
				dataSource("PostgreSQL", "jdbc:postgresql://127.0.0.1:5432/soundconnect")
		);

		assertThatCode(guard::assertRuntimeAllowed).doesNotThrowAnyException();
		assertThat(guard.verifiedDatabaseName()).isEqualTo("soundconnect");
	}

	@Test
	void rejectsProductionEvenWhenLocalAndSimulationAreAlsoPresent() throws Exception {
		assertThatThrownBy(() -> new SimulationRuntimeGuard(
				environment("local", "simulation", "prod"),
				SimulationPropertiesTest.safeProperties(),
				dataSource("PostgreSQL", "jdbc:postgresql://localhost:5432/soundconnect")
		))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("prod profile");
	}

	@Test
	void rejectsMissingLocalOrSimulationProfile() throws Exception {
		DataSource dataSource = dataSource(
				"PostgreSQL", "jdbc:postgresql://localhost:5432/soundconnect");

		assertThatThrownBy(() -> new SimulationRuntimeGuard(
				environment("simulation"), SimulationPropertiesTest.safeProperties(), dataSource))
				.hasMessageContaining("both local and simulation profiles");
		assertThatThrownBy(() -> new SimulationRuntimeGuard(
				environment("local"), SimulationPropertiesTest.safeProperties(), dataSource))
				.hasMessageContaining("both local and simulation profiles");
	}

	@Test
	void rejectsNonPostgresqlDatasource() throws Exception {
		assertThatThrownBy(() -> new SimulationRuntimeGuard(
				environment("local", "simulation"),
				SimulationPropertiesTest.safeProperties(),
				dataSource("H2", "jdbc:h2:mem:soundconnect")
		))
				.hasMessageContaining("requires PostgreSQL");
	}

	@Test
	void rejectsNonLoopbackPostgresqlHost() throws Exception {
		assertThatThrownBy(() -> new SimulationRuntimeGuard(
				environment("local", "simulation"),
				SimulationPropertiesTest.safeProperties(),
				dataSource("PostgreSQL", "jdbc:postgresql://db.internal:5432/soundconnect")
		))
				.hasMessageContaining("loopback PostgreSQL host");
	}

	@Test
	void rejectsRemoteRedisOrRabbitMqBeforeSimulationCanStart() throws Exception {
		MockEnvironment remoteRedis = environment("local", "simulation")
				.withProperty("spring.data.redis.host", "cache.internal");
		assertThatThrownBy(() -> new SimulationRuntimeGuard(
				remoteRedis, SimulationPropertiesTest.safeProperties(),
				dataSource("PostgreSQL", "jdbc:postgresql://localhost:5432/soundconnect")))
				.hasMessageContaining("loopback Redis host");

		MockEnvironment remoteRabbit = environment("local", "simulation")
				.withProperty("spring.rabbitmq.addresses", "mq.internal:5672");
		assertThatThrownBy(() -> new SimulationRuntimeGuard(
				remoteRabbit, SimulationPropertiesTest.safeProperties(),
				dataSource("PostgreSQL", "jdbc:postgresql://localhost:5432/soundconnect")))
				.hasMessageContaining("loopback RabbitMQ address");
	}

	@Test
	void rejectsUnexpectedDatabase() throws Exception {
		assertThatThrownBy(() -> new SimulationRuntimeGuard(
				environment("local", "simulation"),
				SimulationPropertiesTest.safeProperties(),
				dataSource("PostgreSQL", "jdbc:postgresql://localhost:5432/postgres")
		))
				.hasMessageContaining("does not match");
	}

	@Test
	void rejectsMetadataFailuresWithoutFallingOpen() throws Exception {
		DataSource dataSource = mock(DataSource.class);
		when(dataSource.getConnection()).thenThrow(new SQLException("unavailable"));

		assertThatThrownBy(() -> new SimulationRuntimeGuard(
				environment("local", "simulation"),
				SimulationPropertiesTest.safeProperties(),
				dataSource
		))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("could not verify datasource metadata");
	}

	@Test
	void runtimeBoundaryIsRecheckedAfterStartup() throws Exception {
		MockEnvironment environment = environment("local", "simulation");
		SimulationRuntimeGuard guard = new SimulationRuntimeGuard(
				environment,
				SimulationPropertiesTest.safeProperties(),
				dataSource("PostgreSQL", "jdbc:postgresql://localhost:5432/soundconnect")
		);

		environment.setActiveProfiles("simulation");

		assertThatThrownBy(guard::assertRuntimeAllowed)
				.hasMessageContaining("both local and simulation profiles");
	}

	static MockEnvironment environment(String... profiles) {
		MockEnvironment environment = new MockEnvironment();
		environment.setActiveProfiles(profiles);
		return environment;
	}

	static DataSource dataSource(String productName, String url) throws SQLException {
		DataSource dataSource = mock(DataSource.class);
		Connection connection = mock(Connection.class);
		DatabaseMetaData metadata = mock(DatabaseMetaData.class);
		when(dataSource.getConnection()).thenReturn(connection);
		when(connection.getMetaData()).thenReturn(metadata);
		when(metadata.getDatabaseProductName()).thenReturn(productName);
		when(metadata.getURL()).thenReturn(url);
		return dataSource;
	}
}

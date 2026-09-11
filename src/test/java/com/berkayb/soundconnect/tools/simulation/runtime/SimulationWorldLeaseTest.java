package com.berkayb.soundconnect.tools.simulation.runtime;

import com.berkayb.soundconnect.tools.simulation.SimulationRuntimeGuard;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SimulationWorldLeaseTest {

	@Test
	void holdsOnePostgresqlSessionUntilShutdown() throws Exception {
		SimulationRuntimeGuard guard = mock(SimulationRuntimeGuard.class);
		DataSource dataSource = mock(DataSource.class);
		Connection connection = mock(Connection.class);
		PreparedStatement acquire = statementReturning(true);
		PreparedStatement release = statementReturning(true);
		when(dataSource.getConnection()).thenReturn(connection);
		when(connection.prepareStatement(SimulationWorldLease.ACQUIRE_SQL)).thenReturn(acquire);
		when(connection.prepareStatement(SimulationWorldLease.RELEASE_SQL)).thenReturn(release);
		when(connection.isValid(2)).thenReturn(true);
		SimulationWorldLease lease = new SimulationWorldLease(guard, dataSource);

		lease.acquire();
		lease.assertHeld();
		lease.close();

		verify(acquire).setLong(1, SimulationWorldLease.LOCK_KEY);
		verify(release).setLong(1, SimulationWorldLease.LOCK_KEY);
		verify(connection).close();
	}

	@Test
	void rejectsASecondSimulationWriterAndReturnsItsConnection() throws Exception {
		SimulationRuntimeGuard guard = mock(SimulationRuntimeGuard.class);
		DataSource dataSource = mock(DataSource.class);
		Connection connection = mock(Connection.class);
		PreparedStatement unavailable = statementReturning(false);
		when(dataSource.getConnection()).thenReturn(connection);
		when(connection.prepareStatement(SimulationWorldLease.ACQUIRE_SQL))
				.thenReturn(unavailable);
		SimulationWorldLease lease = new SimulationWorldLease(guard, dataSource);

		assertThatThrownBy(lease::acquire)
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("already owns");
		verify(connection).close();
	}

	private static PreparedStatement statementReturning(boolean value) throws Exception {
		PreparedStatement statement = mock(PreparedStatement.class);
		ResultSet result = mock(ResultSet.class);
		when(statement.executeQuery()).thenReturn(result);
		when(result.next()).thenReturn(true);
		when(result.getBoolean(1)).thenReturn(value);
		return statement;
	}
}

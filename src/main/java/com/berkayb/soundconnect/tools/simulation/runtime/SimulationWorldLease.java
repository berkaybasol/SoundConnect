package com.berkayb.soundconnect.tools.simulation.runtime;

import com.berkayb.soundconnect.tools.simulation.SimulationRuntimeGuard;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;

/**
 * Process-lifetime PostgreSQL advisory lease for the single local simulation writer.
 *
 * <p>A JVM lock is insufficient when two IDE/API instances point at the same disposable
 * database. Retaining one session-level advisory lock prevents two bootstrap runners or two
 * population schedulers from advancing the same durable world concurrently.</p>
 */
@Component
@Profile("local & simulation")
@ConditionalOnProperty(prefix = "app.simulation", name = "enabled", havingValue = "true")
@Slf4j
public final class SimulationWorldLease implements AutoCloseable {

	static final long LOCK_KEY = 0x53434F4E4E454354L;
	static final String ACQUIRE_SQL = "SELECT pg_try_advisory_lock(?)";
	static final String RELEASE_SQL = "SELECT pg_advisory_unlock(?)";

	private final SimulationRuntimeGuard runtimeGuard;
	private final DataSource dataSource;
	private Connection leaseConnection;

	public SimulationWorldLease(SimulationRuntimeGuard runtimeGuard, DataSource dataSource) {
		this.runtimeGuard = Objects.requireNonNull(runtimeGuard, "runtimeGuard");
		this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
	}

	public synchronized void acquire() {
		runtimeGuard.assertRuntimeAllowed();
		if (leaseConnection != null) {
			assertHeld();
			return;
		}

		Connection candidate = null;
		try {
			candidate = dataSource.getConnection();
			if (!query(candidate, ACQUIRE_SQL)) {
				throw new IllegalStateException(
						"Another API process already owns the local simulation world lease");
			}
			leaseConnection = candidate;
		} catch (SQLException failure) {
			closeQuietly(candidate);
			throw new IllegalStateException("Local simulation world lease could not be acquired", failure);
		} catch (RuntimeException failure) {
			closeQuietly(candidate);
			throw failure;
		}
	}

	public synchronized void assertHeld() {
		runtimeGuard.assertRuntimeAllowed();
		try {
			if (leaseConnection == null || leaseConnection.isClosed() || !leaseConnection.isValid(2)) {
				throw new IllegalStateException("Local simulation world lease is not held");
			}
		} catch (SQLException failure) {
			throw new IllegalStateException("Local simulation world lease cannot be verified", failure);
		}
	}

	@Override
	@PreDestroy
	public synchronized void close() {
		Connection current = leaseConnection;
		leaseConnection = null;
		if (current == null) return;
		try {
			if (!current.isClosed() && !query(current, RELEASE_SQL)) {
				log.warn("Local simulation world lease was not owned during shutdown");
			}
		} catch (SQLException failure) {
			log.warn("Local simulation world lease release failed exceptionType={}",
					failure.getClass().getSimpleName());
		} finally {
			closeQuietly(current);
		}
	}

	private static boolean query(Connection connection, String sql) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.setLong(1, LOCK_KEY);
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					throw new SQLException("PostgreSQL advisory-lock query returned no result");
				}
				return result.getBoolean(1);
			}
		}
	}

	private static void closeQuietly(Connection connection) {
		if (connection == null) return;
		try {
			connection.close();
		} catch (SQLException ignored) {
			// Startup/release failure remains authoritative.
		}
	}
}

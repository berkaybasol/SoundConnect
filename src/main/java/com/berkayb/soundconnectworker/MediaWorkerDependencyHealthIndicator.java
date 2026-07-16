package com.berkayb.soundconnectworker;

import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

import javax.sql.DataSource;
import java.sql.Connection;

/** Direct DB and Rabbit readiness probe without adding HTTP or publish beans. */
public class MediaWorkerDependencyHealthIndicator implements HealthIndicator {

	private final DataSource dataSource;
	private final ConnectionFactory rabbitConnectionFactory;

	public MediaWorkerDependencyHealthIndicator(
			DataSource dataSource,
			ConnectionFactory rabbitConnectionFactory
	) {
		this.dataSource = dataSource;
		this.rabbitConnectionFactory = rabbitConnectionFactory;
	}

	@Override
	public Health health() {
		try (Connection database = dataSource.getConnection()) {
			if (!database.isValid(2)) {
				return Health.down().withDetail("database", "unavailable").build();
			}
			org.springframework.amqp.rabbit.connection.Connection rabbit = null;
			try {
				rabbit = rabbitConnectionFactory.createConnection();
				if (rabbit == null || !rabbit.isOpen()) {
					return Health.down().withDetail("rabbit", "unavailable").build();
				}
			} finally {
				// CachingConnectionFactory returns a close-safe proxy. Closing here is
				// still essential for any non-caching implementation used later.
				if (rabbit != null) {
					rabbit.close();
				}
			}
			return Health.up()
					.withDetail("database", "up")
					.withDetail("rabbit", "up")
					.build();
		} catch (Exception unavailable) {
			return Health.down()
					.withDetail("dependencyFailure", unavailable.getClass().getSimpleName())
					.build();
		}
	}
}

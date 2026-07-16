package com.berkayb.soundconnectworker;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.actuate.health.Status;

import javax.sql.DataSource;
import java.sql.Connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MediaWorkerDependencyHealthIndicatorTest {

	@Test
	void reportsUpOnlyAfterBothDatabaseAndRabbitAreReachable() throws Exception {
		DataSource dataSource = mock(DataSource.class);
		Connection database = mock(Connection.class);
		ConnectionFactory rabbitFactory = mock(ConnectionFactory.class);
		org.springframework.amqp.rabbit.connection.Connection rabbit =
				mock(org.springframework.amqp.rabbit.connection.Connection.class);
		when(dataSource.getConnection()).thenReturn(database);
		when(database.isValid(2)).thenReturn(true);
		when(rabbitFactory.createConnection()).thenReturn(rabbit);
		when(rabbit.isOpen()).thenReturn(true);

		var indicator = new MediaWorkerDependencyHealthIndicator(dataSource, rabbitFactory);

		assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
		verify(database).isValid(2);
		verify(rabbitFactory).createConnection();
		verify(rabbit).isOpen();
		verify(rabbit).close();
	}

	@Test
	void closesRabbitConnectionEvenWhenItIsNotOpen() throws Exception {
		DataSource dataSource = mock(DataSource.class);
		Connection database = mock(Connection.class);
		ConnectionFactory rabbitFactory = mock(ConnectionFactory.class);
		org.springframework.amqp.rabbit.connection.Connection rabbit =
				mock(org.springframework.amqp.rabbit.connection.Connection.class);
		when(dataSource.getConnection()).thenReturn(database);
		when(database.isValid(2)).thenReturn(true);
		when(rabbitFactory.createConnection()).thenReturn(rabbit);
		when(rabbit.isOpen()).thenReturn(false);

		var indicator = new MediaWorkerDependencyHealthIndicator(dataSource, rabbitFactory);

		assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
		verify(rabbit).close();
	}

	@Test
	void reportsDownWhenRabbitIsUnavailable() throws Exception {
		DataSource dataSource = mock(DataSource.class);
		Connection database = mock(Connection.class);
		ConnectionFactory rabbitFactory = mock(ConnectionFactory.class);
		when(dataSource.getConnection()).thenReturn(database);
		when(database.isValid(2)).thenReturn(true);
		when(rabbitFactory.createConnection()).thenThrow(new IllegalStateException("offline"));

		var indicator = new MediaWorkerDependencyHealthIndicator(dataSource, rabbitFactory);

		assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
	}
}

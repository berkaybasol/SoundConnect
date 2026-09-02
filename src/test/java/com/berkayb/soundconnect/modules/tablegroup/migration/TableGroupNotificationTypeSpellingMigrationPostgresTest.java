package com.berkayb.soundconnect.modules.tablegroup.migration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class TableGroupNotificationTypeSpellingMigrationPostgresTest {

	@Container
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine")
			.withDatabaseName("soundconnect_table_group_notification_type_spelling")
			.withUsername("soundconnect")
			.withPassword("soundconnect");

	@BeforeEach
	void resetSchema() throws SQLException {
		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			statement.execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public");
		}
	}

	@Test
	void migrationRewritesRowsAndArbitrarilyNamedChecksAndIsRerunnable() throws Exception {
		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					CREATE TABLE tbl_notification (
					 id integer PRIMARY KEY,
					 type varchar(64) NOT NULL,
					 CONSTRAINT hibernate_generated_9274 CHECK (
					   type IN ('TABLE_JOIN_REQUEST_RECEVIED', 'TABLE_CANCELLED')
					 ),
					 CONSTRAINT keep_nonblank_notification_type CHECK (char_length(type) > 0)
					)
					""");
			statement.execute("""
					CREATE TABLE tbl_table_group_notification_outbox (
					 id integer PRIMARY KEY,
					 notification_type varchar(64) NOT NULL
					)
					""");
			statement.execute("""
					INSERT INTO tbl_notification (id, type) VALUES
					 (1, 'TABLE_JOIN_REQUEST_RECEVIED'),
					 (2, 'TABLE_CANCELLED')
					""");
			statement.execute("""
					INSERT INTO tbl_table_group_notification_outbox (id, notification_type) VALUES
					 (1, 'TABLE_JOIN_REQUEST_RECEVIED'),
					 (2, 'UNRELATED_LEGACY')
					""");
			statement.execute("""
					ALTER TABLE tbl_table_group_notification_outbox
					 ADD CONSTRAINT "Odd TableGroup Type Guard" CHECK (
					   notification_type IN (
					     'TABLE_JOIN_REQUEST_RECEVIED',
					     'TABLE_JOIN_REQUEST_APPROVED'
					   )
					 ) NOT VALID
					""");
		}

		execute(migrationSql());
		execute(migrationSql());

		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			assertThat(singleInt(statement, """
					SELECT count(*)
					  FROM tbl_notification
					 WHERE type = 'TABLE_JOIN_REQUEST_RECEIVED'
					""")).isEqualTo(1);
			assertThat(singleInt(statement, """
					SELECT count(*)
					  FROM tbl_table_group_notification_outbox
					 WHERE notification_type = 'TABLE_JOIN_REQUEST_RECEIVED'
					""")).isEqualTo(1);
			assertThat(singleInt(statement, """
					SELECT count(*)
					  FROM tbl_notification
					 WHERE type = 'TABLE_JOIN_REQUEST_RECEVIED'
					""")).isZero();
			assertThat(singleInt(statement, """
					SELECT count(*)
					  FROM tbl_table_group_notification_outbox
					 WHERE notification_type = 'TABLE_JOIN_REQUEST_RECEVIED'
					""")).isZero();

			assertThat(singleInt(statement, """
					SELECT count(*)
					  FROM pg_constraint
					 WHERE conname IN (
					   'hibernate_generated_9274',
					   'Odd TableGroup Type Guard'
					 )
					   AND strpos(
					     pg_get_constraintdef(oid, true),
					     'TABLE_JOIN_REQUEST_RECEIVED'
					   ) > 0
					""")).isEqualTo(2);
			assertThat(singleInt(statement, """
					SELECT count(*)
					  FROM pg_constraint
					 WHERE strpos(
					   pg_get_constraintdef(oid, true),
					   'TABLE_JOIN_REQUEST_RECEVIED'
					 ) > 0
					""")).isZero();
			assertThat(singleInt(statement, """
					SELECT count(*)
					  FROM pg_constraint
					 WHERE conname = 'hibernate_generated_9274'
					   AND convalidated
					""")).isEqualTo(1);
			assertThat(singleInt(statement, """
					SELECT count(*)
					  FROM pg_constraint
					 WHERE conname = 'Odd TableGroup Type Guard'
					   AND NOT convalidated
					""")).isEqualTo(1);
			assertThat(singleInt(statement, """
					SELECT count(*)
					  FROM pg_constraint
					 WHERE conname = 'keep_nonblank_notification_type'
					""")).isEqualTo(1);

			statement.execute("""
					INSERT INTO tbl_notification (id, type)
					VALUES (3, 'TABLE_JOIN_REQUEST_RECEIVED')
					""");
			statement.execute("""
					INSERT INTO tbl_table_group_notification_outbox (id, notification_type)
					VALUES (3, 'TABLE_JOIN_REQUEST_RECEIVED')
					""");

			assertThatThrownBy(() -> statement.execute("""
					INSERT INTO tbl_notification (id, type)
					VALUES (4, 'TABLE_JOIN_REQUEST_RECEVIED')
					"""))
					.isInstanceOf(SQLException.class)
					.hasMessageContaining("hibernate_generated_9274");
			assertThatThrownBy(() -> statement.execute("""
					INSERT INTO tbl_table_group_notification_outbox (id, notification_type)
					VALUES (4, 'TABLE_JOIN_REQUEST_RECEVIED')
					"""))
					.isInstanceOf(SQLException.class)
					.hasMessageContaining("Odd TableGroup Type Guard");
		}
	}

	@Test
	void migrationRejectsMissingRequiredSchemaWithoutPartialChanges() throws Exception {
		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					CREATE TABLE tbl_notification (
					 id integer PRIMARY KEY,
					 type varchar(64) NOT NULL
					)
					""");
			statement.execute("""
					INSERT INTO tbl_notification (id, type)
					VALUES (1, 'TABLE_JOIN_REQUEST_RECEVIED')
					""");
		}

		assertThatThrownBy(() -> execute(migrationSql()))
				.isInstanceOf(SQLException.class)
				.hasMessageContaining(
						"requires tbl_table_group_notification_outbox");

		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			assertThat(singleString(statement, """
					SELECT type
					  FROM tbl_notification
					 WHERE id = 1
					""")).isEqualTo("TABLE_JOIN_REQUEST_RECEVIED");
		}
	}

	private static String migrationSql() throws Exception {
		return Files.readString(Path.of(System.getProperty("user.dir"), "scripts", "db",
				"2026-09-02-tablegroup-notification-type-spelling.sql"));
	}

	private static void execute(String sql) throws SQLException {
		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			statement.execute(sql);
		}
	}

	private static String singleString(Statement statement, String sql) throws SQLException {
		try (ResultSet result = statement.executeQuery(sql)) {
			assertThat(result.next()).isTrue();
			return result.getString(1);
		}
	}

	private static int singleInt(Statement statement, String sql) throws SQLException {
		try (ResultSet result = statement.executeQuery(sql)) {
			assertThat(result.next()).isTrue();
			return result.getInt(1);
		}
	}

	private static Connection connection() throws SQLException {
		return DriverManager.getConnection(
				POSTGRES.getJdbcUrl(),
				POSTGRES.getUsername(),
				POSTGRES.getPassword()
		);
	}
}

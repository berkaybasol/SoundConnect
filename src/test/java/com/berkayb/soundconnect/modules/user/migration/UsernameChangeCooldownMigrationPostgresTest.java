package com.berkayb.soundconnect.modules.user.migration;

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

@Testcontainers(disabledWithoutDocker = true)
class UsernameChangeCooldownMigrationPostgresTest {

	@Container
	static final PostgreSQLContainer<?> POSTGRES =
			new PostgreSQLContainer<>("postgres:16.4-alpine")
					.withDatabaseName("soundconnect_username_cooldown")
					.withUsername("soundconnect")
					.withPassword("soundconnect");

	@BeforeEach
	void createLegacySchema() throws SQLException {
		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			statement.execute("DROP TABLE IF EXISTS tbl_user CASCADE");
			statement.execute("""
					CREATE TABLE tbl_user (
					    id uuid PRIMARY KEY,
					    user_name varchar(255) NOT NULL
					)
					""");
			statement.execute("""
					INSERT INTO tbl_user (id, user_name)
					VALUES ('00000000-0000-0000-0000-000000000001', 'legacyuser')
					""");
		}
	}

	@Test
	void migrationIsAdditiveRerunnableAndLeavesExistingUsersImmediatelyEligible() throws Exception {
		String migration = migrationSql();
		assertThat(migration).contains("BEGIN;", "COMMIT;", "ADD COLUMN IF NOT EXISTS");

		executeMigration(migration);
		assertColumnContract();

		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			assertThat(singleString(statement, """
					SELECT username_changed_at::text
					FROM tbl_user
					WHERE id = '00000000-0000-0000-0000-000000000001'
					""")).isNull();
			statement.execute("""
					UPDATE tbl_user
					SET username_changed_at = TIMESTAMP '2026-07-24 12:00:00'
					WHERE id = '00000000-0000-0000-0000-000000000001'
					""");
		}

		executeMigration(migration);
		assertColumnContract();
		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			assertThat(singleString(statement, """
					SELECT username_changed_at::text
					FROM tbl_user
					WHERE id = '00000000-0000-0000-0000-000000000001'
					""")).isEqualTo("2026-07-24 12:00:00");
		}
	}

	private void assertColumnContract() throws SQLException {
		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			assertThat(singleString(statement, """
					SELECT data_type || ':' || is_nullable
					FROM information_schema.columns
					WHERE table_schema = current_schema()
					  AND table_name = 'tbl_user'
					  AND column_name = 'username_changed_at'
					""")).isEqualTo("timestamp without time zone:YES");
			assertThat(singleString(statement, """
					SELECT col_description('tbl_user'::regclass, ordinal_position)
					FROM information_schema.columns
					WHERE table_schema = current_schema()
					  AND table_name = 'tbl_user'
					  AND column_name = 'username_changed_at'
					""")).contains("last successful self-service username change");
		}
	}

	private static Connection connection() throws SQLException {
		return DriverManager.getConnection(
				POSTGRES.getJdbcUrl(),
				POSTGRES.getUsername(),
				POSTGRES.getPassword()
		);
	}

	private static String migrationSql() throws Exception {
		return Files.readString(Path.of(
				System.getProperty("user.dir"),
				"scripts", "db", "2026-07-24-username-change-cooldown.sql"
		));
	}

	private static void executeMigration(String sql) throws SQLException {
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
}

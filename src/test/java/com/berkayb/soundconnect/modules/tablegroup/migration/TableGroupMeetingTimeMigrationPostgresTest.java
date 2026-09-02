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
class TableGroupMeetingTimeMigrationPostgresTest {

	@Container
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine")
			.withDatabaseName("soundconnect_table_group_meeting_time")
			.withUsername("soundconnect")
			.withPassword("soundconnect");

	@BeforeEach
	void resetSchema() throws SQLException {
		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			statement.execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public");
		}
	}

	@Test
	void migrationIsRerunnableBackfillsLegacyTimeAndPreservesTechnicalExpiry() throws Exception {
		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					CREATE TABLE tbl_table_group (
					 id uuid PRIMARY KEY,
					 start_at timestamp with time zone NOT NULL,
					 expires_at timestamp with time zone NOT NULL
					)
					""");
			statement.execute("""
					INSERT INTO tbl_table_group (id,start_at,expires_at)
					VALUES (
					 '10000000-0000-0000-0000-000000000001',
					 '2026-08-31 10:00:00+00',
					 '2026-08-31 15:00:00+00'
					)
					""");
		}

		execute(migrationSql());

		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			assertThat(singleString(statement, """
					SELECT to_char(meeting_at AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS')
					  FROM tbl_table_group
					 WHERE id = '10000000-0000-0000-0000-000000000001'
					""")).isEqualTo("2026-08-31 15:00:00");
			assertThat(singleString(statement, """
					SELECT to_char(expires_at AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS')
					  FROM tbl_table_group
					 WHERE id = '10000000-0000-0000-0000-000000000001'
					""")).isEqualTo("2026-08-31 15:00:00");
			statement.execute("""
					UPDATE tbl_table_group
					   SET meeting_at = '2026-08-31 14:00:00+00'
					 WHERE id = '10000000-0000-0000-0000-000000000001'
					""");
		}

		// A rerun must neither restore the legacy value nor move expires_at.
		execute(migrationSql());

		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			assertThat(singleString(statement, """
					SELECT data_type || ':' || is_nullable
					  FROM information_schema.columns
					 WHERE table_schema = current_schema()
					   AND table_name = 'tbl_table_group'
					   AND column_name = 'meeting_at'
					""")).isEqualTo("timestamp with time zone:YES");
			assertThat(singleString(statement, """
					SELECT to_char(meeting_at AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS')
					  FROM tbl_table_group
					 WHERE id = '10000000-0000-0000-0000-000000000001'
					""")).isEqualTo("2026-08-31 14:00:00");
			assertThat(singleString(statement, """
					SELECT to_char(expires_at AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS')
					  FROM tbl_table_group
					 WHERE id = '10000000-0000-0000-0000-000000000001'
					""")).isEqualTo("2026-08-31 15:00:00");
			assertThat(singleInt(statement, """
					SELECT count(*) FROM pg_constraint
					 WHERE conrelid = 'tbl_table_group'::regclass
					   AND conname = 'ck_table_group_meeting_time'
					   AND convalidated
					""")).isEqualTo(1);

			// Nullable-at-rest is intentional while old API processes may still write.
			statement.execute("""
					INSERT INTO tbl_table_group (id,start_at,expires_at,meeting_at)
					VALUES (
					 '10000000-0000-0000-0000-000000000002',
					 '2026-08-31 10:00:00+00',
					 '2026-09-01 10:00:00+00',
					 NULL
					)
					""");

			assertThatThrownBy(() -> statement.execute("""
					UPDATE tbl_table_group
					   SET meeting_at = start_at
					 WHERE id = '10000000-0000-0000-0000-000000000001'
					"""))
					.isInstanceOf(SQLException.class)
					.hasMessageContaining("ck_table_group_meeting_time");
			assertThatThrownBy(() -> statement.execute("""
					UPDATE tbl_table_group
					   SET meeting_at = expires_at + INTERVAL '1 second'
					 WHERE id = '10000000-0000-0000-0000-000000000001'
					"""))
					.isInstanceOf(SQLException.class)
					.hasMessageContaining("ck_table_group_meeting_time");
		}
	}

	@Test
	void migrationRejectsMeetingBeyondTwentyFourHoursEvenWithLaterExpiry() throws Exception {
		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					CREATE TABLE tbl_table_group (
					 id uuid PRIMARY KEY,
					 start_at timestamp with time zone NOT NULL,
					 expires_at timestamp with time zone NOT NULL,
					 meeting_at timestamp with time zone
					)
					""");
			statement.execute("""
					INSERT INTO tbl_table_group (id,start_at,expires_at,meeting_at)
					VALUES (
					 '10000000-0000-0000-0000-000000000001',
					 '2026-08-31 10:00:00+00',
					 '2026-09-01 16:00:00+00',
					 '2026-09-01 11:00:00+00'
					)
					""");
		}

		assertThatThrownBy(() -> execute(migrationSql()))
				.isInstanceOf(SQLException.class)
				.hasMessageContaining("outside the table lifetime");
	}

	@Test
	void migrationRefusesIncompatiblePartialSchemaAndMissingBaseTable() throws Exception {
		assertThatThrownBy(() -> execute(migrationSql()))
				.isInstanceOf(SQLException.class)
				.hasMessageContaining("requires tbl_table_group");

		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					CREATE TABLE tbl_table_group (
					 id uuid PRIMARY KEY,
					 start_at timestamp with time zone NOT NULL,
					 expires_at timestamp with time zone NOT NULL,
					 meeting_at timestamp without time zone
					)
					""");
		}

		assertThatThrownBy(() -> execute(migrationSql()))
				.isInstanceOf(SQLException.class)
				.hasMessageContaining("meeting_at to be timestamptz");
	}

	private static String migrationSql() throws Exception {
		return Files.readString(Path.of(System.getProperty("user.dir"), "scripts", "db",
				"2026-09-01-tablegroup-meeting-time.sql"));
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

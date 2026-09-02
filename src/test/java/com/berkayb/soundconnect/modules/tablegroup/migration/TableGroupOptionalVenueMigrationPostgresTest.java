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
class TableGroupOptionalVenueMigrationPostgresTest {

	@Container
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine")
			.withDatabaseName("soundconnect_optional_venue")
			.withUsername("soundconnect")
			.withPassword("soundconnect");

	@BeforeEach
	void createLegacyVenueShape() throws SQLException {
		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			statement.execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public");
			statement.execute("""
					CREATE TABLE tbl_table_group (
					 id uuid PRIMARY KEY,
					 venue_id uuid,
					 venue_name varchar(128) NOT NULL,
					 CONSTRAINT ck_table_group_venue_shape CHECK (
					  (venue_id IS NOT NULL AND char_length(btrim(venue_name)) BETWEEN 1 AND 64)
					  OR
					  (venue_id IS NULL AND char_length(btrim(venue_name)) BETWEEN 1 AND 64)
					 )
					)
					""");
			statement.execute("""
					INSERT INTO tbl_table_group (id,venue_name)
					VALUES ('10000000-0000-0000-0000-000000000001','Custom Venue')
					""");
		}
	}

	@Test
	void migrationIsRerunnableAndAllowsNoVenueWithoutWeakeningNameBounds() throws Exception {
		execute(migrationSql());
		execute(migrationSql());

		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			assertThat(singleInt(statement, """
					SELECT count(*)
					  FROM information_schema.columns
					 WHERE table_schema = current_schema()
					   AND table_name = 'tbl_table_group'
					   AND column_name IN ('venue_id','venue_name')
					   AND is_nullable = 'YES'
					""")).isEqualTo(2);
			assertThat(singleInt(statement, """
					SELECT count(*) FROM pg_constraint
					 WHERE conrelid = 'tbl_table_group'::regclass
					   AND conname = 'ck_table_group_venue_shape'
					   AND convalidated
					""")).isEqualTo(1);

			statement.execute("""
					INSERT INTO tbl_table_group (id,venue_id,venue_name)
					VALUES ('10000000-0000-0000-0000-000000000002',NULL,NULL)
					""");
			statement.execute("""
					INSERT INTO tbl_table_group (id,venue_id,venue_name)
					VALUES (
					 '10000000-0000-0000-0000-000000000003',
					 '20000000-0000-0000-0000-000000000003',
					 'Registered Snapshot'
					)
					""");

			assertThatThrownBy(() -> statement.execute("""
					INSERT INTO tbl_table_group (id,venue_name)
					VALUES ('10000000-0000-0000-0000-000000000004','   ')
					"""))
					.isInstanceOf(SQLException.class)
					.hasMessageContaining("ck_table_group_venue_shape");
			assertThatThrownBy(() -> statement.execute("""
					INSERT INTO tbl_table_group (id,venue_name)
					VALUES (
					 '10000000-0000-0000-0000-000000000005',
					 repeat('x',65)
					)
					"""))
					.isInstanceOf(SQLException.class)
					.hasMessageContaining("ck_table_group_venue_shape");
		}
	}

	private static String migrationSql() throws Exception {
		return Files.readString(Path.of(System.getProperty("user.dir"), "scripts", "db",
				"2026-09-01-tablegroup-optional-venue.sql"));
	}

	private static void execute(String sql) throws SQLException {
		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			statement.execute(sql);
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
				POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
	}
}

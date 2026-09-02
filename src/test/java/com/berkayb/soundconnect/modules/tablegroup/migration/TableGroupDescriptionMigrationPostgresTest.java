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
class TableGroupDescriptionMigrationPostgresTest {

	@Container
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine")
			.withDatabaseName("soundconnect_table_group_description")
			.withUsername("soundconnect")
			.withPassword("soundconnect");

	@BeforeEach
	void resetSchema() throws SQLException {
		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			statement.execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public");
		}
	}

	@Test
	void migrationIsRerunnablePreservesLegacyNullAndEnforcesNormalizedBound() throws Exception {
		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			statement.execute("CREATE TABLE tbl_table_group (id uuid PRIMARY KEY)");
			statement.execute("""
					INSERT INTO tbl_table_group (id)
					VALUES ('10000000-0000-0000-0000-000000000001')
					""");
		}

		execute(migrationSql());
		execute(migrationSql());

		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			assertThat(singleString(statement, """
					SELECT data_type || ':' || character_maximum_length || ':' || is_nullable
					  FROM information_schema.columns
					 WHERE table_schema = current_schema()
					   AND table_name = 'tbl_table_group'
					   AND column_name = 'description'
					""")).isEqualTo("character varying:280:YES");
			assertThat(singleString(statement, """
					SELECT description
					  FROM tbl_table_group
					 WHERE id = '10000000-0000-0000-0000-000000000001'
					""")).isNull();

			statement.execute("""
					INSERT INTO tbl_table_group (id, description)
					VALUES ('10000000-0000-0000-0000-000000000002', 'Yeni insanlarla tanışmak istiyorum')
					""");
			statement.execute("""
					INSERT INTO tbl_table_group (id, description)
					VALUES ('10000000-0000-0000-0000-000000000008', repeat('😀', 280))
					""");
			assertThatThrownBy(() -> statement.execute("""
					INSERT INTO tbl_table_group (id, description)
					VALUES ('10000000-0000-0000-0000-000000000003', '  başında boşluk var')
					"""))
					.isInstanceOf(SQLException.class)
					.hasMessageContaining("ck_table_group_description");
			assertThatThrownBy(() -> statement.execute("""
					INSERT INTO tbl_table_group (id, description)
					VALUES ('10000000-0000-0000-0000-000000000004', '')
					"""))
					.isInstanceOf(SQLException.class)
					.hasMessageContaining("ck_table_group_description");
			assertThatThrownBy(() -> statement.execute("""
					INSERT INTO tbl_table_group (id, description)
					VALUES ('10000000-0000-0000-0000-000000000006', E'\\tkenarlarda kontrol karakteri var\\n')
					"""))
					.isInstanceOf(SQLException.class)
					.hasMessageContaining("ck_table_group_description");
			assertThatThrownBy(() -> statement.execute("""
					INSERT INTO tbl_table_group (id, description)
					VALUES (
					 '10000000-0000-0000-0000-000000000007',
					 chr(160) || 'kenarlarda Unicode boşluk var' || chr(12288)
					)
					"""))
					.isInstanceOf(SQLException.class)
					.hasMessageContaining("ck_table_group_description");
			assertThatThrownBy(() -> statement.execute("""
					INSERT INTO tbl_table_group (id, description)
					VALUES ('10000000-0000-0000-0000-000000000005', repeat('x', 281))
					"""))
					.isInstanceOf(SQLException.class)
					.hasMessageContaining("value too long");
		}
	}

	@Test
	void migrationRefusesIncompatibleOrUnnormalizedPartialRollout() throws Exception {
		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					CREATE TABLE tbl_table_group (
					 id uuid PRIMARY KEY,
					 description text
					)
					""");
			statement.execute("""
					INSERT INTO tbl_table_group (id, description)
					VALUES (
					 '10000000-0000-0000-0000-000000000001',
					 chr(160) || E'\\ttrim gerekli\\n' || chr(12288)
					)
					""");
		}

		assertThatThrownBy(() -> execute(migrationSql()))
				.isInstanceOf(SQLException.class)
				.hasMessageContaining("require manual normalization");

		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			assertThat(singleString(statement, """
					SELECT data_type
					  FROM information_schema.columns
					 WHERE table_schema = current_schema()
					   AND table_name = 'tbl_table_group'
					   AND column_name = 'description'
					""")).isEqualTo("text");
		}
	}

	@Test
	void migrationRefusesToRunBeforeTableGroupSchemaExists() throws Exception {
		assertThatThrownBy(() -> execute(migrationSql()))
				.isInstanceOf(SQLException.class)
				.hasMessageContaining("requires tbl_table_group");
	}

	private static String migrationSql() throws Exception {
		return Files.readString(Path.of(System.getProperty("user.dir"), "scripts", "db",
				"2026-09-01-tablegroup-description.sql"));
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

	private static Connection connection() throws SQLException {
		return DriverManager.getConnection(
				POSTGRES.getJdbcUrl(),
				POSTGRES.getUsername(),
				POSTGRES.getPassword()
		);
	}
}

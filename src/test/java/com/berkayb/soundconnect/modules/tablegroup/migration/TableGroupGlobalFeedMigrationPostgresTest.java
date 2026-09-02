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
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class TableGroupGlobalFeedMigrationPostgresTest {

	@Container
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine")
			.withDatabaseName("soundconnect_table_group_global_feed")
			.withUsername("soundconnect")
			.withPassword("soundconnect");

	@BeforeEach
	void resetSchema() throws SQLException {
		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			statement.execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public");
		}
	}

	@Test
	void migrationBackfillsLegacyCreatedAtEnforcesInvariantAndKeepsTheGlobalFeedPlan() throws Exception {
		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			createCompatibleTable(statement);
			statement.execute("""
					INSERT INTO tbl_table_group
					 (id,created_at,start_at,expires_at,status) VALUES
					 ('10000000-0000-0000-0000-000000000001',NULL,
					  TIMESTAMPTZ '2026-08-31 12:00:00+03',CURRENT_TIMESTAMP + INTERVAL '1 day','ACTIVE'),
					 ('10000000-0000-0000-0000-000000000002',
					  TIMESTAMP '2026-08-31 10:00:00',TIMESTAMPTZ '2026-08-31 10:00:00+00',
					  CURRENT_TIMESTAMP + INTERVAL '1 day','ACTIVE')
					""");
		}

		execute(migrationSql());
		execute(migrationSql());

		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			assertThat(singleTimestamp(statement, """
					SELECT created_at FROM tbl_table_group
					 WHERE id = '10000000-0000-0000-0000-000000000001'
					""").toLocalDateTime())
					.isEqualTo(LocalDateTime.of(2026, 8, 31, 9, 0));
			assertThat(singleString(statement, """
					SELECT is_nullable
					  FROM information_schema.columns
					 WHERE table_schema = current_schema()
					   AND table_name = 'tbl_table_group'
					   AND column_name = 'created_at'
					""")).isEqualTo("NO");
			assertThat(strings(statement, """
					SELECT id::text
					  FROM tbl_table_group
					 WHERE status = 'ACTIVE'
					   AND expires_at > CURRENT_TIMESTAMP
					 ORDER BY created_at DESC, id DESC
					""")).containsExactly(
					"10000000-0000-0000-0000-000000000002",
					"10000000-0000-0000-0000-000000000001"
			);

			String indexDefinition = singleString(statement, """
					SELECT indexdef
					  FROM pg_indexes
					 WHERE schemaname = current_schema()
					   AND indexname = 'idx_group_status_created_id_active_feed'
					""");
			assertThat(indexDefinition)
					.contains("(status, created_at DESC, id DESC)")
					.contains("INCLUDE (expires_at)");

			statement.execute("SET enable_seqscan = off");
			statement.execute("SET enable_bitmapscan = off");
			assertThat(explain(statement, """
					SELECT id
					  FROM tbl_table_group
					 WHERE status = 'ACTIVE'
					   AND expires_at > CURRENT_TIMESTAMP
					 ORDER BY created_at DESC, id DESC
					 LIMIT 20
					"""))
					.contains("idx_group_status_created_id_active_feed")
					.doesNotContain("Sort");

			assertThatThrownBy(() -> statement.execute("""
					INSERT INTO tbl_table_group
					 (id,created_at,start_at,expires_at,status) VALUES
					 ('10000000-0000-0000-0000-000000000003',NULL,
					  CURRENT_TIMESTAMP,CURRENT_TIMESTAMP + INTERVAL '1 day','ACTIVE')
					"""))
					.isInstanceOf(SQLException.class)
					.hasMessageContaining("created_at");
		}
	}

	@Test
	void migrationRefusesToInventCreatedAtWhenNoSourceClockExists() throws Exception {
		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			createCompatibleTable(statement);
			statement.execute("""
					INSERT INTO tbl_table_group
					 (id,created_at,start_at,expires_at,status) VALUES
					 ('10000000-0000-0000-0000-000000000001',NULL,NULL,
					  CURRENT_TIMESTAMP + INTERVAL '1 day','ACTIVE')
					""");
		}

		assertThatThrownBy(() -> execute(migrationSql()))
				.isInstanceOf(SQLException.class)
				.hasMessageContaining("rows without created_at/start_at require manual reconciliation");

		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			assertThat(singleString(statement, """
					SELECT is_nullable
					  FROM information_schema.columns
					 WHERE table_schema = current_schema()
					   AND table_name = 'tbl_table_group'
					   AND column_name = 'created_at'
					""")).isEqualTo("YES");
			assertThat(singleInt(statement, """
					SELECT count(*) FROM pg_indexes
					 WHERE schemaname = current_schema()
					   AND indexname = 'idx_group_status_created_id_active_feed'
					""")).isZero();
		}
	}

	@Test
	void migrationRefusesAnUnhardenedStartAtTimestampType() throws Exception {
		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					CREATE TABLE tbl_table_group (
					 id uuid PRIMARY KEY,
					 created_at timestamp without time zone,
					 start_at timestamp without time zone,
					 expires_at timestamp with time zone NOT NULL,
					 status varchar(16) NOT NULL
					)
					""");
		}

		assertThatThrownBy(() -> execute(migrationSql()))
				.isInstanceOf(SQLException.class)
				.hasMessageContaining("requires UTC wall-clock created_at and hardened timestamptz start_at");
	}

	@Test
	void migrationRefusesToRunBeforeTheTableGroupSchemaExists() throws Exception {
		assertThatThrownBy(() -> execute(migrationSql()))
				.isInstanceOf(SQLException.class)
				.hasMessageContaining("requires tbl_table_group");
	}

	private static void createCompatibleTable(Statement statement) throws SQLException {
		statement.execute("""
				CREATE TABLE tbl_table_group (
				 id uuid PRIMARY KEY,
				 created_at timestamp without time zone,
				 start_at timestamp with time zone,
				 expires_at timestamp with time zone NOT NULL,
				 status varchar(16) NOT NULL
				)
				""");
	}

	private static String migrationSql() throws Exception {
		return Files.readString(Path.of(System.getProperty("user.dir"), "scripts", "db",
				"2026-08-31-tablegroup-global-feed.sql"));
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

	private static Timestamp singleTimestamp(Statement statement, String sql) throws SQLException {
		try (ResultSet result = statement.executeQuery(sql)) {
			assertThat(result.next()).isTrue();
			return result.getTimestamp(1);
		}
	}

	private static List<String> strings(Statement statement, String sql) throws SQLException {
		List<String> values = new ArrayList<>();
		try (ResultSet result = statement.executeQuery(sql)) {
			while (result.next()) {
				values.add(result.getString(1));
			}
		}
		return values;
	}

	private static String explain(Statement statement, String sql) throws SQLException {
		StringBuilder plan = new StringBuilder();
		try (ResultSet result = statement.executeQuery("EXPLAIN (COSTS OFF) " + sql)) {
			while (result.next()) {
				plan.append(result.getString(1)).append('\n');
			}
		}
		return plan.toString();
	}

	private static Connection connection() throws SQLException {
		return DriverManager.getConnection(
				POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
	}
}

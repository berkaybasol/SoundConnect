package com.berkayb.soundconnect.modules.user.migration;

import com.berkayb.soundconnect.shared.util.UsernameUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class UsernameCanonicalizationMigrationPostgresTest {
	@Container
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine")
			.withDatabaseName("soundconnect_username_migration")
			.withUsername("soundconnect")
			.withPassword("soundconnect");

	@BeforeEach
	void createLegacySchema() throws SQLException {
		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			statement.execute("DROP TABLE IF EXISTS tbl_user CASCADE");
			statement.execute("""
					CREATE TABLE tbl_user (
					    id uuid PRIMARY KEY,
					    user_name varchar(255) NOT NULL UNIQUE
					)
					""");
		}
	}

	@Test
	void migrationBackfillsWithRootSemanticsAndIsRerunnable() throws Exception {
		Map<UUID, String> legacyUsernames = new LinkedHashMap<>();
		legacyUsernames.put(UUID.randomUUID(), " Berkay ");
		legacyUsernames.put(UUID.randomUUID(), "IUSER");
		legacyUsernames.put(UUID.randomUUID(), "\tTabUser\u00A0");
		legacyUsernames.put(UUID.randomUUID(), "\u2003EmSpaceUser\uFEFF");
		legacyUsernames.put(UUID.randomUUID(), "\uD83D\uDE00\uD83D\uDE00");
		for (Map.Entry<UUID, String> entry : legacyUsernames.entrySet()) {
			insert(entry.getKey(), entry.getValue());
		}

		String migration = migrationSql();
		assertThat(migration).contains(
				"BEGIN;",
				"COMMIT;",
				"ACCESS EXCLUSIVE",
				"soundconnect_canonical_username"
		);
		assertThat(migration).doesNotContain("char_length");
		executeMigration(migration);
		assertCanonicalRows(legacyUsernames);
		assertCanonicalConstraintAndIndex();

		executeMigration(migration);
		assertCanonicalRows(legacyUsernames);
		assertCanonicalConstraintAndIndex();

		assertThatThrownBy(() -> insert(UUID.randomUUID(), " MixedCase "))
				.isInstanceOfSatisfying(SQLException.class,
						exception -> assertThat(exception.getSQLState()).isEqualTo("23514"));
		assertThatThrownBy(() -> insert(UUID.randomUUID(), "\u00A0\u2003\uFEFF"))
				.isInstanceOfSatisfying(SQLException.class,
						exception -> assertThat(exception.getSQLState()).isEqualTo("23514"));
	}

	@Test
	void canonicalCollisionAbortsWithoutMutatingLegacyRowsOrSchema() throws Exception {
		UUID firstId = UUID.randomUUID();
		UUID secondId = UUID.randomUUID();
		insert(firstId, "\u0130USER");
		insert(secondId, " iuser ");

		assertThatThrownBy(() -> executeMigration(migrationSql()))
				.isInstanceOfSatisfying(SQLException.class,
						exception -> assertThat(exception.getSQLState()).isEqualTo("23505"));

		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			assertThat(singleString(statement,
					"SELECT user_name FROM tbl_user WHERE id = '" + firstId + "'")).isEqualTo("\u0130USER");
			assertThat(singleString(statement,
					"SELECT user_name FROM tbl_user WHERE id = '" + secondId + "'")).isEqualTo(" iuser ");
			assertThat(singleInt(statement, """
					SELECT count(*) FROM pg_constraint
					WHERE conname = 'ck_tbl_user_username_canonical'
					""")).isZero();
			assertThat(singleInt(statement, """
					SELECT count(*) FROM pg_indexes
					WHERE indexname = 'ux_tbl_user_username_canonical'
					""")).isZero();
		}
	}

	@Test
	void postgresCanonicalFunctionMatchesJavaSimpleLowercaseCorpus() throws Exception {
		executeMigration(migrationSql());
		List<String> corpus = List.of(
				"\t\u00A0\u2003\uFEFFBeRKay\uFEFF\u2003\u00A0\r",
				"\u0130USER",
				"\u039F\u03A3",
				"\u03A3\u039F\u03A3",
				"\u1E9E",
				"\uD801\uDC00",
				"\uD83D\uDE00\uD83D\uDE00"
		);

		try (Connection connection = connection();
		     PreparedStatement statement = connection.prepareStatement(
				     "SELECT public.soundconnect_canonical_username(?)")) {
			for (String input : corpus) {
				statement.setString(1, input);
				try (ResultSet result = statement.executeQuery()) {
					assertThat(result.next()).isTrue();
					assertThat(result.getString(1))
							.as("PostgreSQL canonical value for %s", input)
							.isEqualTo(UsernameUtils.normalize(input));
				}
			}
		}
	}

	@Test
	void databaseSerializesConcurrentCanonicalUsernameWriters() throws Exception {
		executeMigration(migrationSql());
		// Isolate the functional index in this test. Production keeps its existing
		// exact unique constraint; the migration deliberately does not guess its name.
		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			statement.execute("ALTER TABLE tbl_user DROP CONSTRAINT tbl_user_user_name_key");
		}
		CountDownLatch start = new CountDownLatch(1);

		try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
			Future<Boolean> first = executor.submit(() -> insertConcurrent(start));
			Future<Boolean> second = executor.submit(() -> insertConcurrent(start));
			start.countDown();

			assertThat(List.of(first.get(), second.get()))
					.containsExactlyInAnyOrder(true, false);
		}
	}

	@Test
	void lateFailureRollsBackBackfillConstraintAndIndex() throws Exception {
		UUID userId = UUID.randomUUID();
		insert(userId, " LegacyUser ");
		String failingMigration = migrationSql().replaceFirst(
				"(?s)\\RCOMMIT;\\s*$",
				"\nDO \\$\\$ BEGIN RAISE EXCEPTION 'forced username migration failure'; END \\$\\$;\nCOMMIT;"
		);

		assertThatThrownBy(() -> executeMigration(failingMigration))
				.isInstanceOf(SQLException.class)
				.hasMessageContaining("forced username migration failure");

		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			assertThat(singleString(statement,
					"SELECT user_name FROM tbl_user WHERE id = '" + userId + "'"))
					.isEqualTo(" LegacyUser ");
			assertThat(singleInt(statement, """
					SELECT count(*) FROM pg_constraint
					WHERE conname = 'ck_tbl_user_username_canonical'
					""")).isZero();
			assertThat(singleInt(statement, """
					SELECT count(*) FROM pg_indexes
					WHERE indexname = 'ux_tbl_user_username_canonical'
					""")).isZero();
		}
	}

	private void assertCanonicalRows(Map<UUID, String> legacyUsernames) throws SQLException {
		try (Connection connection = connection();
		     PreparedStatement statement = connection.prepareStatement(
				     "SELECT user_name FROM tbl_user WHERE id = ?")) {
			for (Map.Entry<UUID, String> entry : legacyUsernames.entrySet()) {
				statement.setObject(1, entry.getKey());
				try (ResultSet result = statement.executeQuery()) {
					assertThat(result.next()).isTrue();
					assertThat(result.getString(1)).isEqualTo(UsernameUtils.normalize(entry.getValue()));
				}
			}
		}
	}

	private void assertCanonicalConstraintAndIndex() throws SQLException {
		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			assertThat(singleInt(statement, """
					SELECT count(*) FROM pg_constraint
					WHERE conname = 'ck_tbl_user_username_canonical'
					  AND contype = 'c'
					  AND convalidated
					""")).isEqualTo(1);
			assertThat(singleString(statement, """
					SELECT indexdef FROM pg_indexes
					WHERE indexname = 'ux_tbl_user_username_canonical'
					"""))
					.contains("UNIQUE INDEX", "soundconnect_canonical_username");
			assertThat(singleString(statement, """
					SELECT provolatile::text
					FROM pg_proc
					WHERE proname = 'soundconnect_canonical_username'
					""")).isEqualTo("i");
			assertThat(singleString(statement, """
					SELECT pg_get_constraintdef(oid)
					FROM pg_constraint
					WHERE conname = 'ck_tbl_user_username_canonical'
					""")).doesNotContain("char_length");
			assertThat(singleInt(statement, """
					SELECT count(*) FROM pg_constraint
					WHERE conrelid = 'tbl_user'::regclass
					  AND contype = 'u'
					""")).isEqualTo(1);
		}
	}

	private boolean insertConcurrent(CountDownLatch start) throws Exception {
		start.await();
		try {
			insert(UUID.randomUUID(), "racer");
			return true;
		} catch (SQLException duplicate) {
			assertThat(duplicate.getSQLState()).isEqualTo("23505");
			return false;
		}
	}

	private void insert(UUID id, String username) throws SQLException {
		try (Connection connection = connection();
		     PreparedStatement statement = connection.prepareStatement(
				     "INSERT INTO tbl_user (id, user_name) VALUES (?, ?)")) {
			statement.setObject(1, id);
			statement.setString(2, username);
			statement.executeUpdate();
		}
	}

	private static Connection connection() throws SQLException {
		return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
	}

	private static String migrationSql() throws Exception {
		return Files.readString(Path.of(
				System.getProperty("user.dir"),
				"scripts", "db", "2026-07-24-username-canonicalization.sql"
		));
	}

	private static void executeMigration(String sql) throws SQLException {
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

	private static String singleString(Statement statement, String sql) throws SQLException {
		try (ResultSet result = statement.executeQuery(sql)) {
			assertThat(result.next()).isTrue();
			return result.getString(1);
		}
	}
}

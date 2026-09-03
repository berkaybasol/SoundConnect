package com.berkayb.soundconnect.modules.profile.ListenerProfile.migration;

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
class ListenerGhostProfileMigrationPostgresTest {

	private static final String PROFILE_ID = "00000000-0000-0000-0000-000000000101";
	private static final String USER_ID = "00000000-0000-0000-0000-000000000201";
	private static final String NEW_PROFILE_ID = "00000000-0000-0000-0000-000000000102";
	private static final String NEW_USER_ID = "00000000-0000-0000-0000-000000000202";
	private static final String MISSING_PROFILE_USER_ID = "00000000-0000-0000-0000-000000000203";

	@Container
	static final PostgreSQLContainer<?> POSTGRES =
			new PostgreSQLContainer<>("postgres:16.4-alpine")
					.withDatabaseName("soundconnect_listener_ghost")
					.withUsername("soundconnect")
					.withPassword("soundconnect");

	@BeforeEach
	void createLegacySchema() throws SQLException {
		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			statement.execute("DROP TABLE IF EXISTS tbl_follow CASCADE");
			statement.execute("DROP TABLE IF EXISTS \"tbl_listener-profile\" CASCADE");
			statement.execute("DROP TABLE IF EXISTS user_roles CASCADE");
			statement.execute("DROP TABLE IF EXISTS tbl_role CASCADE");
			statement.execute("DROP TABLE IF EXISTS tbl_user CASCADE");
			statement.execute("CREATE TABLE tbl_user (id uuid PRIMARY KEY)");
			statement.execute("CREATE TABLE tbl_role (id uuid PRIMARY KEY, name varchar(255) NOT NULL UNIQUE)");
			statement.execute("CREATE TABLE user_roles (user_id uuid NOT NULL, role_id uuid NOT NULL)");
			statement.execute("""
					CREATE TABLE "tbl_listener-profile" (
					    id uuid PRIMARY KEY,
					    user_id uuid NOT NULL UNIQUE,
					    description varchar(1024)
					)
					""");
			statement.execute("""
					CREATE TABLE tbl_follow (
					    id uuid PRIMARY KEY,
					    follower_id uuid NOT NULL,
					    following_id uuid NOT NULL
					)
					""");
			statement.execute("""
					INSERT INTO tbl_user (id) VALUES
					('%s'), ('%s'), ('%s')
					""".formatted(USER_ID, NEW_USER_ID, MISSING_PROFILE_USER_ID));
			statement.execute("""
					INSERT INTO tbl_role (id, name)
					VALUES ('00000000-0000-0000-0000-000000000211', 'ROLE_LISTENER')
					""");
			statement.execute("""
					INSERT INTO user_roles (user_id, role_id) VALUES
					('%s', '00000000-0000-0000-0000-000000000211'),
					('%s', '00000000-0000-0000-0000-000000000211'),
					('%s', '00000000-0000-0000-0000-000000000211')
					""".formatted(USER_ID, NEW_USER_ID, MISSING_PROFILE_USER_ID));
			statement.execute("""
					INSERT INTO "tbl_listener-profile" (id, user_id, description)
					VALUES ('%s', '%s', 'preserved bio')
					""".formatted(PROFILE_ID, USER_ID));
		}
	}

	@Test
	void migrationIsAdditiveRerunnableAndPreservesLegacyContent() throws Exception {
		String migration = migrationSql();
		assertThat(migration).contains("BEGIN;", "COMMIT;", "ADD COLUMN IF NOT EXISTS");

		executeMigration(migration);
		assertSchemaContract();
		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			assertThat(singleString(statement, """
					SELECT visibility_mode || ':' || version || ':'
					       || visibility_choice_completed || ':' || description
					FROM "tbl_listener-profile"
					WHERE id = '%s'
					""".formatted(PROFILE_ID))).isEqualTo("STANDARD:0:true:preserved bio");
			statement.execute("""
					INSERT INTO "tbl_listener-profile" (id, user_id, description)
					VALUES ('%s', '%s', 'new listener')
					""".formatted(NEW_PROFILE_ID, NEW_USER_ID));
			assertThat(singleString(statement, """
					SELECT visibility_mode || ':' || version || ':' || visibility_choice_completed
					FROM "tbl_listener-profile"
					WHERE id = '%s'
					""".formatted(NEW_PROFILE_ID))).isEqualTo("STANDARD:0:false");
			statement.execute("""
					INSERT INTO tbl_follow (id, follower_id, following_id)
					VALUES ('00000000-0000-0000-0000-000000000301',
					        '00000000-0000-0000-0000-000000000302', '%s')
					""".formatted(USER_ID));
			statement.execute("""
					UPDATE "tbl_listener-profile"
					SET visibility_mode = 'GHOST',
					    visibility_changed_at = TIMESTAMP '2026-09-03 01:02:03',
					    version = 4
					WHERE id = '%s'
					""".formatted(PROFILE_ID));
			assertThat(singleString(statement, "SELECT count(*)::text FROM tbl_follow"))
					.isEqualTo("0");
		}

		executeMigration(migration);
		assertSchemaContract();
		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			assertThat(singleString(statement, """
					SELECT visibility_mode || ':' || version || ':' || visibility_choice_completed
					       || ':' || visibility_changed_at::text || ':' || description
					FROM "tbl_listener-profile"
					WHERE id = '%s'
					""".formatted(PROFILE_ID)))
					.isEqualTo("GHOST:4:true:2026-09-03 01:02:03:preserved bio");
			assertThat(singleString(statement, """
					SELECT visibility_choice_completed::text
					FROM "tbl_listener-profile"
					WHERE id = '%s'
					""".formatted(NEW_PROFILE_ID))).isEqualTo("false");
		}
	}

	@Test
	void databaseRejectsUnknownVisibilityAndNegativeVersion() throws Exception {
		executeMigration(migrationSql());

		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			assertThatThrownBy(() -> statement.execute("""
					INSERT INTO tbl_follow (id, follower_id, following_id)
					VALUES ('00000000-0000-0000-0000-000000000405',
					        '00000000-0000-0000-0000-000000000406', '%s')
					""".formatted(MISSING_PROFILE_USER_ID))).isInstanceOf(SQLException.class)
					.hasMessageContaining("onboarding-private listener profiles cannot receive followers");

			statement.execute("""
					INSERT INTO "tbl_listener-profile" (id, user_id, description)
					VALUES ('%s', '%s', 'pending choice')
					""".formatted(NEW_PROFILE_ID, NEW_USER_ID));
			assertThatThrownBy(() -> statement.execute("""
					INSERT INTO tbl_follow (id, follower_id, following_id)
					VALUES ('00000000-0000-0000-0000-000000000403',
					        '00000000-0000-0000-0000-000000000404', '%s')
					""".formatted(NEW_USER_ID))).isInstanceOf(SQLException.class)
					.hasMessageContaining("onboarding-private listener profiles cannot receive followers");
		}

		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			assertThatThrownBy(() -> statement.execute("""
					UPDATE "tbl_listener-profile" SET visibility_mode = 'INVISIBLE'
					WHERE id = '%s'
					""".formatted(PROFILE_ID))).isInstanceOf(SQLException.class);
		}

		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			assertThatThrownBy(() -> statement.execute("""
					UPDATE "tbl_listener-profile" SET version = -1
					WHERE id = '%s'
					""".formatted(PROFILE_ID))).isInstanceOf(SQLException.class);
		}

		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					UPDATE "tbl_listener-profile" SET visibility_mode = 'GHOST'
					WHERE id = '%s'
					""".formatted(PROFILE_ID));
			assertThatThrownBy(() -> statement.execute("""
					INSERT INTO tbl_follow (id, follower_id, following_id)
					VALUES ('00000000-0000-0000-0000-000000000401',
					        '00000000-0000-0000-0000-000000000402', '%s')
					""".formatted(USER_ID))).isInstanceOf(SQLException.class)
						.hasMessageContaining("listener profiles cannot receive followers");
		}
	}

	@Test
	void followGuardAllowsNonListenerTargetsAndCompletionRevocationPurgesExistingEdges() throws Exception {
		executeMigration(migrationSql());

		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			String nonListenerUserId = "00000000-0000-0000-0000-000000000601";
			statement.execute("""
					INSERT INTO tbl_follow (id, follower_id, following_id)
					VALUES ('00000000-0000-0000-0000-000000000602',
					        '00000000-0000-0000-0000-000000000603', '%s')
					""".formatted(nonListenerUserId));
			assertThat(singleString(statement, "SELECT count(*)::text FROM tbl_follow"))
					.isEqualTo("1");

			statement.execute("""
					INSERT INTO tbl_follow (id, follower_id, following_id)
					VALUES ('00000000-0000-0000-0000-000000000604',
					        '00000000-0000-0000-0000-000000000605', '%s')
					""".formatted(USER_ID));
			assertThat(singleString(statement, "SELECT count(*)::text FROM tbl_follow"))
					.isEqualTo("2");

			statement.execute("""
					UPDATE "tbl_listener-profile"
					SET visibility_choice_completed = false
					WHERE id = '%s'
					""".formatted(PROFILE_ID));
			assertThat(singleString(statement, """
					SELECT count(*)::text
					FROM tbl_follow
					WHERE following_id = '%s'
					""".formatted(USER_ID))).isEqualTo("0");
			assertThat(singleString(statement, """
					SELECT count(*)::text
					FROM tbl_follow
					WHERE following_id = '%s'
					""".formatted(nonListenerUserId))).isEqualTo("1");
		}
	}

	@Test
	void migrationRejectsASameNamedColumnWithAnIncompatibleType() throws Exception {
		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			statement.execute("ALTER TABLE \"tbl_listener-profile\" ADD COLUMN version integer");
		}

		assertThatThrownBy(() -> executeMigration(migrationSql()))
				.isInstanceOf(SQLException.class)
				.hasMessageContaining("listener profile version must be bigint");
	}

	@Test
	void migrationRejectsAnIncompatibleChoiceCompletionColumn() throws Exception {
		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					ALTER TABLE "tbl_listener-profile"
					ADD COLUMN visibility_choice_completed varchar(5)
					""");
		}

		assertThatThrownBy(() -> executeMigration(migrationSql()))
				.isInstanceOf(SQLException.class)
				.hasMessageContaining("visibility_choice_completed must be boolean");
	}

	@Test
	void rerunRepairsGhostFollowerDriftAndAWrongSameNamedIndex() throws Exception {
		String migration = migrationSql();
		executeMigration(migration);

		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			statement.execute("DROP TRIGGER trg_follow_reject_ghost_listener ON tbl_follow");
			statement.execute("DROP TRIGGER trg_listener_profile_purge_followers ON \"tbl_listener-profile\"");
			statement.execute("""
					UPDATE "tbl_listener-profile"
					SET visibility_mode = 'GHOST', version = 9
					WHERE id = '%s'
					""".formatted(PROFILE_ID));
			statement.execute("""
					INSERT INTO tbl_follow (id, follower_id, following_id)
					VALUES ('00000000-0000-0000-0000-000000000501',
					        '00000000-0000-0000-0000-000000000502', '%s')
					""".formatted(USER_ID));
			statement.execute("DROP INDEX idx_follow_following_id");
			statement.execute("CREATE INDEX idx_follow_following_id ON tbl_follow (follower_id)");
		}

		executeMigration(migration);

		assertSchemaContract();
		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			assertThat(singleString(statement, "SELECT count(*)::text FROM tbl_follow"))
					.isEqualTo("0");
			assertThat(singleString(statement, """
					SELECT visibility_mode || ':' || version
					FROM "tbl_listener-profile"
					WHERE id = '%s'
					""".formatted(PROFILE_ID))).isEqualTo("GHOST:9");
		}
	}

	private void assertSchemaContract() throws SQLException {
		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			assertThat(singleString(statement, """
					SELECT data_type || ':' || is_nullable || ':' || column_default
					FROM information_schema.columns
					WHERE table_schema = current_schema()
					  AND table_name = 'tbl_listener-profile'
					  AND column_name = 'visibility_mode'
					""")).isEqualTo("character varying:NO:'STANDARD'::character varying");
			assertThat(singleString(statement, """
					SELECT data_type || ':' || is_nullable || ':' || column_default
					FROM information_schema.columns
					WHERE table_schema = current_schema()
					  AND table_name = 'tbl_listener-profile'
					  AND column_name = 'version'
					""")).isEqualTo("bigint:NO:0");
			assertThat(singleString(statement, """
					SELECT data_type || ':' || is_nullable
					FROM information_schema.columns
					WHERE table_schema = current_schema()
					  AND table_name = 'tbl_listener-profile'
					  AND column_name = 'visibility_changed_at'
					""")).isEqualTo("timestamp without time zone:YES");
			assertThat(singleString(statement, """
					SELECT data_type || ':' || is_nullable || ':' || column_default
					FROM information_schema.columns
					WHERE table_schema = current_schema()
					  AND table_name = 'tbl_listener-profile'
					  AND column_name = 'visibility_choice_completed'
					""")).isEqualTo("boolean:NO:false");
			assertThat(singleString(statement, """
					SELECT indexdef
					FROM pg_indexes
					WHERE schemaname = current_schema()
					  AND tablename = 'tbl_follow'
					  AND indexname = 'idx_follow_following_id'
					"""))
					.contains("USING btree (following_id)")
					.doesNotContain("USING btree (follower_id)");
			assertThat(singleString(statement, """
					SELECT count(*)::text
					FROM pg_trigger
					WHERE tgrelid = 'tbl_follow'::regclass
					  AND tgname = 'trg_follow_reject_ghost_listener'
					  AND NOT tgisinternal
					""")).isEqualTo("1");
			assertThat(singleString(statement, """
					SELECT count(*)::text
					FROM pg_trigger
					WHERE tgrelid = '"tbl_listener-profile"'::regclass
					  AND tgname = 'trg_listener_profile_purge_followers'
					  AND NOT tgisinternal
					""")).isEqualTo("1");
		}
	}

	private static Connection connection() throws SQLException {
		return DriverManager.getConnection(
				POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
	}

	private static String migrationSql() throws Exception {
		return Files.readString(Path.of(
				System.getProperty("user.dir"),
				"scripts", "db", "2026-09-03-listener-ghost-profile.sql"));
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

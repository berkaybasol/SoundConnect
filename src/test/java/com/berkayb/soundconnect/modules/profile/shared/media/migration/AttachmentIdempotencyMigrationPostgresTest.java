package com.berkayb.soundconnect.modules.profile.shared.media.migration;

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
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class AttachmentIdempotencyMigrationPostgresTest {

	@Container
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine")
			.withDatabaseName("soundconnect_attachment_migration")
			.withUsername("soundconnect")
			.withPassword("soundconnect");

	@BeforeEach
	void createLegacySchemaWithDuplicates() throws SQLException {
		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			statement.execute("DROP TABLE IF EXISTS tbl_overthinking_post CASCADE");
			statement.execute("DROP TABLE IF EXISTS tbl_profile_media CASCADE");
			statement.execute("DROP TABLE IF EXISTS tbl_tracks CASCADE");
			statement.execute("""
					CREATE TABLE tbl_profile_media (
					    id uuid PRIMARY KEY,
					    profile_type varchar(32) NOT NULL,
					    profile_id uuid NOT NULL,
					    media_asset_id uuid NOT NULL,
					    role varchar(32) NOT NULL,
					    created_at timestamp without time zone
					)
					""");
			statement.execute("""
					CREATE TABLE tbl_tracks (
					    id uuid PRIMARY KEY,
					    owner_type varchar(32) NOT NULL,
					    owner_id uuid NOT NULL,
					    media_asset_id uuid NOT NULL,
					    title varchar(256) NOT NULL,
					    created_at timestamp without time zone
					)
					""");
			statement.execute("""
					CREATE TABLE tbl_overthinking_post (
					    id uuid PRIMARY KEY,
					    musician_track_id uuid,
					    band_track_id uuid
					)
					""");
			statement.execute("""
					INSERT INTO tbl_profile_media
					    (id, profile_type, profile_id, media_asset_id, role, created_at)
					VALUES
					    ('00000000-0000-0000-0000-000000000102', 'LISTENER',
					     '00000000-0000-0000-0000-000000000201',
					     '00000000-0000-0000-0000-000000000301', 'GALLERY',
					     TIMESTAMP '2026-07-14 09:00:00'),
					    ('00000000-0000-0000-0000-000000000101', 'LISTENER',
					     '00000000-0000-0000-0000-000000000201',
					     '00000000-0000-0000-0000-000000000301', 'GALLERY',
					     TIMESTAMP '2026-07-15 09:00:00')
					""");
			statement.execute("""
					INSERT INTO tbl_tracks
					    (id, owner_type, owner_id, media_asset_id, title, created_at)
					VALUES
					    ('00000000-0000-0000-0000-000000000402', 'MUSICIAN_PROFILE',
					     '00000000-0000-0000-0000-000000000501',
					     '00000000-0000-0000-0000-000000000601', 'First writer',
					     TIMESTAMP '2026-07-14 09:00:00'),
					    ('00000000-0000-0000-0000-000000000401', 'MUSICIAN_PROFILE',
					     '00000000-0000-0000-0000-000000000501',
					     '00000000-0000-0000-0000-000000000601', 'Retry writer',
					     TIMESTAMP '2026-07-15 09:00:00'),
					    ('00000000-0000-0000-0000-000000000412', 'BAND',
					     '00000000-0000-0000-0000-000000000511',
					     '00000000-0000-0000-0000-000000000611', 'Band first writer',
					     TIMESTAMP '2026-07-14 09:00:00'),
					    ('00000000-0000-0000-0000-000000000411', 'BAND',
					     '00000000-0000-0000-0000-000000000511',
					     '00000000-0000-0000-0000-000000000611', 'Band retry writer',
					     TIMESTAMP '2026-07-15 09:00:00')
					""");
			statement.execute("""
					INSERT INTO tbl_overthinking_post (id, musician_track_id, band_track_id)
					VALUES
					    ('00000000-0000-0000-0000-000000001001',
					     '00000000-0000-0000-0000-000000000401', NULL),
					    ('00000000-0000-0000-0000-000000001002',
					     NULL, '00000000-0000-0000-0000-000000000411'),
					    ('00000000-0000-0000-0000-000000001003',
					     '00000000-0000-0000-0000-000000000402',
					     '00000000-0000-0000-0000-000000000412')
					""");
		}
	}

	@Test
	void migrationDeterministicallyDeduplicatesAndIsIdempotent() throws Exception {
		String migration = migrationSql();
		assertThat(migration).contains("BEGIN;", "COMMIT;", "ACCESS EXCLUSIVE");

		executeMigration(migration);
		assertMigratedState();

		// Re-running after an uncertain deployment result must remain harmless.
		executeMigration(migration);
		assertMigratedState();
	}

	@Test
	void uniqueConstraintsSerializeConcurrentDuplicateWriters() throws Exception {
		executeMigration(migrationSql());
		CountDownLatch start = new CountDownLatch(1);
		try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
			Future<Boolean> first = executor.submit(() -> insertConcurrentProfileAttachment(start, "701"));
			Future<Boolean> second = executor.submit(() -> insertConcurrentProfileAttachment(start, "702"));
			start.countDown();

			assertThat(List.of(first.get(), second.get()))
					.containsExactlyInAnyOrder(true, false);
		}

		CountDownLatch trackStart = new CountDownLatch(1);
		try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
			Future<Boolean> first = executor.submit(() -> insertConcurrentTrack(trackStart, "703"));
			Future<Boolean> second = executor.submit(() -> insertConcurrentTrack(trackStart, "704"));
			trackStart.countDown();

			assertThat(List.of(first.get(), second.get()))
					.containsExactlyInAnyOrder(true, false);
		}

		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			assertThat(singleInt(statement, """
					SELECT count(*) FROM tbl_profile_media
					WHERE profile_type = 'LISTENER'
					  AND profile_id = '00000000-0000-0000-0000-000000000801'
					  AND media_asset_id = '00000000-0000-0000-0000-000000000901'
					  AND role = 'GALLERY'
					""")).isEqualTo(1);
			assertThat(singleInt(statement, """
					SELECT count(*) FROM tbl_tracks
					WHERE owner_type = 'MUSICIAN_PROFILE'
					  AND owner_id = '00000000-0000-0000-0000-000000000802'
					  AND media_asset_id = '00000000-0000-0000-0000-000000000902'
					""")).isEqualTo(1);
		}
	}

	@Test
	void aLateFailureRollsBackDeduplicationAndBothConstraints() throws Exception {
		String failingMigration = migrationSql().replaceFirst(
				"(?s)\\RCOMMIT;\\s*$",
				"\nDO \\$\\$ BEGIN RAISE EXCEPTION 'forced attachment migration failure'; END \\$\\$;\nCOMMIT;"
		);

		assertThatThrownBy(() -> executeMigration(failingMigration))
				.isInstanceOf(SQLException.class)
				.hasMessageContaining("forced attachment migration failure");

		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			assertThat(singleInt(statement, "SELECT count(*) FROM tbl_profile_media")).isEqualTo(2);
			assertThat(singleInt(statement, "SELECT count(*) FROM tbl_tracks")).isEqualTo(4);
			assertThat(singleString(statement, """
					SELECT musician_track_id::text FROM tbl_overthinking_post
					WHERE id = '00000000-0000-0000-0000-000000001001'
					""")).isEqualTo("00000000-0000-0000-0000-000000000401");
			assertThat(singleString(statement, """
					SELECT band_track_id::text FROM tbl_overthinking_post
					WHERE id = '00000000-0000-0000-0000-000000001002'
					""")).isEqualTo("00000000-0000-0000-0000-000000000411");
			assertThat(singleInt(statement, """
					SELECT count(*) FROM pg_constraint
					WHERE conname IN ('uk_profile_media_attachment', 'uk_tracks_owner_media_asset')
					""")).isZero();
		}
	}

	private void assertMigratedState() throws SQLException {
		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			assertThat(singleString(statement, "SELECT id::text FROM tbl_profile_media"))
					.isEqualTo("00000000-0000-0000-0000-000000000102");
			assertThat(singleString(statement, """
					SELECT id::text FROM tbl_tracks WHERE owner_type = 'MUSICIAN_PROFILE'
					"""))
					.isEqualTo("00000000-0000-0000-0000-000000000402");
			assertThat(singleString(statement, """
					SELECT title FROM tbl_tracks WHERE owner_type = 'MUSICIAN_PROFILE'
					"""))
					.isEqualTo("First writer");
			assertThat(singleString(statement, """
					SELECT id::text FROM tbl_tracks WHERE owner_type = 'BAND'
					""")).isEqualTo("00000000-0000-0000-0000-000000000412");
			assertThat(singleString(statement, """
					SELECT musician_track_id::text FROM tbl_overthinking_post
					WHERE id = '00000000-0000-0000-0000-000000001001'
					""")).isEqualTo("00000000-0000-0000-0000-000000000402");
			assertThat(singleString(statement, """
					SELECT band_track_id::text FROM tbl_overthinking_post
					WHERE id = '00000000-0000-0000-0000-000000001002'
					""")).isEqualTo("00000000-0000-0000-0000-000000000412");
			assertThat(singleString(statement, """
					SELECT musician_track_id::text || ':' || band_track_id::text
					FROM tbl_overthinking_post
					WHERE id = '00000000-0000-0000-0000-000000001003'
					""")).isEqualTo(
					"00000000-0000-0000-0000-000000000402:00000000-0000-0000-0000-000000000412");
			assertThat(singleInt(statement, """
					SELECT count(*) FROM pg_constraint
					WHERE conname IN ('uk_profile_media_attachment', 'uk_tracks_owner_media_asset')
					  AND contype = 'u'
					  AND convalidated
					""")).isEqualTo(2);
		}
	}

	private boolean insertConcurrentProfileAttachment(CountDownLatch start, String idSuffix) throws Exception {
		start.await();
		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					INSERT INTO tbl_profile_media
					    (id, profile_type, profile_id, media_asset_id, role, created_at)
					VALUES ('00000000-0000-0000-0000-000000000%s', 'LISTENER',
					        '00000000-0000-0000-0000-000000000801',
					        '00000000-0000-0000-0000-000000000901', 'GALLERY', CURRENT_TIMESTAMP)
					""".formatted(idSuffix));
			return true;
		} catch (SQLException duplicate) {
			assertThat(duplicate.getSQLState()).isEqualTo("23505");
			return false;
		}
	}

	private boolean insertConcurrentTrack(CountDownLatch start, String idSuffix) throws Exception {
		start.await();
		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					INSERT INTO tbl_tracks
					    (id, owner_type, owner_id, media_asset_id, title, created_at)
					VALUES ('00000000-0000-0000-0000-000000000%s', 'MUSICIAN_PROFILE',
					        '00000000-0000-0000-0000-000000000802',
					        '00000000-0000-0000-0000-000000000902', 'Concurrent', CURRENT_TIMESTAMP)
					""".formatted(idSuffix));
			return true;
		} catch (SQLException duplicate) {
			assertThat(duplicate.getSQLState()).isEqualTo("23505");
			return false;
		}
	}

	private static Connection connection() throws SQLException {
		return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
	}

	private static String migrationSql() throws Exception {
		return Files.readString(Path.of(
				System.getProperty("user.dir"),
				"scripts", "db", "2026-07-15-attachment-idempotency.sql"
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

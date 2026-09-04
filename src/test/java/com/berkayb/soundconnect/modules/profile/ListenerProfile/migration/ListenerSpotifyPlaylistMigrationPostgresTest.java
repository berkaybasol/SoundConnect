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
class ListenerSpotifyPlaylistMigrationPostgresTest {

	private static final String PROFILE_ID = "00000000-0000-0000-0000-000000000101";
	private static final String SECOND_PROFILE_ID = "00000000-0000-0000-0000-000000000102";
	private static final String PLAYLIST_ID = "37i9dQZF1DXcBWIGoYBM5M";

	@Container
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine")
			.withDatabaseName("soundconnect_listener_playlists")
			.withUsername("soundconnect")
			.withPassword("soundconnect");

	@BeforeEach
	void createLegacySchema() throws SQLException {
		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			statement.execute("DROP TABLE IF EXISTS tbl_listener_spotify_playlist CASCADE");
			statement.execute("DROP TABLE IF EXISTS \"tbl_listener-profile\" CASCADE");
			statement.execute("""
					CREATE TABLE "tbl_listener-profile" (
					    id uuid PRIMARY KEY,
					    user_id uuid NOT NULL UNIQUE
					)
					""");
			statement.execute("""
					INSERT INTO "tbl_listener-profile" (id, user_id) VALUES
					('%s', '00000000-0000-0000-0000-000000000201'),
					('%s', '00000000-0000-0000-0000-000000000202')
					""".formatted(PROFILE_ID, SECOND_PROFILE_ID));
		}
	}

	@Test
	void migrationIsRerunnablePreservesRowsAndInstallsTheCanonicalContract() throws Exception {
		String migration = migrationSql();
		assertThat(migration).contains("BEGIN;", "COMMIT;", "ON DELETE CASCADE");

		execute(migration);
		insertPlaylist(
				"00000000-0000-0000-0000-000000000301",
				PROFILE_ID,
				PLAYLIST_ID,
				0
		);
		execute(migration);

		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			assertThat(singleLong(statement, """
					SELECT playlist_revision FROM "tbl_listener-profile" WHERE id = '%s'
					""".formatted(PROFILE_ID))).isZero();
			assertThat(singleLong(statement, "SELECT count(*) FROM tbl_listener_spotify_playlist"))
					.isEqualTo(1L);
			assertThat(singleString(statement, """
					SELECT spotify_url FROM tbl_listener_spotify_playlist
					WHERE listener_profile_id = '%s'
					""".formatted(PROFILE_ID)))
					.isEqualTo("https://open.spotify.com/playlist/" + PLAYLIST_ID);
		}
	}

	@Test
	void databaseEnforcesFourPositionsDuplicatesCanonicalUrlsAndRevision() throws Exception {
		execute(migrationSql());
		insertPlaylist("00000000-0000-0000-0000-000000000311", PROFILE_ID, PLAYLIST_ID, 0);

		assertThatThrownBy(() -> insertPlaylist(
				"00000000-0000-0000-0000-000000000312", PROFILE_ID, PLAYLIST_ID, 1))
				.isInstanceOf(SQLException.class);
		assertThatThrownBy(() -> insertPlaylist(
				"00000000-0000-0000-0000-000000000315", PROFILE_ID,
				"3333333333333333333333", 0))
				.isInstanceOf(SQLException.class);
		assertThatThrownBy(() -> insertPlaylist(
				"00000000-0000-0000-0000-000000000313", PROFILE_ID,
				"1111111111111111111111", 4))
				.isInstanceOf(SQLException.class);

		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			assertThatThrownBy(() -> statement.execute("""
					INSERT INTO tbl_listener_spotify_playlist (
					    id, listener_profile_id, spotify_playlist_id, title,
					    cover_image_url, spotify_url, position
					) VALUES (
					    '00000000-0000-0000-0000-000000000314',
					    '%s', '2222222222222222222222', 'Wrong URL',
					    'https://i.scdn.co/image/cover',
					    'https://evil.test/playlist/2222222222222222222222', 1
					)
					""".formatted(PROFILE_ID))).isInstanceOf(SQLException.class);
			assertThatThrownBy(() -> statement.execute("""
					INSERT INTO tbl_listener_spotify_playlist (
					    id, listener_profile_id, spotify_playlist_id, title,
					    cover_image_url, spotify_url, position
					) VALUES (
					    '00000000-0000-0000-0000-000000000316',
					    '%s', '4444444444444444444444', 'Untrusted cover',
					    'https://i.scdn.co.evil.test/image/cover',
					    'https://open.spotify.com/playlist/4444444444444444444444', 1
					)
					""".formatted(PROFILE_ID))).isInstanceOf(SQLException.class);
			assertThatThrownBy(() -> statement.execute("""
					UPDATE "tbl_listener-profile" SET playlist_revision = -1 WHERE id = '%s'
					""".formatted(PROFILE_ID))).isInstanceOf(SQLException.class);
		}
	}

	@Test
	void deletingAListenerProfileCascadesItsPlaylistSnapshots() throws Exception {
		execute(migrationSql());
		insertPlaylist("00000000-0000-0000-0000-000000000321", PROFILE_ID, PLAYLIST_ID, 0);

		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			statement.execute("DELETE FROM \"tbl_listener-profile\" WHERE id = '" + PROFILE_ID + "'");
			assertThat(singleLong(statement, "SELECT count(*) FROM tbl_listener_spotify_playlist"))
					.isZero();
		}
	}

	@Test
	void migrationRejectsNonContiguousLegacyPositions() throws Exception {
		execute(migrationSql());
		insertPlaylist("00000000-0000-0000-0000-000000000331", PROFILE_ID, PLAYLIST_ID, 0);
		insertPlaylist(
				"00000000-0000-0000-0000-000000000332",
				PROFILE_ID,
				"1111111111111111111111",
				2
		);

		assertThatThrownBy(() -> execute(migrationSql()))
				.isInstanceOf(SQLException.class)
				.hasMessageContaining("positions must be contiguous from zero");
	}

	@Test
	void migrationNormalizesAnArbitrarilyNamedNoActionForeignKey() throws Exception {
		execute(migrationSql());
		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					ALTER TABLE tbl_listener_spotify_playlist
					DROP CONSTRAINT fk_listener_spotify_playlist_profile
					""");
			statement.execute("""
					ALTER TABLE tbl_listener_spotify_playlist
					ADD CONSTRAINT fk_hibernate_generated_7f91
					FOREIGN KEY (listener_profile_id)
					REFERENCES "tbl_listener-profile" (id)
					ON DELETE NO ACTION
					""");
		}

		execute(migrationSql());

		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			assertThat(singleLong(statement, """
					SELECT count(*)
					FROM pg_constraint constraint_row
					WHERE constraint_row.contype = 'f'
					  AND constraint_row.conrelid = 'tbl_listener_spotify_playlist'::regclass
					  AND constraint_row.confrelid = '"tbl_listener-profile"'::regclass
					  AND constraint_row.confdeltype = 'c'
					""")).isEqualTo(1L);
			assertThat(singleString(statement, """
					SELECT constraint_row.conname
					FROM pg_constraint constraint_row
					WHERE constraint_row.contype = 'f'
					  AND constraint_row.conrelid = 'tbl_listener_spotify_playlist'::regclass
					  AND constraint_row.confrelid = '"tbl_listener-profile"'::regclass
					""")).isEqualTo("fk_listener_spotify_playlist_profile");
		}
	}

	@Test
	void migrationRejectsAnIncompatiblePartialRolloutAtomically() throws Exception {
		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			statement.execute("ALTER TABLE \"tbl_listener-profile\" ADD COLUMN playlist_revision integer");
		}

		assertThatThrownBy(() -> execute(migrationSql()))
				.isInstanceOf(SQLException.class)
				.hasMessageContaining("listener playlist revision must be bigint");

		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			assertThat(singleString(statement, "SELECT to_regclass('tbl_listener_spotify_playlist')::text"))
					.isNull();
		}
	}

	private void insertPlaylist(String rowId, String profileId, String spotifyId, int position)
			throws SQLException {
		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					INSERT INTO tbl_listener_spotify_playlist (
					    id, listener_profile_id, spotify_playlist_id, title,
					    cover_image_url, spotify_url, position
					) VALUES (
					    '%s', '%s', '%s', 'Playlist',
					    'https://i.scdn.co/image/cover',
					    'https://open.spotify.com/playlist/%s', %d
					)
					""".formatted(rowId, profileId, spotifyId, spotifyId, position));
		}
	}

	private void execute(String sql) throws SQLException {
		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			statement.execute(sql);
		}
	}

	private Connection connection() throws SQLException {
		return DriverManager.getConnection(
				POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
	}

	private String migrationSql() throws Exception {
		return Files.readString(Path.of(
				System.getProperty("user.dir"),
				"scripts", "db", "2026-09-04-listener-spotify-playlists.sql"
		));
	}

	private long singleLong(Statement statement, String sql) throws SQLException {
		try (ResultSet resultSet = statement.executeQuery(sql)) {
			resultSet.next();
			return resultSet.getLong(1);
		}
	}

	private String singleString(Statement statement, String sql) throws SQLException {
		try (ResultSet resultSet = statement.executeQuery(sql)) {
			resultSet.next();
			return resultSet.getString(1);
		}
	}
}

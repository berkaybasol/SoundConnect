package com.berkayb.soundconnect.modules.media.migration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class MediaLifecycleMigrationPostgresTest {

	@Container
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine")
			.withDatabaseName("soundconnect_migration")
			.withUsername("soundconnect")
			.withPassword("soundconnect");

	@BeforeEach
	void createLegacySchema() throws SQLException {
		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			statement.execute("DROP TABLE IF EXISTS tbl_media_asset CASCADE");
			statement.execute("""
					CREATE TABLE tbl_media_asset (
					    id uuid PRIMARY KEY,
					    status varchar(16) NOT NULL,
					    kind varchar(16) NOT NULL,
					    visibility varchar(16) NOT NULL,
					    updated_at timestamp without time zone,
					    CONSTRAINT legacy_media_status_membership_ck
					        CHECK (status IN ('UPLOADING', 'VERIFYING', 'PROCESSING', 'READY', 'FAILED'))
					)
					""");
			statement.execute("""
					INSERT INTO tbl_media_asset (id, status, kind, visibility, updated_at) VALUES
					    ('00000000-0000-0000-0000-000000000101', 'PROCESSING', 'VIDEO', 'PUBLIC',
					     TIMESTAMP '2026-07-15 08:00:00'),
					    ('00000000-0000-0000-0000-000000000102', 'VERIFYING', 'IMAGE', 'PUBLIC',
					     TIMESTAMP '2026-07-15 08:00:00')
					""");
		}
	}

	@Test
	void migrationIsAtomicIdempotentAndSupportsAConstraintWithAnUnknownLegacyName() throws Exception {
		String migration = migrationSql();
		assertThat(migration).startsWith("-- SoundConnect media lifecycle status rollout.");
		assertThat(migration).contains("BEGIN;", "COMMIT;");
		assertThat(migration).doesNotContain("gen_random_uuid(");

		executeMigration(migration);

		UUID firstVerificationToken;
		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			try (ResultSet row = statement.executeQuery("""
					SELECT status, transcode_attempt_count, transcode_retry_pending,
					       transcode_retain_source_after_cleanup,
					       transcode_attempt_deadline, transcode_cleanup_not_before
					FROM tbl_media_asset
					WHERE id = '00000000-0000-0000-0000-000000000101'
					""")) {
				assertThat(row.next()).isTrue();
				assertThat(row.getString("status")).isEqualTo("HLS_CLEANUP");
				assertThat(row.getInt("transcode_attempt_count")).isEqualTo(1);
				assertThat(row.getBoolean("transcode_retry_pending")).isTrue();
				assertThat(row.getBoolean("transcode_retain_source_after_cleanup")).isFalse();
				assertThat(row.getTimestamp("transcode_cleanup_not_before"))
						.isAfterOrEqualTo(row.getTimestamp("transcode_attempt_deadline"));
			}

			try (ResultSet row = statement.executeQuery("""
					SELECT upload_verification_attempt_token,
					       upload_verification_lease_expires_at,
					       upload_verification_attempt_deadline,
					       upload_verification_cleanup_not_before
					FROM tbl_media_asset
					WHERE id = '00000000-0000-0000-0000-000000000102'
					""")) {
				assertThat(row.next()).isTrue();
				firstVerificationToken = row.getObject("upload_verification_attempt_token", UUID.class);
				assertThat(firstVerificationToken).isNotNull();
				assertThat(row.getTimestamp("upload_verification_lease_expires_at")).isNotNull();
				assertThat(row.getTimestamp("upload_verification_attempt_deadline")).isNotNull();
				assertThat(row.getTimestamp("upload_verification_cleanup_not_before")).isNotNull();
			}

			statement.execute("""
					INSERT INTO tbl_media_asset (id, status, kind, visibility, updated_at)
					VALUES ('00000000-0000-0000-0000-000000000103', 'DELETION_PENDING',
					        'IMAGE', 'PRIVATE', TIMESTAMP '2026-07-15 08:00:00')
					""");
		}

		// A second execution is the failure-recovery path used after an uncertain
		// deployment result. It must not regenerate tokens or duplicate metadata.
		executeMigration(migration);

		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			assertThat(singleString(statement, """
					SELECT upload_verification_attempt_token::text
					FROM tbl_media_asset
					WHERE id = '00000000-0000-0000-0000-000000000102'
					""")).isEqualTo(firstVerificationToken.toString());

			assertThat(singleInt(statement, """
					SELECT count(*)
					FROM pg_constraint
					WHERE conrelid = 'tbl_media_asset'::regclass
					  AND conname = 'tbl_media_asset_status_check'
					  AND convalidated
					""")).isEqualTo(1);

			assertThat(singleInt(statement, """
					SELECT count(*)
					FROM pg_indexes
					WHERE tablename = 'tbl_media_asset'
					  AND indexname IN (
					      'idx_media_transcode_lease',
					      'idx_media_verification_lease',
					      'idx_media_verification_cleanup'
					  )
					""")).isEqualTo(3);

			assertThat(singleInt(statement,
					"SELECT count(*) FROM pg_extension WHERE extname = 'pgcrypto'"))
					.isZero();
		}
	}

	@Test
	void aLateFailureRollsBackAllSchemaAndDataChanges() throws Exception {
		String failingMigration = migrationSql().replaceFirst(
				"(?s)\\RCOMMIT;\\s*$",
				"\nDO \\$\\$ BEGIN RAISE EXCEPTION 'forced migration failure'; END \\$\\$;\nCOMMIT;"
		);

		assertThatThrownBy(() -> executeMigration(failingMigration))
				.isInstanceOf(SQLException.class)
				.hasMessageContaining("forced migration failure");

		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			assertThat(singleInt(statement, """
					SELECT count(*)
					FROM information_schema.columns
					WHERE table_schema = current_schema()
					  AND table_name = 'tbl_media_asset'
					  AND column_name = 'deletion_requested_at'
					""")).isZero();
			assertThat(singleString(statement, """
					SELECT status FROM tbl_media_asset
					WHERE id = '00000000-0000-0000-0000-000000000101'
					""")).isEqualTo("PROCESSING");
			assertThat(singleInt(statement, """
					SELECT count(*) FROM pg_constraint
					WHERE conrelid = 'tbl_media_asset'::regclass
					  AND conname = 'legacy_media_status_membership_ck'
					""")).isEqualTo(1);
		}
	}

	private static Connection connection() throws SQLException {
		return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
	}

	private static String migrationSql() throws IOException {
		return Files.readString(Path.of(
				System.getProperty("user.dir"),
				"scripts", "db", "2026-07-15-media-status-lifecycle.sql"
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

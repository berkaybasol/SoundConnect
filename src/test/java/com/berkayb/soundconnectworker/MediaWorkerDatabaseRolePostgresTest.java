package com.berkayb.soundconnectworker;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class MediaWorkerDatabaseRolePostgresTest {

	private static final String WORKER_USER = "media_worker_test";
	private static final String WORKER_PASSWORD = "worker-local_test.123";

	@Container
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine")
			.withDatabaseName("soundconnect")
			.withUsername("api_owner")
			.withPassword("admin-local_test.123")
			.withEnv("POSTGRES_HOST", "127.0.0.1")
			.withEnv("POSTGRES_ADMIN_USER", "api_owner")
			.withEnv("POSTGRES_ADMIN_PASSWORD", "admin-local_test.123")
			.withEnv("SOUNDCONNECT_MEDIA_WORKER_POSTGRES_USERNAME", WORKER_USER)
			.withEnv("SOUNDCONNECT_MEDIA_WORKER_POSTGRES_PASSWORD", WORKER_PASSWORD)
			.withCopyFileToContainer(
					MountableFile.forHostPath(Path.of(
							"docker", "postgres", "configure-media-worker-role.sh").toAbsolutePath()),
					"/opt/soundconnect/configure-media-worker-role.sh");

	@BeforeAll
	static void createSchemaAndApplyActualGrantScript() throws Exception {
		try (var connection = DriverManager.getConnection(
				POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
			 var statement = connection.createStatement()) {
			statement.execute("""
					create table tbl_media_asset (
					  id uuid primary key,
					  status varchar(32),
					  updated_at timestamp,
					  playback_url text,
					  thumbnail_url text,
					  duration_seconds integer,
					  width integer,
					  height integer,
					  streaming_protocol varchar(32),
					  transcode_attempt_token uuid,
					  transcode_lease_until timestamp,
					  transcode_attempt_deadline timestamp,
					  transcode_cleanup_not_before timestamp,
					  transcode_attempt_count integer not null default 0,
					  transcode_retry_pending boolean not null default false,
					  transcode_retain_source_after_cleanup boolean not null default false,
					  storage_key text
					)
					""");
			statement.execute("insert into tbl_media_asset(id, status, storage_key) values ('"
					+ UUID.randomUUID() + "', 'TRANSCODE_QUEUED', 'verified/media/source')");
		}

		var first = POSTGRES.execInContainer(
				"sh", "/opt/soundconnect/configure-media-worker-role.sh");
		assertThat(first.getExitCode()).as(first.getStderr()).isZero();
		// Password rotation/grants are intentionally idempotent on every deployment.
		var second = POSTGRES.execInContainer(
				"sh", "/opt/soundconnect/configure-media-worker-role.sh");
		assertThat(second.getExitCode()).as(second.getStderr()).isZero();
	}

	@Test
	void roleCanReadAndUpdateEveryWorkerLifecycleColumn() throws Exception {
		try (var connection = DriverManager.getConnection(
				POSTGRES.getJdbcUrl(), WORKER_USER, WORKER_PASSWORD);
			 var statement = connection.createStatement()) {
			assertThat(statement.executeQuery("select count(*) from tbl_media_asset").next()).isTrue();
			assertThat(statement.executeUpdate("""
					update tbl_media_asset set
					  status = 'PROCESSING',
					  updated_at = current_timestamp,
					  playback_url = 'https://cdn.invalid/master.m3u8',
					  thumbnail_url = 'https://cdn.invalid/thumbnail.jpg',
					  duration_seconds = 30,
					  width = 1280,
					  height = 720,
					  streaming_protocol = 'HLS',
					  transcode_attempt_token = gen_random_uuid(),
					  transcode_lease_until = current_timestamp,
					  transcode_attempt_deadline = current_timestamp,
					  transcode_cleanup_not_before = current_timestamp,
					  transcode_attempt_count = 1,
					  transcode_retry_pending = false,
					  transcode_retain_source_after_cleanup = false
					""")).isEqualTo(1);
		}
	}

	@Test
	void roleCannotMutateSourceInsertOrDeleteRows() throws Exception {
		try (var connection = DriverManager.getConnection(
				POSTGRES.getJdbcUrl(), WORKER_USER, WORKER_PASSWORD);
			 var statement = connection.createStatement()) {
			assertThatThrownBy(() -> statement.executeUpdate(
					"update tbl_media_asset set storage_key = 'stolen'"))
					.hasMessageContaining("permission denied");
			assertThatThrownBy(() -> statement.executeUpdate(
					"delete from tbl_media_asset"))
					.hasMessageContaining("permission denied");
			assertThatThrownBy(() -> statement.executeUpdate(
					"insert into tbl_media_asset(id, status) values (gen_random_uuid(), 'READY')"))
					.hasMessageContaining("permission denied");
		}
	}
}

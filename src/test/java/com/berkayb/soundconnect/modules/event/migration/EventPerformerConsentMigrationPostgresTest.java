package com.berkayb.soundconnect.modules.event.migration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class EventPerformerConsentMigrationPostgresTest {

	@Container
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine")
			.withDatabaseName("soundconnect_event_consent")
			.withUsername("soundconnect")
			.withPassword("soundconnect");

	@BeforeEach
	void createLegacySchema() throws SQLException {
		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			statement.execute("DROP TABLE IF EXISTS tbl_event_performer_notification_outbox CASCADE");
			statement.execute("DROP TABLE IF EXISTS event_performer_requests CASCADE");
			statement.execute("DROP TABLE IF EXISTS tbl_event CASCADE");
			statement.execute("DROP TABLE IF EXISTS musician_profile_venues CASCADE");
			statement.execute("DROP TABLE IF EXISTS venue_active_bands CASCADE");
			statement.execute("DROP TABLE IF EXISTS tbl_band_member CASCADE");
			statement.execute("DROP TABLE IF EXISTS tbl_venues CASCADE");
			statement.execute("DROP TABLE IF EXISTS tbl_musician_profile CASCADE");
			statement.execute("DROP TABLE IF EXISTS tbl_band CASCADE");
			statement.execute("DROP TABLE IF EXISTS tbl_user CASCADE");
			statement.execute("CREATE TABLE tbl_user (id uuid PRIMARY KEY, user_name varchar(255) NOT NULL)");
			statement.execute("""
					CREATE TABLE tbl_musician_profile (
					 id uuid PRIMARY KEY,
					 user_id uuid NOT NULL REFERENCES tbl_user(id),
					 stage_name varchar(255),
					 name varchar(255)
					)
					""");
			statement.execute("CREATE TABLE tbl_band (id uuid PRIMARY KEY, name varchar(100) NOT NULL)");
			statement.execute("""
					CREATE TABLE tbl_band_member (
					 id uuid PRIMARY KEY,
					 band_id uuid NOT NULL REFERENCES tbl_band(id),
					 user_id uuid NOT NULL REFERENCES tbl_user(id),
					 band_role varchar(30) NOT NULL,
					 status varchar(30) NOT NULL,
					 UNIQUE (band_id, user_id)
					)
					""");
			statement.execute("""
					CREATE TABLE tbl_venues (
					 id uuid PRIMARY KEY,
					 owner_id uuid NOT NULL REFERENCES tbl_user(id),
					 name varchar(255) NOT NULL
					)
					""");
			statement.execute("""
					CREATE TABLE musician_profile_venues (
					 musician_profile_id uuid NOT NULL REFERENCES tbl_musician_profile(id),
					 venue_id uuid NOT NULL REFERENCES tbl_venues(id),
					 PRIMARY KEY (musician_profile_id, venue_id)
					)
					""");
			statement.execute("""
					CREATE TABLE venue_active_bands (
					 venue_id uuid NOT NULL REFERENCES tbl_venues(id),
					 band_id uuid NOT NULL REFERENCES tbl_band(id),
					 PRIMARY KEY (venue_id, band_id)
					)
					""");
			statement.execute("""
					CREATE TABLE tbl_event (
					    id uuid PRIMARY KEY,
					    venue_id uuid NOT NULL REFERENCES tbl_venues(id),
					    title varchar(255) NOT NULL,
					    musician_profile_id uuid,
					    band_id uuid,
					    manual_performer_name varchar(50)
					)
					""");
			statement.execute("""
					INSERT INTO tbl_user(id, user_name) VALUES
					 ('00000000-0000-0000-0000-000000000001', 'venueowner'),
					 ('00000000-0000-0000-0000-000000000002', 'decider'),
					 ('00000000-0000-0000-0000-000000000003', 'bugrasahin'),
					 ('00000000-0000-0000-0000-000000000004', 'inactivefounder')
					""");
			statement.execute("""
					INSERT INTO tbl_musician_profile(id, user_id, stage_name, name) VALUES
					 ('10000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000003', 'Buğra Şahin', null)
					""");
			statement.execute("INSERT INTO tbl_band(id, name) VALUES ('20000000-0000-0000-0000-000000000001', 'Şahbaz')");
			statement.execute("""
					INSERT INTO tbl_band_member(id, band_id, user_id, band_role, status) VALUES
					 ('21000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000002', 'FOUNDER', 'ACTIVE'),
					 ('21000000-0000-0000-0000-000000000002', '20000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000003', 'MEMBER', 'ACTIVE'),
					 ('21000000-0000-0000-0000-000000000003', '20000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000004', 'FOUNDER', 'LEFT')
					""");
			statement.execute("""
					INSERT INTO tbl_venues(id, owner_id, name) VALUES
					 ('50000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', 'Bağlantılı Mekan'),
					 ('50000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000001', 'SoundConnect Ankara')
					""");
			statement.execute("""
					INSERT INTO musician_profile_venues(musician_profile_id, venue_id) VALUES
					 ('10000000-0000-0000-0000-000000000001', '50000000-0000-0000-0000-000000000001')
					""");
			statement.execute("""
					INSERT INTO venue_active_bands(venue_id, band_id) VALUES
					 ('50000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000001')
					""");
			statement.execute("""
					INSERT INTO tbl_event(id, venue_id, title, musician_profile_id, band_id, manual_performer_name) VALUES
					 ('30000000-0000-0000-0000-000000000001', '50000000-0000-0000-0000-000000000001', 'Bağlantılı Müzisyen', '10000000-0000-0000-0000-000000000001', null, null),
					 ('30000000-0000-0000-0000-000000000002', '50000000-0000-0000-0000-000000000002', 'Manuel Etkinlik', null, null, 'Bağımsız Sanatçı'),
					 ('30000000-0000-0000-0000-000000000003', '50000000-0000-0000-0000-000000000002', 'Müzisyen Gecesi', '10000000-0000-0000-0000-000000000001', null, null),
					 ('30000000-0000-0000-0000-000000000004', '50000000-0000-0000-0000-000000000001', 'Bağlantılı Grup', null, '20000000-0000-0000-0000-000000000001', null),
					 ('30000000-0000-0000-0000-000000000005', '50000000-0000-0000-0000-000000000002', 'Grup Gecesi', null, '20000000-0000-0000-0000-000000000001', null)
					""");
		}
	}

	@Test
	void migrationIsRerunnableBackfillsLegacyEventsAndEnforcesConsentFences() throws Exception {
		String migration = migrationSql();
		assertThat(migration).contains("BEGIN;", "COMMIT;", "ON DELETE CASCADE", "ON DELETE RESTRICT");
		execute(migration);

		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			assertThat(singleString(statement, "SELECT to_regclass('tbl_event_performer_notification_outbox')::text"))
					.isEqualTo("tbl_event_performer_notification_outbox");
			assertThat(singleString(statement, """
					SELECT data_type FROM information_schema.columns
					WHERE table_schema = current_schema()
					  AND table_name = 'tbl_event_performer_notification_outbox'
					  AND column_name = 'occurred_at'
					""")).isEqualTo("timestamp with time zone");
			assertThat(singleString(statement, """
					SELECT performer_approval_status FROM tbl_event
					WHERE id = '30000000-0000-0000-0000-000000000001'
					""")).isEqualTo("APPROVED");
			assertThat(singleString(statement, """
					SELECT performer_approval_status FROM tbl_event
					WHERE id = '30000000-0000-0000-0000-000000000002'
					""")).isEqualTo("NOT_REQUIRED");
			assertThat(singleString(statement, """
					SELECT performer_approval_status FROM tbl_event
					WHERE id = '30000000-0000-0000-0000-000000000003'
					""")).isEqualTo("PENDING");
			assertThat(singleString(statement, """
					SELECT manual_performer_name FROM tbl_event
					WHERE id = '30000000-0000-0000-0000-000000000003'
					""")).isEqualTo("bugrasahin");
			assertThat(singleLong(statement, """
					SELECT count(*) FROM tbl_event
					WHERE id = '30000000-0000-0000-0000-000000000003'
					  AND musician_profile_id IS NULL AND band_id IS NULL
					""")).isEqualTo(1);
			assertThat(singleString(statement, """
					SELECT performer_approval_status FROM tbl_event
					WHERE id = '30000000-0000-0000-0000-000000000004'
					""")).isEqualTo("APPROVED");
			assertThat(singleString(statement, """
					SELECT manual_performer_name FROM tbl_event
					WHERE id = '30000000-0000-0000-0000-000000000005'
					""")).isEqualTo("Şahbaz");
			assertThat(singleLong(statement, """
					SELECT count(*) FROM event_performer_requests
					WHERE status = 'PENDING'
					  AND requested_by_user_id = '00000000-0000-0000-0000-000000000001'
					""")).isEqualTo(2);
			assertThat(singleLong(statement, "SELECT count(*) FROM tbl_event_performer_notification_outbox"))
					.isEqualTo(2);
			assertThat(singleLong(statement, """
					SELECT count(*)
					FROM tbl_event_performer_notification_outbox
					WHERE recipient_id = '00000000-0000-0000-0000-000000000003'
					  AND notification_type = 'EVENT_PERFORMER_APPROVAL_REQUESTED'
					  AND payload ->> 'eventId' = '30000000-0000-0000-0000-000000000003'
					  AND payload ->> 'musicianProfileId' = '10000000-0000-0000-0000-000000000001'
					  AND payload ->> 'performerType' = 'MUSICIAN'
					  AND payload ->> 'action' = 'APPROVAL_REQUESTED'
					  AND payload ->> 'status' = 'PENDING'
					  AND payload -> 'availableActions' = '["ACCEPT", "REJECT"]'::jsonb
					  AND title = 'Etkinlik katılım onayı'
					  AND message = 'SoundConnect Ankara, “Müzisyen Gecesi” etkinliğine seni eklemek istiyor.'
					""")).isEqualTo(1);
			assertThat(singleLong(statement, """
					SELECT count(*)
					FROM tbl_event_performer_notification_outbox
					WHERE recipient_id = '00000000-0000-0000-0000-000000000002'
					  AND notification_type = 'EVENT_PERFORMER_APPROVAL_REQUESTED'
					  AND payload ->> 'eventId' = '30000000-0000-0000-0000-000000000005'
					  AND payload ->> 'bandId' = '20000000-0000-0000-0000-000000000001'
					  AND payload ->> 'performerType' = 'BAND'
					  AND title = 'Grubunuz için etkinlik katılım onayı'
					  AND message = 'SoundConnect Ankara, “Grup Gecesi” etkinliğine “Şahbaz” adlı grubunuzu eklemek istiyor.'
					""")).isEqualTo(1);
			assertThat(singleLong(statement, """
					SELECT count(*)
					FROM tbl_event_performer_notification_outbox
					WHERE payload ->> 'eventId' = '30000000-0000-0000-0000-000000000005'
					  AND recipient_id <> '00000000-0000-0000-0000-000000000002'
					""")).isZero();

			String musicianRequestId = singleString(statement, """
					SELECT id::text FROM event_performer_requests
					WHERE event_id = '30000000-0000-0000-0000-000000000003'
					""");
			UUID expectedMusicianNotificationId = UUID.nameUUIDFromBytes((
					"EVENT_PERFORMER|APPROVAL_REQUESTED|30000000-0000-0000-0000-000000000003|"
							+ musicianRequestId
							+ "|00000000-0000-0000-0000-000000000003"
			).getBytes(StandardCharsets.UTF_8));
			assertThat(singleString(statement, """
					SELECT event_id::text FROM tbl_event_performer_notification_outbox
					WHERE payload ->> 'eventId' = '30000000-0000-0000-0000-000000000003'
					""")).isEqualTo(expectedMusicianNotificationId.toString());
			assertThat(singleString(statement, """
					SELECT character_maximum_length::text FROM information_schema.columns
					WHERE table_schema = current_schema() AND table_name = 'tbl_event'
					  AND column_name = 'manual_performer_name'
					""")).isEqualTo("120");

			assertThatThrownBy(() -> statement.execute("""
					UPDATE tbl_event SET musician_profile_id = '10000000-0000-0000-0000-000000000001'
					WHERE id = '30000000-0000-0000-0000-000000000003'
					""")).isInstanceOf(SQLException.class);
			assertThatThrownBy(() -> statement.execute("""
					INSERT INTO event_performer_requests(
					 id, event_id, musician_profile_id, band_id, performer_name_snapshot,
					 status, requested_by_user_id, version
					) VALUES (
					 '40000000-0000-0000-0000-000000000002',
					 '30000000-0000-0000-0000-000000000002',
					 '10000000-0000-0000-0000-000000000001',
					 '20000000-0000-0000-0000-000000000001',
					 'Geçersiz', 'PENDING', '00000000-0000-0000-0000-000000000001', 0
					)
					""")).isInstanceOf(SQLException.class);
			assertThatThrownBy(() -> statement.execute("""
					INSERT INTO tbl_event_performer_notification_outbox(
					 event_id, recipient_id, notification_type, title, message, payload,
					 email_force, occurred_at, status, attempt_count, next_attempt_at,
					 created_at, updated_at
					) VALUES (
					 '60000000-0000-0000-0000-000000000001',
					 '00000000-0000-0000-0000-000000000003',
					 'EVENT_PERFORMER_APPROVAL_REQUESTED', 'Onay', 'Mesaj', '[]'::jsonb,
					 false, now(), 'PENDING', 0, now(), now(), now()
					)
					""")).isInstanceOf(SQLException.class);
			assertThatThrownBy(() -> statement.execute("""
					INSERT INTO tbl_event_performer_notification_outbox(
					 event_id, recipient_id, notification_type, title, message, payload,
					 email_force, occurred_at, status, attempt_count, next_attempt_at,
					 lease_owner, lease_until, created_at, updated_at
					) VALUES (
					 '60000000-0000-0000-0000-000000000002',
					 '00000000-0000-0000-0000-000000000003',
					 'EVENT_PERFORMER_APPROVAL_REQUESTED', 'Onay', 'Mesaj', '{}'::jsonb,
					 false, now(), 'PENDING', 0, now(), 'stale-worker', now(), now(), now()
					)
					""")).isInstanceOf(SQLException.class);
		}

		// Existing valid data survives a second rollout.
		execute(migration);
		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			assertThat(singleLong(statement, "SELECT count(*) FROM event_performer_requests")).isEqualTo(2);
			assertThat(singleLong(statement, "SELECT count(*) FROM tbl_event_performer_notification_outbox"))
					.isEqualTo(2);
			assertThat(singleLong(statement, """
					SELECT count(DISTINCT event_id)
					FROM tbl_event_performer_notification_outbox
					WHERE notification_type = 'EVENT_PERFORMER_APPROVAL_REQUESTED'
					""")).isEqualTo(2);
			assertThatThrownBy(() -> statement.execute(
					"DELETE FROM tbl_musician_profile WHERE id = '10000000-0000-0000-0000-000000000001'"
			)).isInstanceOf(SQLException.class);
			statement.execute("DELETE FROM tbl_event WHERE id = '30000000-0000-0000-0000-000000000003'");
			assertThat(singleLong(statement, "SELECT count(*) FROM event_performer_requests")).isEqualTo(1);
		}
	}

	@Test
	void migrationRejectsAnUnconnectedLegacyBandWithoutAnActiveFounder() throws Exception {
		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			statement.executeUpdate("""
					UPDATE tbl_band_member
					SET status = 'LEFT'
					WHERE band_id = '20000000-0000-0000-0000-000000000001'
					  AND band_role = 'FOUNDER'
					""");
		}

		assertThatThrownBy(() -> execute(migrationSql()))
				.isInstanceOf(SQLException.class)
				.hasMessageContaining(
						"Unconnected legacy band event has no active founder to decide performer approval"
				);

		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			assertThat(singleString(statement, "SELECT to_regclass('event_performer_requests')::text"))
					.isNull();
		}
	}

	@Test
	void migrationRejectsRatherThanSilentlyTruncatingALegacyPerformerName() throws Exception {
		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			statement.executeUpdate("""
					UPDATE tbl_user
					SET user_name = repeat('x', 101)
					WHERE id = '00000000-0000-0000-0000-000000000003'
					""");
		}

		assertThatThrownBy(() -> execute(migrationSql()))
				.isInstanceOf(SQLException.class)
				.hasMessageContaining(
						"Unconnected legacy performer name exceeds the 100 character consent snapshot limit"
				);

		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			assertThat(singleString(statement, "SELECT to_regclass('event_performer_requests')::text"))
					.isNull();
			assertThat(singleString(statement, """
					SELECT musician_profile_id::text FROM tbl_event
					WHERE id = '30000000-0000-0000-0000-000000000003'
					""")).isEqualTo("10000000-0000-0000-0000-000000000001");
		}
	}

	@Test
	void connectionRowShareLockSerializesAConcurrentDisconnect() throws Exception {
		try (Connection eventCreation = connection(); Connection disconnect = connection()) {
			eventCreation.setAutoCommit(false);
			try (Statement statement = eventCreation.createStatement()) {
				statement.executeQuery("""
						SELECT 1 FROM musician_profile_venues
						WHERE musician_profile_id = '10000000-0000-0000-0000-000000000001'
						  AND venue_id = '50000000-0000-0000-0000-000000000001'
						FOR KEY SHARE
						""").close();
			}

			try (Statement statement = disconnect.createStatement()) {
				statement.execute("SET lock_timeout TO '250ms'");
				assertThatThrownBy(() -> statement.executeUpdate("""
						DELETE FROM musician_profile_venues
						WHERE musician_profile_id = '10000000-0000-0000-0000-000000000001'
						  AND venue_id = '50000000-0000-0000-0000-000000000001'
						""")).isInstanceOf(SQLException.class);
			}

			eventCreation.rollback();
			try (Statement statement = disconnect.createStatement()) {
				assertThat(statement.executeUpdate("""
						DELETE FROM musician_profile_venues
						WHERE musician_profile_id = '10000000-0000-0000-0000-000000000001'
						  AND venue_id = '50000000-0000-0000-0000-000000000001'
						""")).isEqualTo(1);
			}
		}
	}

	private static Connection connection() throws SQLException {
		return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
	}

	private static String migrationSql() throws Exception {
		return Files.readString(Path.of(
				System.getProperty("user.dir"), "scripts", "db", "2026-09-04-event-performer-consent.sql"
		));
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

	private static long singleLong(Statement statement, String sql) throws SQLException {
		try (ResultSet result = statement.executeQuery(sql)) {
			assertThat(result.next()).isTrue();
			return result.getLong(1);
		}
	}
}

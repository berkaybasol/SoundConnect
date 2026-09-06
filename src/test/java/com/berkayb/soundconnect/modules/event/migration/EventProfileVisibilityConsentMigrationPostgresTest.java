package com.berkayb.soundconnect.modules.event.migration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class EventProfileVisibilityConsentMigrationPostgresTest {
	@Container
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine")
			.withDatabaseName("soundconnect_profile_visibility").withUsername("soundconnect").withPassword("soundconnect");

	@BeforeEach
	void existingConsentSchema() throws Exception {
		execute("""
				DROP SCHEMA public CASCADE;
				CREATE SCHEMA public;
				CREATE TABLE tbl_user(id uuid PRIMARY KEY, user_name varchar(255) NOT NULL);
				CREATE TABLE tbl_musician_profile(id uuid PRIMARY KEY, user_id uuid NOT NULL REFERENCES tbl_user(id), stage_name varchar(255), name varchar(255));
				CREATE TABLE tbl_band(id uuid PRIMARY KEY, name varchar(100) NOT NULL);
				CREATE TABLE tbl_band_member(id uuid PRIMARY KEY, band_id uuid REFERENCES tbl_band(id), user_id uuid REFERENCES tbl_user(id), status varchar(30), band_role varchar(30));
				CREATE TABLE tbl_venues(id uuid PRIMARY KEY, owner_id uuid REFERENCES tbl_user(id), name varchar(255));
				CREATE TABLE musician_profile_venues(musician_profile_id uuid, venue_id uuid);
				CREATE TABLE venue_active_bands(band_id uuid, venue_id uuid);
				CREATE TABLE tbl_event(id uuid PRIMARY KEY, venue_id uuid REFERENCES tbl_venues(id), title varchar(255), musician_profile_id uuid, band_id uuid, manual_performer_name varchar(120), event_date date NOT NULL DEFAULT CURRENT_DATE);
				INSERT INTO tbl_user VALUES
				 ('00000000-0000-0000-0000-000000000001', 'venueowner'),
				 ('00000000-0000-0000-0000-000000000002', 'bugrasahin'),
				 ('00000000-0000-0000-0000-000000000003', 'formerfounder');
				INSERT INTO tbl_musician_profile(id, user_id, stage_name) VALUES ('10000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000002', 'Buğra Şahin');
				INSERT INTO tbl_band VALUES ('20000000-0000-0000-0000-000000000001', 'Şahbaz');
				INSERT INTO tbl_band_member VALUES
				 ('21000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000002', 'ACTIVE', 'FOUNDER'),
				 ('21000000-0000-0000-0000-000000000002', '20000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000003', 'LEFT', 'FOUNDER');
				INSERT INTO tbl_venues VALUES
				 ('50000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', 'Bağlantılı Mekan'),
				 ('50000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000001', 'SoundConnect Ankara');
				INSERT INTO musician_profile_venues VALUES ('10000000-0000-0000-0000-000000000001', '50000000-0000-0000-0000-000000000001');
				INSERT INTO venue_active_bands VALUES ('20000000-0000-0000-0000-000000000001', '50000000-0000-0000-0000-000000000001');
				INSERT INTO tbl_event(id, venue_id, title, musician_profile_id, band_id, manual_performer_name) VALUES
				 ('30000000-0000-0000-0000-000000000001', '50000000-0000-0000-0000-000000000001', 'Bağlantılı Müzisyen', '10000000-0000-0000-0000-000000000001', NULL, NULL),
				 ('30000000-0000-0000-0000-000000000002', '50000000-0000-0000-0000-000000000001', 'Bağlantılı Grup', NULL, '20000000-0000-0000-0000-000000000001', NULL),
				 ('30000000-0000-0000-0000-000000000003', '50000000-0000-0000-0000-000000000002', 'Açık Onaylı Etkinlik', '10000000-0000-0000-0000-000000000001', NULL, NULL),
				 ('30000000-0000-0000-0000-000000000004', '50000000-0000-0000-0000-000000000002', 'Bekleyen Grup', NULL, '20000000-0000-0000-0000-000000000001', NULL),
				 ('30000000-0000-0000-0000-000000000005', '50000000-0000-0000-0000-000000000002', 'Reddedilen Etkinlik', '10000000-0000-0000-0000-000000000001', NULL, NULL),
				 ('30000000-0000-0000-0000-000000000006', '50000000-0000-0000-0000-000000000002', 'Manuel Etkinlik', NULL, NULL, 'Konuk');
				""");
		execute(oldMigration());
		execute("""
				UPDATE tbl_event SET performer_approval_status = 'APPROVED', musician_profile_id = '10000000-0000-0000-0000-000000000001', manual_performer_name = NULL
				 WHERE id = '30000000-0000-0000-0000-000000000003';
				UPDATE event_performer_requests SET status = 'ACCEPTED', decided_by_user_id = '00000000-0000-0000-0000-000000000002', decided_at = CURRENT_TIMESTAMP, version = 1
				 WHERE event_id = '30000000-0000-0000-0000-000000000003';
				UPDATE tbl_event SET performer_approval_status = 'REJECTED' WHERE id = '30000000-0000-0000-0000-000000000005';
				UPDATE event_performer_requests SET status = 'REJECTED', decided_by_user_id = '00000000-0000-0000-0000-000000000002', decided_at = CURRENT_TIMESTAMP, version = 1
				 WHERE event_id = '30000000-0000-0000-0000-000000000005';
				""");
	}

	@Test
	void onlyExplicitAcceptancesBackfillAndConnectedLegacyEventsReceiveActionableRequests() throws Exception {
		execute(migration());
		assertThat(count("SELECT count(*) FROM tbl_event")).isEqualTo(6);
		assertThat(count("SELECT count(*) FROM tbl_event WHERE profile_calendar_approved")).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM tbl_event WHERE profile_calendar_approved AND id = '30000000-0000-0000-0000-000000000003'")).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM event_performer_requests WHERE request_purpose = 'PROFILE_VISIBILITY' AND status = 'PENDING'")).isEqualTo(2);
		assertThat(count("SELECT count(*) FROM event_performer_requests WHERE request_purpose = 'PERFORMER_CONSENT'")).isEqualTo(3);
		assertThat(count("SELECT count(*) FROM tbl_event WHERE performer_approval_status = 'APPROVED' AND NOT profile_calendar_approved")).isEqualTo(2);
		assertThat(count("SELECT count(*) FROM event_performer_requests WHERE status = 'REJECTED'")).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM tbl_event_performer_notification_outbox WHERE payload ->> 'requestPurpose' = 'PROFILE_VISIBILITY'")).isEqualTo(2);
		assertThat(count("""
				SELECT count(*) FROM tbl_event_performer_notification_outbox
				WHERE payload ->> 'requestPurpose' = 'PROFILE_VISIBILITY'
				 AND recipient_id = '00000000-0000-0000-0000-000000000002'
				 AND payload -> 'availableActions' = '["ACCEPT", "REJECT"]'::jsonb
				 AND notification_type = 'EVENT_PERFORMER_APPROVAL_REQUESTED'
				""")).isEqualTo(2);
		assertThat(count("""
				SELECT count(*) FROM tbl_event_performer_notification_outbox
				WHERE payload ->> 'requestPurpose' = 'PROFILE_VISIBILITY'
				 AND payload ->> 'performerType' = 'BAND' AND message LIKE '%“Şahbaz” adlı grubunuzu%'
				""")).isEqualTo(1);
	}

	@Test
	void fullStartupMigrationRerunPreservesVisibilityRejectionWithoutReaskingOrUnlinking() throws Exception {
		execute(migration());
		execute("""
				UPDATE event_performer_requests SET status = 'REJECTED', decided_by_user_id = '00000000-0000-0000-0000-000000000002', decided_at = CURRENT_TIMESTAMP, version = 1
				 WHERE event_id = '30000000-0000-0000-0000-000000000001';
				""");
		long notifications = count("SELECT count(*) FROM tbl_event_performer_notification_outbox");
		execute(oldMigration());
		execute(migration());
		execute(migration());
		assertThat(count("SELECT count(*) FROM tbl_event_performer_notification_outbox")).isEqualTo(notifications);
		assertThat(count("SELECT count(*) FROM event_performer_requests")).isEqualTo(5);
		assertThat(count("SELECT count(*) FROM event_performer_requests WHERE event_id = '30000000-0000-0000-0000-000000000001' AND status = 'REJECTED' AND request_purpose = 'PROFILE_VISIBILITY' AND version = 1")).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM tbl_event WHERE id = '30000000-0000-0000-0000-000000000001' AND NOT profile_calendar_approved AND musician_profile_id IS NOT NULL AND performer_approval_status = 'APPROVED'")).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM tbl_event WHERE profile_calendar_approved")).isEqualTo(1);
	}

	@Test
	void unknownPurposeAndCalendarPermissionForUnlinkedEventsAreRejectedByDatabase() throws Exception {
		execute(migration());
		assertThatThrownBy(() -> execute("UPDATE event_performer_requests SET request_purpose = 'AUTO_APPROVED'"))
				.isInstanceOf(SQLException.class).hasMessageContaining("ck_event_performer_request_purpose");
		assertThatThrownBy(() -> execute("UPDATE tbl_event SET profile_calendar_approved = true WHERE performer_approval_status = 'PENDING'"))
				.isInstanceOf(SQLException.class).hasMessageContaining("ck_event_profile_calendar_consent");
	}

	@Test
	void repeatedMigrationsPreserveAcceptedParticipationWithoutProfileOptInForMusicianAndBand() throws Exception {
		execute(migration());
		execute("""
				UPDATE tbl_event SET profile_calendar_approved = false
				 WHERE id = '30000000-0000-0000-0000-000000000003';
				UPDATE tbl_event SET performer_approval_status = 'APPROVED', band_id = '20000000-0000-0000-0000-000000000001', manual_performer_name = NULL
				 WHERE id = '30000000-0000-0000-0000-000000000004';
				UPDATE event_performer_requests SET status = 'ACCEPTED', decided_by_user_id = '00000000-0000-0000-0000-000000000002', decided_at = CURRENT_TIMESTAMP, version = 1
				 WHERE event_id = '30000000-0000-0000-0000-000000000004';
				""");
		long notifications = count("SELECT count(*) FROM tbl_event_performer_notification_outbox");
		for (int i = 0; i < 2; i++) {
			execute(oldMigration());
			execute(migration());
		}
		assertThat(count("SELECT count(*) FROM tbl_event_performer_notification_outbox")).isEqualTo(notifications);
		assertThat(count("""
				SELECT count(*) FROM tbl_event event JOIN event_performer_requests request ON request.event_id = event.id
				 WHERE request.status = 'ACCEPTED' AND request.request_purpose = 'PERFORMER_CONSENT'
				   AND NOT event.profile_calendar_approved AND event.performer_approval_status = 'APPROVED'
				   AND event.manual_performer_name IS NULL AND event.venue_id IS NOT NULL
				   AND request.version = 1
				""")).isEqualTo(2);
		assertThat(count("SELECT count(*) FROM tbl_event WHERE profile_calendar_approved")).isZero();
	}

	@Test
	void anExistingFalseColumnIsNeverInferredAsUninitializedEvenForAcceptedLegacyRows() throws Exception {
		execute("ALTER TABLE tbl_event ADD COLUMN profile_calendar_approved boolean NOT NULL DEFAULT false");
		execute(migration());
		execute(migration());
		assertThat(count("SELECT count(*) FROM tbl_event WHERE profile_calendar_approved")).isZero();
		assertThat(count("SELECT count(*) FROM event_performer_requests WHERE status = 'ACCEPTED'")).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM tbl_event WHERE id = '30000000-0000-0000-0000-000000000003' AND musician_profile_id IS NOT NULL AND performer_approval_status = 'APPROVED'")).isEqualTo(1);
	}

	@Test
	void existingTruePermissionAndAcceptedDecisionSurviveRerunsUnchanged() throws Exception {
		execute(migration());
		execute(oldMigration());
		execute(migration());
		assertThat(count("""
				SELECT count(*) FROM tbl_event event JOIN event_performer_requests request ON request.event_id = event.id
				 WHERE event.id = '30000000-0000-0000-0000-000000000003'
				   AND event.profile_calendar_approved AND event.performer_approval_status = 'APPROVED'
				   AND request.status = 'ACCEPTED' AND request.version = 1
				""")).isEqualTo(1);
	}

	@Test
	void pastAutoLinkedEventsStayHiddenWithoutHistoricalRequestsOrNotifications() throws Exception {
		execute("""
				INSERT INTO tbl_event(id, venue_id, title, musician_profile_id, performer_approval_status, event_date)
				VALUES ('30000000-0000-0000-0000-000000000007', '50000000-0000-0000-0000-000000000001',
				 'Dünkü Bağlantılı Etkinlik', '10000000-0000-0000-0000-000000000001', 'APPROVED', CURRENT_DATE - 1);
				UPDATE tbl_event SET event_date = CURRENT_DATE - 1 WHERE id = '30000000-0000-0000-0000-000000000003';
				""");
		execute(migration());
		assertThat(count("SELECT count(*) FROM tbl_event WHERE id = '30000000-0000-0000-0000-000000000007' AND NOT profile_calendar_approved AND performer_approval_status = 'APPROVED' AND musician_profile_id IS NOT NULL")).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM event_performer_requests WHERE event_id = '30000000-0000-0000-0000-000000000007'")).isZero();
		assertThat(count("SELECT count(*) FROM tbl_event_performer_notification_outbox WHERE payload ->> 'eventId' = '30000000-0000-0000-0000-000000000007'")).isZero();
		assertThat(count("SELECT count(*) FROM tbl_event WHERE id = '30000000-0000-0000-0000-000000000003' AND profile_calendar_approved")).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM event_performer_requests WHERE request_purpose = 'PROFILE_VISIBILITY'")).isEqualTo(2);
	}

	@Test
	void missingFounderRollsBackWithoutDeletingEventsOrPretendingConsent() throws Exception {
		execute("UPDATE tbl_band_member SET status = 'LEFT'");
		assertThatThrownBy(() -> execute(migration())).isInstanceOf(SQLException.class)
				.hasMessageContaining("authorized profile decider");
		assertThat(count("SELECT count(*) FROM tbl_event")).isEqualTo(6);
		assertThat(count("SELECT count(*) FROM event_performer_requests")).isEqualTo(3);
		assertThat(count("SELECT count(*) FROM information_schema.columns WHERE table_name = 'tbl_event' AND column_name = 'profile_calendar_approved'")).isZero();
	}

	private String oldMigration() throws Exception {
		return Files.readString(Path.of("scripts/db/2026-09-04-event-performer-consent.sql"));
	}

	private String migration() throws Exception {
		return Files.readString(Path.of("scripts/db/2026-09-05-event-profile-visibility-consent.sql"));
	}

	private Connection connection() throws SQLException {
		return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
	}

	private void execute(String sql) throws SQLException {
		try (Connection connection = connection(); var statement = connection.createStatement()) {
			statement.execute(sql);
		}
	}

	private long count(String sql) throws SQLException {
		try (Connection connection = connection(); var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
			result.next();
			return result.getLong(1);
		}
	}
}

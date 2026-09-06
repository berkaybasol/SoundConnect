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
import static org.assertj.core.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class ReciprocalMusicianEventsMigrationPostgresTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine")
            .withDatabaseName("reciprocal_migration").withUsername("soundconnect").withPassword("soundconnect");

    @BeforeEach void schema() throws Exception {
        execute("""
                DROP SCHEMA public CASCADE; CREATE SCHEMA public;
                CREATE TABLE tbl_user(id uuid PRIMARY KEY, user_name varchar(255));
                CREATE TABLE tbl_musician_profile(id uuid PRIMARY KEY, user_id uuid REFERENCES tbl_user(id), stage_name varchar(255), name varchar(255));
                CREATE TABLE tbl_band(id uuid PRIMARY KEY, name varchar(100));
                CREATE TABLE tbl_band_member(id uuid PRIMARY KEY, band_id uuid, user_id uuid, status varchar(30), band_role varchar(30));
                CREATE TABLE tbl_venues(id uuid PRIMARY KEY, owner_id uuid REFERENCES tbl_user(id), name varchar(255));
                CREATE TABLE musician_profile_venues(musician_profile_id uuid, venue_id uuid);
                CREATE TABLE venue_active_bands(band_id uuid, venue_id uuid);
                CREATE TABLE tbl_event(id uuid PRIMARY KEY, venue_id uuid NOT NULL REFERENCES tbl_venues(id), title varchar(255),
                    musician_profile_id uuid, band_id uuid, manual_performer_name varchar(120), event_date date NOT NULL DEFAULT CURRENT_DATE,
                    start_time time NOT NULL DEFAULT '20:00');
                CREATE TABLE tbl_notification(id uuid PRIMARY KEY, type varchar(64) CHECK(type IN ('AUTH_EMAIL_VERIFIED','EVENT_PERFORMER_APPROVAL_REQUESTED')));
                INSERT INTO tbl_user VALUES ('00000000-0000-0000-0000-000000000001','venue'), ('00000000-0000-0000-0000-000000000002','musician');
                INSERT INTO tbl_musician_profile VALUES ('10000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000002','Buğra',null);
                INSERT INTO tbl_band VALUES ('20000000-0000-0000-0000-000000000001','Şahbaz');
                INSERT INTO tbl_venues VALUES ('50000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000001','Ankara');
                INSERT INTO tbl_event(id,venue_id,title,manual_performer_name) VALUES ('30000000-0000-0000-0000-000000000001','50000000-0000-0000-0000-000000000001','Legacy','Konuk');
                """);
        earlierMigrations();
        execute("ALTER TABLE tbl_event_performer_notification_outbox ADD CONSTRAINT old_notification_types CHECK(notification_type IN ('AUTH_EMAIL_VERIFIED','EVENT_PERFORMER_APPROVAL_REQUESTED'))");
    }

    @Test void legacyBackfillsOwnershipWithoutLosingEvents() throws Exception {
        execute(current()); execute(current());
        assertThat(count("SELECT count(*) FROM tbl_event WHERE event_origin='VENUE' AND organizer_user_id='00000000-0000-0000-0000-000000000001' AND venue_calendar_approved AND venue_approval_status='APPROVED'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM event_venue_requests")).isZero();
    }

    @Test void startupRerunPreservesOwnConsentAndNeverSeedsReversePerformerRequests() throws Exception {
        execute(current());
        execute("""
                INSERT INTO tbl_event(id,title,event_origin,organizer_user_id,musician_profile_id,performer_approval_status,profile_calendar_approved,venue_calendar_approved,venue_approval_status)
                VALUES ('30000000-0000-0000-0000-000000000002','Independent','MUSICIAN','00000000-0000-0000-0000-000000000002','10000000-0000-0000-0000-000000000001','APPROVED',true,false,'NOT_REQUIRED');
                INSERT INTO tbl_event(id,title,event_origin,organizer_user_id,musician_profile_id,performer_approval_status,profile_calendar_approved,venue_calendar_approved,venue_approval_status,venue_id,venue_name_snapshot)
                VALUES ('30000000-0000-0000-0000-000000000003','Connected','MUSICIAN','00000000-0000-0000-0000-000000000002','10000000-0000-0000-0000-000000000001','APPROVED',true,false,'APPROVED','50000000-0000-0000-0000-000000000001','Ankara');
                INSERT INTO event_venue_requests(id,event_id,venue_id,venue_name_snapshot,status,request_purpose,requested_by_user_id,decided_by_user_id,decided_at)
                VALUES ('40000000-0000-0000-0000-000000000001','30000000-0000-0000-0000-000000000003','50000000-0000-0000-0000-000000000001','Ankara','REJECTED','PROFILE_VISIBILITY','00000000-0000-0000-0000-000000000002','00000000-0000-0000-0000-000000000001',CURRENT_TIMESTAMP);
                UPDATE tbl_musician_calendar_settings SET visible=true,version=3;
                UPDATE tbl_band_calendar_settings SET visible=false,version=2;
                """);
        earlierMigrations(); execute(current());
        assertThat(count("SELECT count(*) FROM tbl_event WHERE event_origin='MUSICIAN' AND profile_calendar_approved AND NOT venue_calendar_approved")).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM event_performer_requests")).isZero();
        assertThat(count("SELECT count(*) FROM event_venue_requests WHERE status='REJECTED'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM tbl_event_performer_notification_outbox")).isZero();
        assertThat(count("SELECT count(*) FROM tbl_musician_calendar_settings WHERE visible AND version=3")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM tbl_band_calendar_settings WHERE NOT visible AND version=2")).isEqualTo(1);
    }

    @Test void constraintsRejectPublishedPendingVenueAndUnknownOrigin() throws Exception {
        execute(current());
        assertThatThrownBy(() -> execute("UPDATE tbl_event SET event_origin='BAND'"))
                .isInstanceOf(SQLException.class).hasMessageContaining("ck_event_origin_contract");
        assertThatThrownBy(() -> execute("UPDATE tbl_event SET venue_id=null"))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> execute("""
                INSERT INTO tbl_event(id,title,event_origin,organizer_user_id,musician_profile_id,performer_approval_status,profile_calendar_approved,venue_calendar_approved,venue_approval_status,venue_name_snapshot)
                VALUES ('30000000-0000-0000-0000-000000000002','Bad','MUSICIAN','00000000-0000-0000-0000-000000000002','10000000-0000-0000-0000-000000000001','APPROVED',true,true,'PENDING','Ankara')
                """)).isInstanceOf(SQLException.class).hasMessageContaining("ck_event_venue_consent");
    }

    @Test void enumChecksAllowNewNotificationsAndStillRejectUnknownTypes() throws Exception {
        execute(current()); execute(current());
        execute("INSERT INTO tbl_notification VALUES ('70000000-0000-0000-0000-000000000001','EVENT_VENUE_APPROVAL_REQUESTED'),('70000000-0000-0000-0000-000000000002','EVENT_VENUE_APPROVED'),('70000000-0000-0000-0000-000000000003','EVENT_VENUE_REJECTED')");
        assertThat(count("SELECT count(*) FROM tbl_notification")).isEqualTo(3);
        assertThatThrownBy(() -> execute("INSERT INTO tbl_notification VALUES ('70000000-0000-0000-0000-000000000004','UNKNOWN')")).isInstanceOf(SQLException.class);
        execute("""
                INSERT INTO tbl_event_performer_notification_outbox(event_id,recipient_id,notification_type,title,message,payload,email_force,occurred_at,status,attempt_count,next_attempt_at,created_at,updated_at)
                SELECT md5(value)::uuid,'00000000-0000-0000-0000-000000000001',value,'Title','Message','{}',false,CURRENT_TIMESTAMP,'PENDING',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
                FROM unnest(ARRAY['EVENT_VENUE_APPROVAL_REQUESTED','EVENT_VENUE_APPROVED','EVENT_VENUE_REJECTED']) value
                """);
        assertThat(count("SELECT count(*) FROM tbl_event_performer_notification_outbox")).isEqualTo(3);
        assertThatThrownBy(() -> execute("UPDATE tbl_event_performer_notification_outbox SET notification_type='UNKNOWN'"))
                .isInstanceOf(SQLException.class).hasMessageContaining("old_notification_types");
    }

    @Test void missingLegacyOwnerAbortsAtomicallyWithoutDeletingAnything() throws Exception {
        execute("UPDATE tbl_venues SET owner_id=null");
        assertThatThrownBy(() -> execute(current())).isInstanceOf(SQLException.class).hasMessageContaining("ownership");
        assertThat(count("SELECT count(*) FROM tbl_event")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM information_schema.columns WHERE table_name='tbl_event' AND column_name='event_origin'")).isZero();
    }

    @Test void unknownPartialOrganizerAbortsWithoutGuessingAnotherUser() throws Exception {
        execute("ALTER TABLE tbl_event ADD COLUMN organizer_user_id uuid; UPDATE tbl_event SET organizer_user_id='00000000-0000-0000-0000-000000000099'");
        assertThatThrownBy(() -> execute(current())).isInstanceOf(SQLException.class).hasMessageContaining("ownership");
        assertThat(count("SELECT count(*) FROM tbl_event WHERE organizer_user_id='00000000-0000-0000-0000-000000000099'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM information_schema.columns WHERE table_name='tbl_event' AND column_name='event_origin'")).isZero();
    }

    @Test void textNotificationEnumCheckIsExtendedWithoutLosingLegacyValues() throws Exception {
        execute("""
                ALTER TABLE tbl_notification DROP CONSTRAINT tbl_notification_type_check;
                ALTER TABLE tbl_notification ALTER COLUMN type TYPE text;
                ALTER TABLE tbl_notification ADD CONSTRAINT text_enum CHECK(type IN ('AUTH_EMAIL_VERIFIED','EVENT_PERFORMER_APPROVAL_REQUESTED'));
                """);
        execute(current()); execute(current());
        execute("INSERT INTO tbl_notification VALUES ('70000000-0000-0000-0000-000000000001','EVENT_VENUE_APPROVED'),('70000000-0000-0000-0000-000000000002','AUTH_EMAIL_VERIFIED')");
        assertThat(count("SELECT count(*) FROM tbl_notification")).isEqualTo(2);
        assertThatThrownBy(() -> execute("UPDATE tbl_notification SET type='UNKNOWN'"))
                .isInstanceOf(SQLException.class).hasMessageContaining("text_enum");
    }

    @Test void existingRequestTableIsNormalizedAndNoHibernateForeignKeysAccumulate() throws Exception {
        execute(current());
        execute("""
                ALTER TABLE event_venue_requests DROP CONSTRAINT fk_event_venue_request_event;
                ALTER TABLE event_venue_requests DROP CONSTRAINT fk_event_venue_request_venue;
                ALTER TABLE event_venue_requests ADD CONSTRAINT hibernate_random_event FOREIGN KEY(event_id) REFERENCES tbl_event(id);
                ALTER TABLE event_venue_requests ADD CONSTRAINT hibernate_random_venue FOREIGN KEY(venue_id) REFERENCES tbl_venues(id);
                ALTER TABLE event_venue_requests ALTER COLUMN status DROP NOT NULL, ALTER COLUMN request_purpose DROP NOT NULL,
                    ALTER COLUMN version DROP NOT NULL, ALTER COLUMN venue_name_snapshot DROP NOT NULL;
                """);
        execute(current()); execute(current());
        assertThat(count("SELECT count(*) FROM pg_constraint WHERE conrelid='event_venue_requests'::regclass AND contype='f'")).isEqualTo(4);
        assertThat(count("SELECT count(*) FROM pg_constraint WHERE conrelid='event_venue_requests'::regclass AND conname LIKE 'hibernate_%'")).isZero();
        insertPendingRequest();
        for (String column : new String[]{"event_id", "venue_id", "venue_name_snapshot", "status", "request_purpose", "requested_by_user_id", "version"}) {
            assertThatThrownBy(() -> execute("UPDATE event_venue_requests SET " + column + "=null"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("null value");
        }
        assertThatThrownBy(() -> execute("UPDATE event_venue_requests SET requested_by_user_id='00000000-0000-0000-0000-000000000099'"))
                .isInstanceOf(SQLException.class).hasMessageContaining("fk_event_venue_request_requested_by");
        assertThatThrownBy(() -> execute("UPDATE event_venue_requests SET status='ACCEPTED',decided_at=CURRENT_TIMESTAMP,decided_by_user_id='00000000-0000-0000-0000-000000000099'"))
                .isInstanceOf(SQLException.class).hasMessageContaining("fk_event_venue_request_decided_by");
        execute("DELETE FROM tbl_event WHERE id='30000000-0000-0000-0000-000000000002'");
        assertThat(count("SELECT count(*) FROM event_venue_requests")).isZero();
    }

    @Test void invalidExistingRequestAbortsWithoutSilentlyFillingConsent() throws Exception {
        execute(current()); insertPendingRequest();
        execute("ALTER TABLE event_venue_requests ALTER COLUMN status DROP NOT NULL; UPDATE event_venue_requests SET status=null");
        assertThatThrownBy(() -> execute(current())).isInstanceOf(SQLException.class).hasMessageContaining("contains null values");
        assertThat(count("SELECT count(*) FROM event_venue_requests WHERE status IS NULL")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM information_schema.columns WHERE table_name='event_venue_requests' AND column_name='status' AND is_nullable='YES'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM tbl_event")).isEqualTo(2);
    }

    @Test void notificationExtensionDoesNotRelaxCompoundChecksOrValidateLegacyUnvalidatedChecks() throws Exception {
        execute("""
                ALTER TABLE tbl_notification ADD CONSTRAINT compound_type_policy CHECK
                    (type IN ('AUTH_EMAIL_VERIFIED','EVENT_PERFORMER_APPROVAL_REQUESTED') AND char_length(type) < 64) NOT VALID;
                ALTER TABLE tbl_notification ADD CONSTRAINT unvalidated_enum CHECK
                    (type IN ('AUTH_EMAIL_VERIFIED','EVENT_PERFORMER_APPROVAL_REQUESTED')) NOT VALID;
                """);
        execute(current()); execute(current());
        assertThat(count("SELECT count(*) FROM pg_constraint WHERE conrelid='tbl_notification'::regclass AND conname='compound_type_policy' AND NOT convalidated AND pg_get_expr(conbin,conrelid) NOT LIKE '%EVENT_VENUE_%'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM pg_constraint WHERE conrelid='tbl_notification'::regclass AND conname='unvalidated_enum' AND NOT convalidated AND pg_get_expr(conbin,conrelid) LIKE '%EVENT_VENUE_REJECTED%'")).isEqualTo(1);
        assertThatThrownBy(() -> execute("INSERT INTO tbl_notification VALUES ('70000000-0000-0000-0000-000000000001','EVENT_VENUE_APPROVED')"))
                .isInstanceOf(SQLException.class).hasMessageContaining("compound_type_policy");
    }

    @Test void creationRetryKeysAreUniqueOwnedAndRetainedWhenEventIsDeleted() throws Exception {
        execute(current());
        execute("""
                INSERT INTO musician_event_creations(id,organizer_user_id,client_request_id,payload_hash,event_id)
                VALUES ('80000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000002','90000000-0000-0000-0000-000000000001',repeat('a',64),'30000000-0000-0000-0000-000000000001');
                """);
        execute(current());
        assertThatThrownBy(() -> execute("UPDATE musician_event_creations SET payload_hash='bad'"))
                .isInstanceOf(SQLException.class).hasMessageContaining("ck_musician_event_creation_payload_hash");
        assertThatThrownBy(() -> execute("UPDATE musician_event_creations SET organizer_user_id='00000000-0000-0000-0000-000000000099'"))
                .isInstanceOf(SQLException.class).hasMessageContaining("fk_musician_event_creation_organizer");
        assertThatThrownBy(() -> execute("""
                INSERT INTO musician_event_creations(id,organizer_user_id,client_request_id,payload_hash,event_id)
                SELECT '80000000-0000-0000-0000-000000000002',organizer_user_id,client_request_id,payload_hash,'30000000-0000-0000-0000-000000000099' FROM musician_event_creations
                """)).isInstanceOf(SQLException.class).hasMessageContaining("uk_musician_event_creation_client");
        assertThatThrownBy(() -> execute("""
                INSERT INTO musician_event_creations(id,organizer_user_id,client_request_id,payload_hash,event_id)
                SELECT '80000000-0000-0000-0000-000000000002',organizer_user_id,'90000000-0000-0000-0000-000000000002',payload_hash,event_id FROM musician_event_creations
                """)).isInstanceOf(SQLException.class).hasMessageContaining("uk_musician_event_creation_event");
        execute("DELETE FROM tbl_event WHERE id='30000000-0000-0000-0000-000000000001'");
        execute(current());
        assertThat(count("SELECT count(*) FROM musician_event_creations")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM pg_constraint WHERE conrelid='musician_event_creations'::regclass AND contype='f' AND confrelid='tbl_event'::regclass")).isZero();
    }

    private void insertPendingRequest() throws SQLException {
        execute("""
                INSERT INTO tbl_event(id,title,event_origin,organizer_user_id,musician_profile_id,performer_approval_status,profile_calendar_approved,venue_calendar_approved,venue_approval_status,venue_name_snapshot)
                VALUES ('30000000-0000-0000-0000-000000000002','Pending','MUSICIAN','00000000-0000-0000-0000-000000000002','10000000-0000-0000-0000-000000000001','APPROVED',true,false,'PENDING','Ankara');
                INSERT INTO event_venue_requests(id,event_id,venue_id,venue_name_snapshot,status,request_purpose,requested_by_user_id)
                VALUES ('40000000-0000-0000-0000-000000000001','30000000-0000-0000-0000-000000000002','50000000-0000-0000-0000-000000000001','Ankara','PENDING','VENUE_CONSENT','00000000-0000-0000-0000-000000000002');
                """);
    }

    private void earlierMigrations() throws Exception {
        execute(migration("2026-09-04-event-performer-consent.sql"));
        execute(migration("2026-09-05-musician-calendar.sql"));
        execute(migration("2026-09-05-performer-calendar-opt-in.sql"));
        execute(migration("2026-09-05-event-profile-visibility-consent.sql"));
    }

    private String current() throws Exception { return migration("2026-09-05-reciprocal-musician-events.sql"); }
    private String migration(String name) throws Exception { return Files.readString(Path.of("scripts/db", name)); }
    private Connection connection() throws SQLException { return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()); }
    private void execute(String sql) throws SQLException { try (var connection = connection(); var statement = connection.createStatement()) { statement.execute(sql); } }
    private long count(String sql) throws SQLException { try (var connection = connection(); var statement = connection.createStatement(); var rs = statement.executeQuery(sql)) { rs.next(); return rs.getLong(1); } }
}

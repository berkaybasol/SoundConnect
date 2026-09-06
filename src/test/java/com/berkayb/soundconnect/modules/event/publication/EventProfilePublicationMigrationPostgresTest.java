package com.berkayb.soundconnect.modules.event.publication;

import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.nio.file.*;
import java.sql.*;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class EventProfilePublicationMigrationPostgresTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine")
            .withDatabaseName("event_publication_migration").withUsername("soundconnect").withPassword("soundconnect");
    final UUID profile = UUID.randomUUID(), hiddenProfile = UUID.randomUUID(), band = UUID.randomUUID(), hiddenBand = UUID.randomUUID();
    final UUID user = UUID.randomUUID(), hiddenUser = UUID.randomUUID(), venue = UUID.randomUUID();
    final UUID direct = UUID.randomUUID(), hiddenDirect = UUID.randomUUID(), group = UUID.randomUUID(), hiddenGroup = UUID.randomUUID();

    @BeforeEach void schema() throws Exception {
        execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public;");
        execute("""
            CREATE TABLE tbl_musician_profile(id uuid PRIMARY KEY, user_id uuid);
            CREATE TABLE tbl_band(id uuid PRIMARY KEY);
            CREATE TABLE tbl_venues(id uuid PRIMARY KEY, status varchar(30));
            CREATE TABLE tbl_event(id uuid PRIMARY KEY, musician_profile_id uuid, band_id uuid,
                venue_id uuid, event_origin varchar(20), performer_approval_status varchar(20), profile_calendar_approved boolean);
            CREATE TABLE event_performer_requests(id uuid PRIMARY KEY, event_id uuid, status varchar(20));
            CREATE TABLE tbl_musician_calendar_settings(musician_profile_id uuid PRIMARY KEY, visible boolean);
            CREATE TABLE tbl_band_calendar_settings(band_id uuid PRIMARY KEY, visible boolean);
            CREATE TABLE tbl_band_member(id uuid PRIMARY KEY, band_id uuid, user_id uuid, status varchar(30));
            INSERT INTO tbl_musician_profile VALUES ('%s','%s'),('%s','%s');
            INSERT INTO tbl_musician_calendar_settings VALUES ('%s',true),('%s',false);
            INSERT INTO tbl_band VALUES ('%s'),('%s');
            INSERT INTO tbl_band_calendar_settings VALUES ('%s',true),('%s',false);
            INSERT INTO tbl_venues VALUES ('%s','APPROVED');
            INSERT INTO tbl_band_member VALUES ('%s','%s','%s','ACTIVE'),('%s','%s','%s','ACTIVE'),('%s','%s','%s','ACTIVE');
            """.formatted(profile,user,hiddenProfile,hiddenUser,profile,hiddenProfile,band,hiddenBand,band,hiddenBand,venue,
                UUID.randomUUID(),band,user,UUID.randomUUID(),band,hiddenUser,UUID.randomUUID(),hiddenBand,user));
        event(direct, profile, null); event(hiddenDirect, hiddenProfile, null); event(group, null, band); event(hiddenGroup, null, hiddenBand);
    }

    @Test void migrationPreservesEffectivePrivacyAndOriginalAcceptanceThenRerunPreservesNewChoices() throws Exception {
        execute(migration());
        assertThat(number("select count(*) from tbl_event where profile_calendar_approved")).isEqualTo(2);
        assertThat(number("select count(*) from event_performer_requests where accepted_profile_publication")).isEqualTo(4);
        assertThat(number("select count(*) from event_member_publications where visible")).isEqualTo(1);
        assertThat(number("select count(*) from event_member_publications where event_id='" + group + "' and musician_profile_id='" + profile + "'")).isEqualTo(1);
        execute("update tbl_event set profile_calendar_approved = true, profile_publication_version = 8 where id='" + hiddenDirect + "'");
        execute("update event_member_publications set visible = false, version = 7");
        execute(migration());
        assertThat(number("select profile_publication_version from tbl_event where id='" + hiddenDirect + "' and profile_calendar_approved")).isEqualTo(8);
        assertThat(number("select version from event_member_publications where not visible")).isEqualTo(7);
        assertThat(number("select count(*) from soundconnect_schema_migrations")).isEqualTo(1);
    }

    @Test void nonpublicVenueAndInactiveMembersAreNeverSeeded() throws Exception {
        execute("update tbl_venues set status='PENDING'");
        execute(migration());
        assertThat(number("select count(*) from event_member_publications")).isZero();
    }

    @Test void defaultsRejectInvalidVersionsAndEventDeletionCascades() throws Exception {
        execute(migration());
        assertThatThrownBy(() -> execute("update event_member_publications set version=-1")).isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> execute("update event_member_publications set visible=null")).isInstanceOf(SQLException.class);
        execute("delete from tbl_event where id='" + group + "'");
        assertThat(number("select count(*) from event_member_publications")).isZero();
    }

    private void event(UUID id, UUID musician, UUID groupId) throws Exception {
        execute("INSERT INTO tbl_event VALUES ('"+id+"',"+sql(musician)+","+sql(groupId)+",'"+venue+"','VENUE','APPROVED',true)");
        execute("INSERT INTO event_performer_requests VALUES ('"+UUID.randomUUID()+"','"+id+"','ACCEPTED')");
    }
    private String sql(UUID id) { return id == null ? "null" : "'"+id+"'"; }
    private String migration() throws Exception { return Files.readString(Path.of("scripts/db/2026-09-06-event-profile-publications.sql")); }
    private Connection connection() throws SQLException { return DriverManager.getConnection(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()); }
    private void execute(String sql) throws SQLException { try(var c=connection();var s=c.createStatement()){s.execute(sql);} }
    private long number(String sql) throws SQLException { try(var c=connection();var s=c.createStatement();var r=s.executeQuery(sql)){r.next();return r.getLong(1);} }
}

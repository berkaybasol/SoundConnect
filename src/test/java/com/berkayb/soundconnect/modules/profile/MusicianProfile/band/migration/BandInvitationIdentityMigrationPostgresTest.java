package com.berkayb.soundconnect.modules.profile.MusicianProfile.band.migration;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class BandInvitationIdentityMigrationPostgresTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine")
            .withDatabaseName("band_invitation_identity_test");

    @Test
    void backfillAssignsDistinctPendingIdentitiesOnceWithoutChangingMembershipConsentOrNotificationHistory() throws Exception {
        execute("""
            CREATE TABLE tbl_band_member(id integer PRIMARY KEY, status varchar(30), member_title varchar(256), title_version bigint);
            INSERT INTO tbl_band_member VALUES (1,'PENDING',NULL,4), (2,'PENDING',NULL,7),
                (3,'ACTIVE','Davul',9), (4,'LEFT',NULL,12);
            CREATE TABLE tbl_notification(id integer PRIMARY KEY, payload jsonb);
            INSERT INTO tbl_notification VALUES (1,'{"module":"BAND","action":"INVITE_RECEIVED"}');
            """);
        migrate();
        String first = value("select invitation_id from tbl_band_member where id=1");
        String second = value("select invitation_id from tbl_band_member where id=2");
        assertThat(UUID.fromString(first)).isNotEqualTo(UUID.fromString(second));
        assertThat(value("select count(*) from tbl_band_member where status<>'PENDING' and invitation_id is null")).isEqualTo("2");
        assertThat(value("select string_agg(id||':'||status||':'||title_version,',' order by id) from tbl_band_member"))
            .isEqualTo("1:PENDING:4,2:PENDING:7,3:ACTIVE:9,4:LEFT:12");
        assertThat(value("select member_title from tbl_band_member where id=3")).isEqualTo("Davul");
        assertThat(value("select count(*) from tbl_notification where not(payload ? 'invitationId')")).isEqualTo("1");
        migrate();
        assertThat(value("select invitation_id from tbl_band_member where id=1")).isEqualTo(first);
        assertThat(value("select invitation_id from tbl_band_member where id=2")).isEqualTo(second);
        assertThat(value("select count(*) from soundconnect_schema_migrations where migration_id='2026-09-07-band-invitation-identity'"))
            .isEqualTo("1");
    }

    private static void migrate() throws Exception {
        execute(Files.readString(Path.of(System.getProperty("user.dir"),"scripts/db/2026-09-07-band-invitation-identity.sql")));
    }
    private static Connection connection() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());
    }
    private static void execute(String sql) throws Exception {
        try (Connection connection=connection(); Statement statement=connection.createStatement()) { statement.execute(sql); }
    }
    private static String value(String sql) throws Exception {
        try (Connection connection=connection(); Statement statement=connection.createStatement(); var result=statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue(); return result.getString(1);
        }
    }
}

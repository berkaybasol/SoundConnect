package com.berkayb.soundconnect.modules.profile.MusicianProfile.band.migration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class BandMemberTitleMigrationPostgresTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine")
            .withDatabaseName("band_title_migration_test");

    @BeforeEach
    void legacySchema() throws Exception {
        execute("DROP TABLE IF EXISTS tbl_band_member; DROP TABLE IF EXISTS soundconnect_schema_migrations;");
        execute("""
                CREATE TABLE tbl_band_member (
                    id integer PRIMARY KEY, band_id integer NOT NULL, user_id integer NOT NULL,
                    band_role varchar(30) NOT NULL, status varchar(30) NOT NULL);
                INSERT INTO tbl_band_member VALUES (1, 10, 100, 'FOUNDER', 'ACTIVE'),
                    (2, 10, 200, 'MEMBER', 'ACTIVE'), (3, 20, 200, 'MEMBER', 'LEFT');
                """);
    }

    @Test
    void additiveMigrationIsRerunnableAndPreservesExistingDataAndTitles() throws Exception {
        migrate();
        assertThat(value("SELECT count(*) FROM tbl_band_member WHERE member_title IS NULL AND title_version = 0"))
                .isEqualTo("3");
        execute("UPDATE tbl_band_member SET member_title = 'Davul', title_version = 7 WHERE id = 2");
        migrate();
        assertThat(value("SELECT member_title || ':' || title_version FROM tbl_band_member WHERE id = 2"))
                .isEqualTo("Davul:7");
        assertThat(value("SELECT string_agg(id || ':' || band_id || ':' || user_id || ':' || band_role || ':' || status, ',' ORDER BY id) FROM tbl_band_member"))
                .isEqualTo("1:10:100:FOUNDER:ACTIVE,2:10:200:MEMBER:ACTIVE,3:20:200:MEMBER:LEFT");
        assertThat(value("SELECT count(*) FROM soundconnect_schema_migrations WHERE migration_id = '2026-09-06-band-member-titles'"))
                .isEqualTo("1");
    }

    @Test
    void hibernateCreatedNullableVersionsAreBackfilledWithoutResettingValidChoices() throws Exception {
        execute("ALTER TABLE tbl_band_member ADD member_title varchar(256), ADD title_version bigint");
        execute("UPDATE tbl_band_member SET member_title = 'Vokal / Gitar', title_version = 5 WHERE id = 1");
        migrate();
        assertThat(value("SELECT member_title || ':' || title_version FROM tbl_band_member WHERE id = 1"))
                .isEqualTo("Vokal / Gitar:5");
        assertThat(value("SELECT count(*) FROM tbl_band_member WHERE title_version = 0")).isEqualTo("2");
        execute("INSERT INTO tbl_band_member(id,band_id,user_id,band_role,status) VALUES(4,10,300,'MEMBER','ACTIVE')");
        assertThat(value("SELECT title_version FROM tbl_band_member WHERE id = 4")).isEqualTo("0");
        assertThatThrownBy(() -> execute("UPDATE tbl_band_member SET title_version = NULL WHERE id = 4"))
                .isInstanceOf(java.sql.SQLException.class);
    }

    @Test
    void databaseBoundsPreventInvalidStorageWithoutAffectingOtherMemberships() throws Exception {
        migrate();
        assertThatThrownBy(() -> execute("UPDATE tbl_band_member SET title_version = -1 WHERE id = 2"))
                .isInstanceOf(java.sql.SQLException.class);
        assertThatThrownBy(() -> execute("UPDATE tbl_band_member SET member_title = repeat('x',257) WHERE id = 2"))
                .isInstanceOf(java.sql.SQLException.class);
        assertThat(value("SELECT count(*) FROM tbl_band_member WHERE member_title IS NULL AND title_version = 0"))
                .isEqualTo("3");
    }

    @Test
    void malformedExistingVersionRollsBackMigrationInsteadOfErasingIt() throws Exception {
        execute("ALTER TABLE tbl_band_member ADD title_version bigint");
        execute("UPDATE tbl_band_member SET title_version = -1 WHERE id = 1");
        assertThatThrownBy(BandMemberTitleMigrationPostgresTest::migrate).isInstanceOf(java.sql.SQLException.class);
        assertThat(value("SELECT title_version FROM tbl_band_member WHERE id = 1")).isEqualTo("-1");
        assertThat(value("SELECT count(*) FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'tbl_band_member' AND column_name = 'member_title'"))
                .isEqualTo("0");
    }

    private static Connection connection() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static void execute(String sql) throws Exception {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static void migrate() throws Exception {
        execute(Files.readString(Path.of(System.getProperty("user.dir"), "scripts", "db", "2026-09-06-band-member-titles.sql")));
    }

    private static String value(String sql) throws Exception {
        try (Connection connection = connection(); Statement statement = connection.createStatement();
             var result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }
}

package com.berkayb.soundconnect.modules.notification.service;

import com.berkayb.soundconnect.modules.notification.config.BandNotificationIdentitySchema;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.nio.file.*;
import java.sql.SQLException;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/** Destructive schema cases run only in this fresh, labelled, non-reusable database. */
@Testcontainers
class BandNotificationIdentityMigrationPostgresTest {
    static final String MARKER = "2026-09-29-band-notification-identity";
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16.4-alpine")
            .withDatabaseName("bil007_band_migration").withUsername("fixture").withPassword("disposable")
            .withLabel("soundconnect.task", "bil007-band-migration").withReuse(false);
    DriverManagerDataSource source;
    JdbcTemplate jdbc;
    String migration;

    @BeforeEach void setup() throws Exception {
        source = new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        jdbc = new JdbcTemplate(source);
        try (var c = source.getConnection()) {
            assertThat(c.getMetaData().getURL()).isEqualTo(PG.getJdbcUrl());
            assertThat(c.getCatalog()).isEqualTo("bil007_band_migration");
        }
        jdbc.execute("drop schema public cascade; create schema public");
        jdbc.execute("""
                create table tbl_notification(id uuid primary key, recipient_id uuid not null,
                  type varchar(64), title varchar(160), message varchar(1000), payload jsonb,
                  is_read boolean, source_event_id uuid unique, created_at timestamptz,
                  occurred_at timestamptz, updated_at timestamptz);
                create table tbl_notification_receipt(source_event_id uuid primary key,
                  recipient_id uuid, recorded_at timestamptz);
                """);
        migration = Files.readString(Path.of("scripts/db", MARKER + ".sql"));
    }

    void migrate() throws Exception {
        try (var c = source.getConnection(); var s = c.createStatement()) { s.execute(migration); }
    }
    void guard() { new BandNotificationIdentitySchema(jdbc).run(null); }
    void rejects() {
        assertThatThrownBy(this::guard).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Apply " + MARKER + ".sql");
    }
    UUID row(String type, String payload, boolean read) {
        UUID id = UUID.randomUUID(), event = UUID.randomUUID(), recipient = UUID.randomUUID();
        jdbc.update("insert into tbl_notification values(?,?,?,'private old band','private old actor',?::jsonb,?,?,now(),now(),now())",
                id, recipient, type, payload, read, event);
        jdbc.update("insert into tbl_notification_receipt values(?,?,now())", event, recipient);
        return id;
    }
    String row(UUID id) { return jdbc.queryForObject("select to_jsonb(n)::text from tbl_notification n where id=?", String.class, id); }
    String preserved(UUID id) { return jdbc.queryForObject("select (to_jsonb(n)-'title'-'message'-'payload')::text from tbl_notification n where id=?", String.class, id); }
    List<String> receipts() { return jdbc.queryForList("select to_jsonb(r)::text from tbl_notification_receipt r order by source_event_id", String.class); }

    @Test void registryIncludesIdentityOnceAfterItsDependencies() throws Exception {
        String script = Files.readString(Path.of("scripts/dev.ps1"));
        String path = "Path = Join-Path $ProjectRoot \"scripts\\db\\" + MARKER + ".sql\"";
        assertThat(script.split(java.util.regex.Pattern.quote(path), -1)).hasSize(2);
        int at = script.indexOf(path);
        for (String prerequisite : List.of("2026-09-07-band-invitation-identity", "2026-09-07-notification-replay-receipts",
                "2026-09-10-listener-account-erasure", "2026-09-28-media-notification-identity")) {
            assertThat(at).isGreaterThan(script.indexOf("scripts\\db\\" + prerequisite + ".sql"));
        }
        assertThat(script.substring(at, script.indexOf('}', at))).contains("Marker = \"" + MARKER + "\"");
    }

    @Test void missingMarkerTableOrNotificationTableRejectsStartup() throws Exception {
        assertThatThrownBy(this::guard).isInstanceOf(org.springframework.dao.DataAccessException.class)
                .hasMessageContaining("soundconnect_schema_migrations");
        migrate(); guard();
        jdbc.execute("drop table tbl_notification cascade");
        assertThatThrownBy(this::guard).isInstanceOf(org.springframework.dao.DataAccessException.class)
                .hasMessageContaining("tbl_notification");
    }

    @Test void missingMarkerRejectsAndHealthyMigrationAccepts() throws Exception {
        migrate(); guard();
        jdbc.update("delete from soundconnect_schema_migrations where migration_id=?", MARKER);
        rejects(); migrate(); guard();
    }

    @Test void missingDisabledOrWrongFunctionTriggerRejects() throws Exception {
        migrate(); guard();
        jdbc.execute("alter table tbl_notification disable trigger trg_band_notification_identity");
        rejects();
        jdbc.execute("alter table tbl_notification enable always trigger trg_band_notification_identity");
        rejects();
        jdbc.execute("drop trigger trg_band_notification_identity on tbl_notification");
        rejects();
        jdbc.execute("""
                create function unrelated_trigger() returns trigger language plpgsql as $$ begin return new; end $$;
                create trigger trg_band_notification_identity before insert or update on tbl_notification
                for each row execute function unrelated_trigger();
                """);
        rejects(); migrate(); guard();
    }

    @Test void missingUnvalidatedOrWrongCheckRejects() throws Exception {
        migrate(); guard();
        String definition = jdbc.queryForObject("select pg_get_constraintdef(oid) from pg_constraint where conrelid='tbl_notification'::regclass and conname='ck_band_notification_identity'", String.class);
        jdbc.execute("alter table tbl_notification drop constraint ck_band_notification_identity");
        rejects();
        jdbc.execute("alter table tbl_notification add constraint ck_band_notification_identity " + definition + " not valid");
        rejects();
        jdbc.execute("alter table tbl_notification validate constraint ck_band_notification_identity");
        guard();
        jdbc.execute("alter table tbl_notification drop constraint ck_band_notification_identity; alter table tbl_notification add constraint ck_band_notification_identity check (true)");
        rejects(); migrate(); guard();
    }

    @Test void mixedRowsKeepRecipientsSourcesTargetsReadsAndReceiptsOnReplay() throws Exception {
        UUID band = UUID.randomUUID(), invite = UUID.randomUUID(), actor = UUID.randomUUID();
        String targets = "\"bandId\":\"" + band + "\",\"invitationId\":\"" + invite + "\"";
        UUID legacy = row("BAND_INVITE_RECEIVED", "{" + targets + ",\"bandName\":\"private band\",\"inviterUsername\":\"private actor\"}", true);
        UUID current = row("BAND_INVITE_ACCEPTED", "{" + targets + ",\"module\":\"BAND\",\"action\":\"INVITE_ACCEPTED\",\"bandIdentityVersion\":1,\"memberId\":\"" + actor.toString().toUpperCase(Locale.ROOT) + "\",\"privateExtra\":\"secret\"}", false);
        UUID other = row("DM_NEW_MESSAGE", "{\"body\":\"unrelated unchanged\"}", false);
        var ids = List.of(legacy, current, other);
        var before = ids.stream().map(this::preserved).toList(); var receiptBefore = receipts(); String unrelated = row(other);
        migrate(); guard();
        assertThat(ids.stream().map(this::preserved).toList()).isEqualTo(before);
        assertThat(receipts()).isEqualTo(receiptBefore); assertThat(row(other)).isEqualTo(unrelated);
        assertThat(row(legacy)).contains(band.toString(), invite.toString(), "\"bandIdentityVersion\": 0")
                .doesNotContain("private", "inviterId", "inviterUsername", "bandName");
        assertThat(row(current)).contains(band.toString(), invite.toString(), actor.toString(), "\"bandIdentityVersion\": 1")
                .doesNotContain("private", "secret");
        String marker = jdbc.queryForObject("select to_jsonb(m)::text from soundconnect_schema_migrations m where migration_id=?", String.class, MARKER);
        var after = ids.stream().map(this::row).toList();
        migrate(); guard();
        assertThat(ids.stream().map(this::row).toList()).isEqualTo(after);
        assertThat(receipts()).isEqualTo(receiptBefore);
        assertThat(jdbc.queryForObject("select to_jsonb(m)::text from soundconnect_schema_migrations m where migration_id=?", String.class, MARKER)).isEqualTo(marker);
    }

    @Test void transactionFailureRestoresDataSchemaFunctionsMarkerAndTriggerBeforeRetry() throws Exception {
        UUID a = row("BAND_INVITE_RECEIVED", "{\"bandName\":\"private\"}", false);
        UUID b = row("BAND_MEMBER_LEFT", "{}", true);
        var before = List.of(row(a), row(b)); var receiptBefore = receipts();
        String boundary = "INSERT INTO soundconnect_schema_migrations(migration_id)";
        assertThat(migration).containsOnlyOnce(boundary);
        String broken = migration.replace(boundary, "SELECT 1/0;\n" + boundary);
        try (var c = source.getConnection(); var s = c.createStatement()) {
            assertThatThrownBy(() -> s.execute(broken)).isInstanceOf(SQLException.class).hasMessageContaining("division by zero");
            s.execute("ROLLBACK");
        }
        assertThat(List.of(row(a), row(b))).isEqualTo(before); assertThat(receipts()).isEqualTo(receiptBefore);
        assertThat(jdbc.queryForObject("select to_regclass('soundconnect_schema_migrations') is null", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("select to_regprocedure('soundconnect_sanitize_band_notification()') is null", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("select to_regprocedure('soundconnect_band_notification_payload(text,jsonb)') is null", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from pg_trigger where tgrelid='tbl_notification'::regclass and tgname='trg_band_notification_identity'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from pg_constraint where conrelid='tbl_notification'::regclass and conname='ck_band_notification_identity'", Integer.class)).isZero();
        migrate(); guard();
    }
}

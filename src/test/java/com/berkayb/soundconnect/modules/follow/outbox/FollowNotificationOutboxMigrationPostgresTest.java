package com.berkayb.soundconnect.modules.follow.outbox;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.nio.file.*;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class FollowNotificationOutboxMigrationPostgresTest {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16.4-alpine");
    JdbcTemplate jdbc; String sql;
    UUID actor=UUID.randomUUID(), recipient=UUID.randomUUID(), event=UUID.randomUUID();
    @BeforeEach void setup() throws Exception {
        var ds=new DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword());
        String schema="follow_"+UUID.randomUUID().toString().replace("-","");
        new JdbcTemplate(ds).execute("create schema "+schema);
        jdbc=new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl()+(PG.getJdbcUrl().contains("?")?"&":"?")+"currentSchema="+schema,PG.getUsername(),PG.getPassword()));
        jdbc.execute("create table tbl_user(id uuid primary key,erased_at timestamptz)");
        jdbc.update("insert into tbl_user values (?,null),(?,null)",actor,recipient);
        sql=Files.readString(Path.of("scripts/db/2026-09-27-follow-notification-outbox.sql"));
    }
    void insert() {
        jdbc.update("insert into tbl_follow_notification_outbox(event_id,occurrence_id,follower_id,recipient_id,notification_type,occurred_at,status,attempt_count,next_attempt_at,created_at,updated_at) values (?,?,?,?,'SOCIAL_NEW_FOLLOWER',now(),'PENDING',0,now(),now(),now())",
            event,UUID.randomUUID(),actor,recipient);
    }
    @Test void freshAndRepeatPreserveDataAndInstallChecksIndexesAndErasureFence() {
        jdbc.execute(sql); insert();
        String before=jdbc.queryForObject("select to_jsonb(o)::text from tbl_follow_notification_outbox o",String.class);
        jdbc.execute(sql);jdbc.execute(sql);
        assertThat(jdbc.queryForObject("select to_jsonb(o)::text from tbl_follow_notification_outbox o",String.class)).isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from pg_constraint where conrelid='tbl_follow_notification_outbox'::regclass and contype='c' and convalidated",Integer.class)).isEqualTo(7);
        assertThat(jdbc.queryForObject("select count(*) from pg_indexes where schemaname=current_schema() and tablename='tbl_follow_notification_outbox'",Integer.class)).isEqualTo(7);
        assertThat(jdbc.queryForObject("select count(*) from pg_constraint where conrelid='tbl_follow_notification_outbox'::regclass and contype='f'",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select to_regclass('soundconnect_schema_migrations') is null",Boolean.class)).isTrue();
        jdbc.update("update tbl_user set erased_at=now() where id=?",actor);
        event=UUID.randomUUID(); assertThatThrownBy(this::insert).isInstanceOf(RuntimeException.class);
    }
    @Test void invalidStatesTypesLeasesBoundsAndSelfRecipientAreRejected() {
        jdbc.execute(sql);insert();
        for(String update:new String[]{"status='INVALID'","status='IN_FLIGHT'","status='PUBLISHED'","status='DEAD_LETTER'",
            "attempt_count=-1","attempt_count=101","notification_type='DM_NEW_MESSAGE'","notification_type='SOCIAL_NEW_BAND_FOLLOWER'",
            "band_id=gen_random_uuid()","lease_owner='owner'","last_error_type='secret detail'","recipient_id=follower_id"}) {
            assertThatThrownBy(() -> jdbc.update("update tbl_follow_notification_outbox set "+update)).isInstanceOf(RuntimeException.class);
        }
        assertThat(jdbc.queryForObject("select status from tbl_follow_notification_outbox",String.class)).isEqualTo("PENDING");
    }
    @Test void missingColumnFailsLoudlyWithoutDroppingExistingData() {
        jdbc.execute(sql);insert();jdbc.execute("alter table tbl_follow_notification_outbox drop column updated_at");
        assertThatThrownBy(() -> jdbc.execute(sql)).hasStackTraceContaining("Incompatible follow outbox column: updated_at");
        assertThat(jdbc.queryForObject("select count(*) from tbl_follow_notification_outbox",Integer.class)).isEqualTo(1);
    }
    @Test void invalidExistingDataRollsBackTheEntireMigration() {
        jdbc.execute(sql);insert();
        jdbc.execute("alter table tbl_follow_notification_outbox drop constraint ck_follow_outbox_status");
        jdbc.update("update tbl_follow_notification_outbox set status='CORRUPT'");
        assertThatThrownBy(() -> jdbc.execute(sql)).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("select status from tbl_follow_notification_outbox",String.class)).isEqualTo("CORRUPT");
        assertThat(jdbc.queryForObject("select count(*) from pg_constraint where conrelid='tbl_follow_notification_outbox'::regclass and conname='ck_follow_outbox_status'",Integer.class)).isZero();
    }
    @Test void missingChecksAndIndexesOnCompatibleSchemaAreRepaired() {
        jdbc.execute(sql);insert();
        jdbc.execute("alter table tbl_follow_notification_outbox drop constraint ck_follow_outbox_lease; drop index idx_follow_outbox_due");
        jdbc.execute(sql);
        assertThatThrownBy(() -> jdbc.update("update tbl_follow_notification_outbox set status='IN_FLIGHT'")).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("select to_regclass('idx_follow_outbox_due') is not null",Boolean.class)).isTrue();
    }
    @Test void sameNamedButMalformedIndexCannotMasqueradeAsReadySchema() {
        jdbc.execute(sql);insert();
        jdbc.execute("drop index idx_follow_outbox_due; create index idx_follow_outbox_due on tbl_follow_notification_outbox(event_id)");
        assertThatThrownBy(() -> jdbc.execute(sql)).hasStackTraceContaining("Incompatible follow outbox index: idx_follow_outbox_due");
        assertThat(jdbc.queryForObject("select count(*) from tbl_follow_notification_outbox",Integer.class)).isEqualTo(1);
    }
}

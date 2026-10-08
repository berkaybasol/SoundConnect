package com.berkayb.soundconnect.modules.notification.service;

import com.berkayb.soundconnect.modules.notification.config.MediaNotificationIdentitySchema;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Forward/replay/rollback use real PostgreSQL JSONB and constraints, never the shared database. */
@Testcontainers
class MediaNotificationIdentityMigrationPostgresTest {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:16.4-alpine")
            .withDatabaseName("media_identity_migration").withUsername("fixture").withPassword("fixture")
            .withLabel("soundconnect.task","media-notification-identity").withReuse(false);
    JdbcTemplate jdbc; DriverManagerDataSource source; String migration;
    @BeforeEach void setup() throws Exception {
        source=new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());jdbc=new JdbcTemplate(source);
        try(var connection=source.getConnection()) { assertThat(connection.getMetaData().getURL()).isEqualTo(POSTGRES.getJdbcUrl()); }
        // Every object in this database is task-owned, and no reused container is permitted.
        jdbc.execute("drop table if exists tbl_notification_receipt,tbl_notification,soundconnect_schema_migrations cascade");
        jdbc.execute("create table tbl_notification(id uuid primary key,recipient_id uuid not null,type varchar(64),title varchar(160),message varchar(1000),payload jsonb,is_read boolean,source_event_id uuid unique,created_at timestamptz,occurred_at timestamptz,updated_at timestamptz)");
        jdbc.execute("create table tbl_notification_receipt(source_event_id uuid primary key,recipient_id uuid,recorded_at timestamptz)");
        migration=Files.readString(Path.of("scripts/db/2026-09-28-media-notification-identity.sql"));
    }
    void migrate() throws Exception {try(var c=source.getConnection();var s=c.createStatement()){s.execute(migration);}}
    UUID row(String type,String payload,boolean read) {
        UUID id=UUID.randomUUID(),event=UUID.randomUUID(),recipient=UUID.randomUUID();
        jdbc.update("insert into tbl_notification values (?,?,?,'old private name','old message',?::jsonb,?,?,now(),now(),now())",id,recipient,type,payload,read,event);
        jdbc.update("insert into tbl_notification_receipt values(?,?,now())",event,recipient);return id;
    }
    String row(UUID id) {return jdbc.queryForObject("select to_jsonb(n)::text from tbl_notification n where id=?",String.class,id);}
    String preserved(UUID id) {return jdbc.queryForObject("select (to_jsonb(n)-'title'-'message'-'payload')::text from tbl_notification n where id=?",String.class,id);}
    List<String> receipts() {return jdbc.queryForList("select to_jsonb(r)::text from tbl_notification_receipt r order by source_event_id",String.class);}

    @Test void freshStartupRequiresMigrationAndReplayPreservesMarker() throws Exception {
        var gate=new MediaNotificationIdentitySchema(jdbc);
        assertThatThrownBy(() -> gate.run(null)).isInstanceOf(IllegalStateException.class);
        migrate();gate.run(null);
        String marker=jdbc.queryForObject("select to_jsonb(m)::text from soundconnect_schema_migrations m",String.class);
        migrate();gate.run(null);
        assertThat(jdbc.queryForObject("select to_jsonb(m)::text from soundconnect_schema_migrations m",String.class)).isEqualTo(marker);
    }

    @Test void mixedLegacyNewAndUnrelatedRowsRetainReadSourceTargetIdsAndReceipts() throws Exception {
        UUID actor=UUID.randomUUID(),target=UUID.randomUUID();
        UUID oldLike=row("SOCIAL_LIKE","{\"targetType\":\"MEDIA\",\"targetId\":\""+target+"\",\"unrelated\":{\"keep\":7}}",false);
        UUID oldComment=row("SOCIAL_COMMENT","{\"targetType\":\"MEDIA\",\"commentId\":\""+UUID.randomUUID()+"\",\"actorUsername\":\"old alias\",\"actorAvatarUrl\":\"https://private.invalid/a\"}",true);
        UUID current=row("SOCIAL_COMMENT","{\"targetType\":\"MEDIA\",\"mediaIdentityVersion\":1,\"actorId\":\""+actor.toString().toUpperCase(Locale.ROOT)+"\",\"targetId\":\""+target+"\"}",false);
        UUID other=row("SOCIAL_LIKE","{\"targetType\":\"EVENT\",\"actorUsername\":\"not this scope\"}",true);
        UUID dm=row("DM_NEW_MESSAGE","{\"targetType\":\"MEDIA\",\"senderUsername\":\"not this scope\"}",false);
        List<UUID> ids=List.of(oldLike,oldComment,current,other,dm);
        var preserved=ids.stream().map(this::preserved).toList();var receiptBefore=receipts();var otherBefore=row(other);var dmBefore=row(dm);
        migrate();
        assertThat(ids.stream().map(this::preserved).toList()).isEqualTo(preserved);assertThat(receipts()).isEqualTo(receiptBefore);
        assertThat(row(other)).isEqualTo(otherBefore);assertThat(row(dm)).isEqualTo(dmBefore);
        assertThat(row(oldLike)).contains("Bir kullanıcı","\"keep\": 7",target.toString()).doesNotContain("old private name");
        assertThat(row(oldComment)).contains("Bir kullanıcı").doesNotContain("old alias","private.invalid","old private name");
        assertThat(jdbc.queryForObject("select payload->>'mediaIdentityVersion' from tbl_notification where id=?",String.class,oldComment)).isEqualTo("0");
        assertThat(jdbc.queryForObject("select payload->>'actorId' from tbl_notification where id=?",String.class,current)).isEqualTo(actor.toString());
        var after=ids.stream().map(this::row).toList();migrate();assertThat(ids.stream().map(this::row).toList()).isEqualTo(after);
    }

    @Test void malformedVersionUuidJsonShapesNeverGuessAnActor() throws Exception {
        List<UUID> malformed=new ArrayList<>();
        for(String actor:List.of("\"1-1-1-1-1\"","\"bad\"","null","42","[]","{}"))
            malformed.add(row("SOCIAL_LIKE","{\"targetType\":\"MEDIA\",\"mediaIdentityVersion\":1,\"actorId\":"+actor+"}",false));
        for(String version:List.of("null","\"1\"","1.0","2","{}"))
            malformed.add(row("SOCIAL_COMMENT","{\"targetType\":\"MEDIA\",\"mediaIdentityVersion\":"+version+",\"actorId\":\""+UUID.randomUUID()+"\"}",true));
        UUID unversioned=row("SOCIAL_LIKE","{\"targetType\":\"MEDIA\",\"actorId\":\""+UUID.randomUUID()+"\"}",false);malformed.add(unversioned);
        List<UUID> outside=List.of(row("SOCIAL_LIKE","[]",true),row("SOCIAL_COMMENT","null",false),row("SOCIAL_LIKE","\"not an object\"",false));
        var before=outside.stream().map(this::row).toList();
        assertThatThrownBy(() -> row("SOCIAL_LIKE","{malformed",false)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        migrate();
        for(UUID id:malformed) {
            assertThat(jdbc.queryForObject("select payload->>'mediaIdentityVersion' from tbl_notification where id=?",String.class,id)).isEqualTo("0");
            assertThat(jdbc.queryForObject("select jsonb_exists(payload,'actorId') from tbl_notification where id=?",Boolean.class,id)).isFalse();
        }
        assertThat(outside.stream().map(this::row).toList()).isEqualTo(before);
    }

    @Test void failureHalfwayRollsBackAllRowsSchemaMarkerAndTriggerThenCanRetry() throws Exception {
        UUID a=row("SOCIAL_LIKE","{\"targetType\":\"MEDIA\"}",false),b=row("SOCIAL_COMMENT","{\"targetType\":\"MEDIA\"}",true);
        var before=List.of(row(a),row(b));var receiptBefore=receipts();
        String broken=migration.replace("ALTER TABLE tbl_notification ADD CONSTRAINT ck_media_notification_identity CHECK (",
                "SELECT 1/0;\nALTER TABLE tbl_notification ADD CONSTRAINT ck_media_notification_identity CHECK (");
        try(var c=source.getConnection();var s=c.createStatement()) {
            assertThatThrownBy(() -> s.execute(broken)).isInstanceOf(SQLException.class);
            s.execute("ROLLBACK");
        }
        assertThat(List.of(row(a),row(b))).isEqualTo(before);assertThat(receipts()).isEqualTo(receiptBefore);
        assertThat(jdbc.queryForObject("select to_regclass('soundconnect_schema_migrations') is null",Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from pg_trigger where tgrelid='tbl_notification'::regclass and tgname='trg_media_notification_identity'",Integer.class)).isZero();
        migrate();new MediaNotificationIdentitySchema(jdbc).run(null);
    }

    @Test void oldProducerCannotReintroduceNamesAfterMigrationAndConstraintRejectsBypass() throws Exception {
        migrate();UUID id=row("SOCIAL_COMMENT","{\"targetType\":\"MEDIA\",\"actorUsername\":\"old\"}",false);
        assertThat(row(id)).contains("Bir kullanıcı").doesNotContain("old private name","actorUsername");
        jdbc.update("update tbl_notification set title='new stale name' where id=?",id);
        assertThat(row(id)).doesNotContain("new stale name");
        jdbc.execute("alter table tbl_notification disable trigger trg_media_notification_identity");
        assertThatThrownBy(() -> jdbc.update("update tbl_notification set title='bypass' where id=?",id))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> new MediaNotificationIdentitySchema(jdbc).run(null)).isInstanceOf(IllegalStateException.class);
        jdbc.execute("alter table tbl_notification enable trigger trg_media_notification_identity");
        new MediaNotificationIdentitySchema(jdbc).run(null);
        jdbc.execute("alter table tbl_notification drop constraint ck_media_notification_identity");
        assertThatThrownBy(() -> new MediaNotificationIdentitySchema(jdbc).run(null)).isInstanceOf(IllegalStateException.class);
    }

    @Test void registryContainsForwardStepAfterV5WithoutAddingNativeCapability() throws Exception {
        String script=Files.readString(Path.of("scripts/dev.ps1"));
        assertThat(script.indexOf("Path = Join-Path $ProjectRoot \"scripts\\db\\2026-09-28-media-notification-identity.sql\""))
                .isGreaterThan(script.indexOf("Path = Join-Path $ProjectRoot \"scripts\\db\\2026-09-28-push-native-follow-capability.sql\""));
        assertThat(migration).doesNotContain("tbl_push_device","ANDROID_NATIVE_V6","SOUNDCONNECT_PUSH_ALLOWED_TYPES");
    }
}

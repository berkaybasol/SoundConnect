package com.berkayb.soundconnect.modules.event.audience;

import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.nio.file.*;
import java.sql.*;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/** Rehearses the exact migration against a verified disposable PostgreSQL legacy schema. */
@Testcontainers
class EventPostMigrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:16.4-alpine")
            .withDatabaseName("event_post_migration_test").withUsername("test").withPassword("test").withReuse(false);
    Connection connection;
    String migration;
    UUID owner=UUID.randomUUID(),event=UUID.randomUUID();

    @BeforeEach void legacySchema() throws Exception {
        connection=DriverManager.getConnection(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());
        assertThat(POSTGRES.isRunning()).isTrue();
        assertThat(connection.getMetaData().getURL()).isEqualTo(POSTGRES.getJdbcUrl());
        assertThat(connection.getCatalog()).isEqualTo(POSTGRES.getDatabaseName());
        execute("drop schema public cascade; create schema public");
        execute("create table tbl_user(id uuid primary key)");
        execute(Files.readString(Path.of("scripts/db/2026-09-08-event-audience-intents.sql")));
        execute("""
                create table tbl_comment(id uuid primary key,target_type varchar(50) not null,
                    constraint old_comment_enum check(target_type in ('EVENT','MEDIA','OVERTHINKING')),
                    constraint unrelated_comment_check check(id<>'00000000-0000-0000-0000-000000000000'));
                create table tbl_like(id uuid primary key,target_type varchar(50) not null,
                    constraint old_like_enum check(target_type in ('EVENT','MEDIA','OVERTHINKING','COMMENT')))
                """);
        execute("insert into tbl_user values ('"+owner+"')");
        execute("insert into tbl_event_audience_intent(user_id,event_id,intent,published_on_profile,note,version,published_at) values ('"
                +owner+"','"+event+"','GOING',true,'Retained note',7,now())");
        execute("insert into tbl_event_audience_intent(user_id,event_id,intent) values ('"+owner+"','"+UUID.randomUUID()+"','THINKING')");
        execute("insert into tbl_comment values ('"+UUID.randomUUID()+"','EVENT')");
        migration=Files.readString(Path.of("scripts/db/2026-09-09-event-post-comments.sql"));
    }
    @AfterEach void close() throws Exception { if(connection!=null) connection.close(); }

    @Test void backfillsOnlyPublishedRowsKeepsIdentityAcrossRerunsAndPreservesConversationsAndConstraints() throws Exception {
        execute(migration);
        String id=value("select post_id::text from tbl_event_audience_intent where published_on_profile");
        assertThat(UUID.fromString(id)).isNotEqualTo(event);
        execute(migration);
        assertThat(value("select post_id::text from tbl_event_audience_intent where published_on_profile")).isEqualTo(id);
        assertThat(value("select note from tbl_event_audience_intent where published_on_profile")).isEqualTo("Retained note");
        assertThat(value("select version::text from tbl_event_audience_intent where published_on_profile")).isEqualTo("7");
        assertThat(value("select count(*)::text from tbl_event_audience_intent where not published_on_profile and post_id is null")).isEqualTo("1");
        assertThat(value("select target_type from tbl_comment")).isEqualTo("EVENT");
        execute("insert into tbl_comment values ('"+UUID.randomUUID()+"','EVENT_POST')");
        execute("insert into tbl_like values ('"+UUID.randomUUID()+"','EVENT_POST'),('"+UUID.randomUUID()+"','COMMENT')");
        assertThatThrownBy(() -> execute("insert into tbl_comment values ('"+UUID.randomUUID()+"','INVALID')")).isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> execute("insert into tbl_comment values ('00000000-0000-0000-0000-000000000000','EVENT_POST')")).isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> execute("update tbl_event_audience_intent set post_id=null where published_on_profile")).isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> execute("update tbl_event_audience_intent set post_id='"+id+"' where not published_on_profile")).isInstanceOf(SQLException.class);
    }

    @Test void incompatibleIndexFailsAtomicallyBeforeEnablingNewTargets() throws Exception {
        execute("create index ux_event_audience_post_id on tbl_event_audience_intent(event_id)");
        assertThatThrownBy(() -> execute(migration)).isInstanceOf(SQLException.class).hasMessageContaining("incompatible definition");
        execute("rollback");
        assertThat(value("select count(*)::text from information_schema.columns where table_name='tbl_event_audience_intent' and column_name='post_id'")).isEqualTo("0");
        assertThatThrownBy(() -> execute("insert into tbl_comment values ('"+UUID.randomUUID()+"','EVENT_POST')")).isInstanceOf(SQLException.class);
    }

    private void execute(String sql) throws SQLException { try(var statement=connection.createStatement()) { statement.execute(sql); } }
    private String value(String sql) throws SQLException {
        try(var statement=connection.createStatement();var result=statement.executeQuery(sql)) { result.next(); return result.getString(1); }
    }
}

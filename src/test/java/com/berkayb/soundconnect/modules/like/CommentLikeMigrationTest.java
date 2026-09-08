package com.berkayb.soundconnect.modules.like;

import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/** Migration rehearsals use independent schemas in an explicitly verified disposable database only. */
@Testcontainers
class CommentLikeMigrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:16.4-alpine")
            .withDatabaseName("comment_like_migration_test").withUsername("comment_like_test")
            .withPassword("comment_like_test").withReuse(false);
    Connection connection;
    String migration;

    @BeforeEach void isolatedLegacySchema() throws Exception {
        assertThat(POSTGRES.isRunning()).isTrue();
        connection=DriverManager.getConnection(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());
        assertThat(connection.getMetaData().getURL()).isEqualTo(POSTGRES.getJdbcUrl());
        assertThat(connection.getCatalog()).isEqualTo(POSTGRES.getDatabaseName());
        String schema="comment_like_"+UUID.randomUUID().toString().replace("-","");
        execute("create schema "+schema); connection.setSchema(schema);
        execute("""
                create table tbl_like(id uuid primary key,user_id uuid not null,target_type varchar(255) not null,target_id uuid not null,
                  constraint uk_like_user_target unique(user_id,target_type,target_id),
                  constraint legacy_enum_name check(target_type in ('EVENT','MEDIA','OVERTHINKING')),
                  constraint unrelated_target_check check(target_id<>'00000000-0000-0000-0000-000000000000'));
                create table tbl_comment(id uuid primary key,target_type varchar(50) not null
                  check(target_type in ('EVENT','MEDIA','OVERTHINKING')))
                """);
        migration=Files.readString(Path.of("scripts/db/2026-09-09-comment-likes.sql"));
    }

    @AfterEach void closeConnection() throws Exception { if(connection!=null) connection.close(); }

    @Test void rerunnableMigrationWidensOnlyLikeEnumAndPreservesExistingRowsAndChecks() throws Exception {
        for(String type : new String[]{"EVENT","MEDIA","OVERTHINKING"}) insertLike(type);
        execute(migration); execute(migration);
        insertLike("COMMENT");
        assertThat(number("select count(*) from tbl_like")).isEqualTo(4);
        assertThat(number("select count(*) from pg_constraint where conrelid='tbl_like'::regclass "
                +"and conname in ('legacy_enum_name','unrelated_target_check') and convalidated")).isEqualTo(2);
        assertThat(number("select count(*) from pg_indexes where schemaname=current_schema() and indexname='idx_like_target' "))
                .isEqualTo(1);
        assertThatThrownBy(() -> insertLike("UNKNOWN")).isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> execute("insert into tbl_comment values ('"+UUID.randomUUID()+"','COMMENT')"))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> execute("insert into tbl_like values ('"+UUID.randomUUID()+"','"+UUID.randomUUID()
                +"','COMMENT','00000000-0000-0000-0000-000000000000')")).isInstanceOf(SQLException.class);
    }

    @Test void missingUniqueInvariantFailsBeforeAnySchemaChange() throws Exception {
        execute("alter table tbl_like drop constraint uk_like_user_target");
        assertThatThrownBy(() -> execute(migration)).isInstanceOf(SQLException.class)
                .hasMessageContaining("existing unique user/type/target index");
        execute("rollback");
        assertThat(number("select count(*) from pg_indexes where schemaname=current_schema() and indexname='idx_like_target'"))
                .isZero();
        assertThatThrownBy(() -> insertLike("COMMENT")).isInstanceOf(SQLException.class);
    }

    @Test void incompatibleNamedIndexAbortsAndRollsBackEnumWidening() throws Exception {
        execute("create index idx_like_target on tbl_like(user_id)");
        assertThatThrownBy(() -> execute(migration)).isInstanceOf(SQLException.class)
                .hasMessageContaining("incompatible definition");
        execute("rollback");
        assertThatThrownBy(() -> insertLike("COMMENT")).isInstanceOf(SQLException.class);
    }

    @Test void deferrableUniqueCannotBeMistakenForAnUpsertConflictArbiter() throws Exception {
        execute("alter table tbl_like drop constraint uk_like_user_target");
        execute("alter table tbl_like add constraint uk_like_user_target unique(user_id,target_type,target_id) deferrable");
        assertThatThrownBy(() -> execute(migration)).isInstanceOf(SQLException.class)
                .hasMessageContaining("existing unique user/type/target index");
        execute("rollback");
        assertThatThrownBy(() -> insertLike("COMMENT")).isInstanceOf(SQLException.class);
    }

    @Test void legacyUnvalidatedEnumCheckKeepsItsValidationStateOnRerun() throws Exception {
        execute("alter table tbl_like drop constraint legacy_enum_name");
        execute("alter table tbl_like add constraint legacy_enum_name check(target_type in ('EVENT','MEDIA','OVERTHINKING')) not valid");
        execute(migration); execute(migration); insertLike("COMMENT");
        assertThat(number("select count(*) from pg_constraint where conrelid='tbl_like'::regclass "
                +"and conname='legacy_enum_name' and not convalidated")).isEqualTo(1);
    }

    private void insertLike(String type) throws SQLException {
        try(var statement=connection.prepareStatement("insert into tbl_like values (?,?,?,?)")) {
            statement.setObject(1,UUID.randomUUID()); statement.setObject(2,UUID.randomUUID());
            statement.setString(3,type); statement.setObject(4,UUID.randomUUID()); statement.executeUpdate();
        }
    }
    private void execute(String sql) throws SQLException { try(var statement=connection.createStatement()) { statement.execute(sql); } }
    private long number(String sql) throws SQLException {
        try(var statement=connection.createStatement();var result=statement.executeQuery(sql)) { result.next(); return result.getLong(1); }
    }
}

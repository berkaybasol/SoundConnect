package com.berkayb.soundconnect.modules.event.plan;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Executes the exact additive migration only against a disposable, explicitly verified legacy schema. */
@Testcontainers
@Timeout(40)
class EventPlanMigrationPostgresTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine")
            .withDatabaseName("event_plan_migration_test").withUsername("plan_migration")
            .withPassword("plan_migration").withReuse(false);
    private UUID owner;
    private UUID venue;
    private UUID event;
    private UUID listenerPost;
    private UUID comment;
    private String migration;

    @BeforeEach
    void legacySchema() throws Exception {
        assertThat(POSTGRES.isRunning()).isTrue();
        try (var connection = connection()) {
            assertThat(connection.getMetaData().getURL()).isEqualTo(POSTGRES.getJdbcUrl());
            assertThat(connection.getCatalog()).isEqualTo(POSTGRES.getDatabaseName());
        }
        owner = UUID.randomUUID(); venue = UUID.randomUUID(); event = UUID.randomUUID();
        listenerPost = UUID.randomUUID(); comment = UUID.randomUUID();
        execute("drop schema public cascade; create schema public");
        execute("""
                create table tbl_user(id uuid primary key);
                create table tbl_venues(id uuid primary key, owner_id uuid references tbl_user(id));
                create table tbl_event(id uuid primary key, title varchar(255), event_date date);
                create table tbl_event_audience_intent(user_id uuid, event_id uuid, post_id uuid, intent varchar(20),
                    note varchar(500), version bigint, primary key(user_id,event_id));
                create table tbl_comment(id uuid primary key, target_type varchar(40), target_id uuid, text varchar(500));
                create table tbl_like(id uuid primary key, target_type varchar(40), target_id uuid);
                """);
        execute("insert into tbl_user values ('" + owner + "')");
        execute("insert into tbl_venues values ('" + venue + "','" + owner + "')");
        execute("insert into tbl_event values ('" + event + "','Existing concert','2026-09-22')");
        execute("insert into tbl_event_audience_intent values ('" + owner + "','" + event + "','" + listenerPost
                + "','GOING','Existing private/public choice',7)");
        execute("insert into tbl_comment values ('" + comment + "','EVENT_POST','" + listenerPost + "','Original conversation')");
        execute("insert into tbl_like values ('" + UUID.randomUUID() + "','EVENT','" + event + "')");
        migration = Files.readString(Path.of("scripts/db/2026-09-21-event-plans.sql"));
    }

    @Test
    void additiveRerunPreservesExistingEventPublicationConversationAndPlanIdentity() throws Exception {
        execute(migration);
        UUID plan = insertPlan(UUID.randomUUID());
        execute(occurrenceInsert(plan, "2026-09-22", event, "GENERATED"));
        execute("update event_plans set version=9,consent_revision=3 where id='" + plan + "'");
        execute(migration);
        assertThat(value("select id::text from tbl_event")).isEqualTo(event.toString());
        assertThat(value("select post_id::text from tbl_event_audience_intent")).isEqualTo(listenerPost.toString());
        assertThat(value("select version::text from tbl_event_audience_intent")).isEqualTo("7");
        assertThat(value("select text from tbl_comment where id='" + comment + "'")).isEqualTo("Original conversation");
        assertThat(value("select target_id::text from tbl_like")).isEqualTo(event.toString());
        assertThat(value("select event_id::text from event_plan_occurrences where plan_id='" + plan + "'")).isEqualTo(event.toString());
        assertThat(value("select version::text from event_plans where id='" + plan + "'")).isEqualTo("9");
        assertThat(value("select consent_revision::text from event_plans where id='" + plan + "'")).isEqualTo("3");
    }

    @Test
    void eventDeletionKeepsTheScheduledDateAndRejectsItsRecreationAfterMigrationRerun() throws Exception {
        execute(migration);
        UUID plan = insertPlan(UUID.randomUUID());
        execute(occurrenceInsert(plan, "2026-09-22", event, "GENERATED"));
        execute("delete from tbl_event where id='" + event + "'");
        execute(migration);
        assertThat(value("select count(*)::text from event_plan_occurrences where plan_id='" + plan + "' and event_id is null")).isEqualTo("1");
        assertThat(value("select scheduled_date::text from event_plan_occurrences where plan_id='" + plan + "'")).isEqualTo("2026-09-22");
        assertThatThrownBy(() -> execute(occurrenceInsert(plan, "2026-09-22", null, "GENERATED")))
                .isInstanceOfSatisfying(SQLException.class, failure -> assertThat(failure.getSQLState()).isEqualTo("23505"));
        execute(occurrenceInsert(plan, "2026-09-23", null, "SKIPPED"));
        execute(occurrenceInsert(plan, "2026-09-24", null, "CANCELLED"));
        execute(migration);
        assertThat(value("select count(*)::text from event_plan_occurrences where plan_id='" + plan + "'")).isEqualTo("3");
    }

    @Test
    void concurrentLedgerClaimsPermitExactlyOneDateAndOneEventAttachment() throws Exception {
        execute(migration);
        UUID plan = insertPlan(UUID.randomUUID());
        var release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(4)) {
            var attempts = new ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int index = 0; index < 4; index++) {
                attempts.add(executor.submit(() -> {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Start latch timed out");
                    try { execute(occurrenceInsert(plan, "2026-09-22", event, "GENERATED")); return true; }
                    catch (SQLException failure) {
                        assertThat(failure.getSQLState()).isEqualTo("23505");
                        return false;
                    }
                }));
            }
            release.countDown();
            int successes = 0;
            for (var attempt : attempts) if (attempt.get(15, TimeUnit.SECONDS)) successes++;
            assertThat(successes).isEqualTo(1);
        }
        assertThatThrownBy(() -> execute(occurrenceInsert(plan, "2026-09-23", event, "GENERATED")))
                .isInstanceOfSatisfying(SQLException.class, failure -> assertThat(failure.getSQLState()).isEqualTo("23505"));
        assertThat(value("select count(*)::text from event_plan_occurrences where plan_id='" + plan + "'")).isEqualTo("1");
    }

    @Test
    void databaseRejectsInvalidPlanStatesAndReusedCreationKeys() throws Exception {
        execute(migration);
        UUID request = UUID.randomUUID();
        UUID plan = insertPlan(request);
        assertThatThrownBy(() -> insertPlan(request)).isInstanceOfSatisfying(SQLException.class,
                failure -> assertThat(failure.getSQLState()).isEqualTo("23505"));
        for (String assignment : new String[]{"version=-1", "consent_revision=-1", "weekday_mask=0", "weekday_mask=128",
                "until_date='2026-09-20'", "status='UNKNOWN'", "consent_status='UNKNOWN'", "show_on_profile=true",
                "excluded_dates='{}'::jsonb", "musician_profile_id='" + UUID.randomUUID() + "',band_id='" + UUID.randomUUID() + "'"}) {
            assertThatThrownBy(() -> execute("update event_plans set " + assignment + " where id='" + plan + "'"))
                    .as(assignment).isInstanceOfSatisfying(SQLException.class,
                            failure -> assertThat(failure.getSQLState()).isEqualTo("23514"));
        }
        assertThat(value("select version::text from event_plans where id='" + plan + "'")).isEqualTo("0");
    }

    @Test
    void migrationAlsoAddsChecksAndForeignKeysWhenHibernateAlreadyCreatedTheTables() throws Exception {
        execute(migration);
        // Hibernate's scalar UUID fields do not create these logical FKs/checks.
        // Keep the same columns, primary keys and unique indexes to mirror that bootstrap path.
        execute("""
                DO $$ DECLARE item record; BEGIN
                    FOR item IN SELECT conname,conrelid::regclass relation FROM pg_constraint
                        WHERE conrelid IN ('event_plans'::regclass,'event_plan_occurrences'::regclass)
                          AND contype IN ('c','f')
                    LOOP EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I',item.relation,item.conname); END LOOP;
                END $$;
                """);
        execute(migration);
        UUID plan = insertPlan(UUID.randomUUID());
        assertThatThrownBy(() -> execute("update event_plans set version=-1 where id='" + plan + "'"))
                .isInstanceOfSatisfying(SQLException.class, failure -> assertThat(failure.getSQLState()).isEqualTo("23514"));
        assertThatThrownBy(() -> execute("update event_plans set weekday_mask=0 where id='" + plan + "'"))
                .isInstanceOfSatisfying(SQLException.class, failure -> assertThat(failure.getSQLState()).isEqualTo("23514"));
        assertThatThrownBy(() -> execute("update event_plans set organizer_user_id='" + UUID.randomUUID() + "' where id='" + plan + "'"))
                .isInstanceOfSatisfying(SQLException.class, failure -> assertThat(failure.getSQLState()).isEqualTo("23503"));
        assertThatThrownBy(() -> execute(occurrenceInsert(UUID.randomUUID(), "2026-09-22", event, "GENERATED")))
                .isInstanceOfSatisfying(SQLException.class, failure -> assertThat(failure.getSQLState()).isEqualTo("23503"));
        execute(occurrenceInsert(plan, "2026-09-22", event, "GENERATED"));
        execute("delete from tbl_event where id='" + event + "'");
        assertThat(value("select count(*)::text from event_plan_occurrences where plan_id='" + plan + "' and event_id is null")).isEqualTo("1");
    }

    private UUID insertPlan(UUID request) throws SQLException {
        UUID plan = UUID.randomUUID();
        execute("insert into event_plans(id,organizer_user_id,venue_id,client_request_id,creation_hash,start_date,weekday_mask,title,start_time,status,consent_status) values ('"
                + plan + "','" + owner + "','" + venue + "','" + request + "','" + "a".repeat(64)
                + "','2026-09-21',1,'Isolated plan','20:00:00','ACTIVE','NOT_REQUIRED')");
        return plan;
    }

    private String occurrenceInsert(UUID plan, String date, UUID id, String status) {
        return "insert into event_plan_occurrences(plan_id,scheduled_date,event_id,event_date,status) values ('" + plan
                + "','" + date + "'," + (id == null ? "null" : "'" + id + "'") + ",'" + date + "','" + status + "')";
    }
    private Connection connection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
    private void execute(String sql) throws SQLException {
        try (var connection = connection(); var statement = connection.createStatement()) { statement.execute(sql); }
    }
    private String value(String sql) throws SQLException {
        try (var connection = connection(); var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            assertThat(rows.next()).isTrue(); return rows.getString(1);
        }
    }
}

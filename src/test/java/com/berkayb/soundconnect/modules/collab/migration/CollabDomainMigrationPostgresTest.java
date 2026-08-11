package com.berkayb.soundconnect.modules.collab.migration;

import com.berkayb.soundconnect.modules.collab.entity.Collab;
import com.berkayb.soundconnect.modules.collab.entity.CollabActor;
import com.berkayb.soundconnect.modules.collab.entity.CollabApplication;
import com.berkayb.soundconnect.modules.collab.entity.CollabJob;
import com.berkayb.soundconnect.modules.collab.entity.CollabReport;
import com.berkayb.soundconnect.modules.collab.entity.CollabReview;
import com.berkayb.soundconnect.modules.collab.entity.CollabSavedListing;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class CollabDomainMigrationPostgresTest {

    private static final String OWNER_ID = "00000000-0000-0000-0000-000000000101";
    private static final String APPLICANT_ONE_ID = "00000000-0000-0000-0000-000000000102";
    private static final String APPLICANT_TWO_ID = "00000000-0000-0000-0000-000000000103";
    private static final String CITY_ID = "00000000-0000-0000-0000-000000000201";
    private static final String INSTRUMENT_ID = "00000000-0000-0000-0000-000000000301";
    private static final String LEGACY_COLLAB_ID = "00000000-0000-0000-0000-000000000401";
    private static final String LEGACY_SLOT_ID = "00000000-0000-0000-0000-000000000402";
    private static final String PUBLISHER_ACTOR_ID = "00000000-0000-0000-0000-000000000501";
    private static final String APPLICANT_ONE_ACTOR_ID = "00000000-0000-0000-0000-000000000502";
    private static final String APPLICANT_TWO_ACTOR_ID = "00000000-0000-0000-0000-000000000503";
    private static final String LISTING_ID = "00000000-0000-0000-0000-000000000601";
    private static final String APPLICATION_ONE_ID = "00000000-0000-0000-0000-000000000701";
    private static final String APPLICATION_TWO_ID = "00000000-0000-0000-0000-000000000702";
    private static final String JOB_ID = "00000000-0000-0000-0000-000000000801";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine")
            .withDatabaseName("soundconnect_collab_migration")
            .withUsername("soundconnect")
            .withPassword("soundconnect");

    @BeforeEach
    void createMinimalLegacySchema() throws SQLException {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    DROP TABLE IF EXISTS tbl_collab_review CASCADE;
                    DROP TABLE IF EXISTS tbl_collab_report CASCADE;
                    DROP TABLE IF EXISTS tbl_collab_saved_listing CASCADE;
                    DROP TABLE IF EXISTS tbl_collab_job CASCADE;
                    DROP TABLE IF EXISTS tbl_collab_application CASCADE;
                    DROP TABLE IF EXISTS tbl_collab_genre CASCADE;
                    DROP TABLE IF EXISTS collab_target_roles CASCADE;
                    DROP TABLE IF EXISTS tbl_collab_required_slot CASCADE;
                    DROP TABLE IF EXISTS tbl_collab CASCADE;
                    DROP TABLE IF EXISTS tbl_collab_legacy_110826 CASCADE;
                    DROP TABLE IF EXISTS tbl_collab_actor CASCADE;
                    DROP TABLE IF EXISTS tbl_instrument CASCADE;
                    DROP TABLE IF EXISTS tbl_city CASCADE;
                    DROP TABLE IF EXISTS tbl_user CASCADE;
                    """);

            statement.execute("CREATE TABLE tbl_user (id uuid PRIMARY KEY)");
            statement.execute("CREATE TABLE tbl_city (id uuid PRIMARY KEY)");
            statement.execute("CREATE TABLE tbl_instrument (id uuid PRIMARY KEY)");
            statement.execute("""
                    INSERT INTO tbl_user (id) VALUES
                        ('%s'), ('%s'), ('%s');
                    INSERT INTO tbl_city (id) VALUES ('%s');
                    INSERT INTO tbl_instrument (id) VALUES ('%s');
                    """.formatted(
                    OWNER_ID,
                    APPLICANT_ONE_ID,
                    APPLICANT_TWO_ID,
                    CITY_ID,
                    INSTRUMENT_ID
            ));

            statement.execute("""
                    CREATE TABLE tbl_collab (
                        id uuid PRIMARY KEY,
                        created_at timestamp without time zone,
                        updated_at timestamp without time zone,
                        owner_user_id uuid NOT NULL REFERENCES tbl_user (id),
                        owner_role varchar(40) NOT NULL,
                        category varchar(40) NOT NULL,
                        title varchar(128) NOT NULL,
                        description varchar(2048),
                        price integer,
                        daily boolean NOT NULL,
                        expiration_time timestamp without time zone,
                        city_id uuid REFERENCES tbl_city (id)
                    );

                    CREATE TABLE collab_target_roles (
                        collab_id uuid NOT NULL,
                        role varchar(40) NOT NULL,
                        CONSTRAINT fk_legacy_target_listing
                            FOREIGN KEY (collab_id) REFERENCES tbl_collab (id)
                    );

                    CREATE TABLE tbl_collab_required_slot (
                        id uuid PRIMARY KEY,
                        collab_id uuid NOT NULL,
                        instrument_id uuid NOT NULL REFERENCES tbl_instrument (id),
                        required_count integer NOT NULL,
                        filled_count integer NOT NULL,
                        CONSTRAINT fk_legacy_slot_listing
                            FOREIGN KEY (collab_id) REFERENCES tbl_collab (id)
                    );
                    """);

            statement.execute("""
                    INSERT INTO tbl_collab
                        (id, owner_user_id, owner_role, category, title, description,
                         price, daily, expiration_time, city_id)
                    VALUES
                        ('%s', '%s', 'MUSICIAN', 'MUSICIAN_WANTED', 'Legacy ilan',
                         'Bu satir migration sirasinda kaybolmamalidir.', 2500, true,
                         TIMESTAMP '2026-08-12 20:00:00', '%s');

                    INSERT INTO collab_target_roles (collab_id, role)
                    VALUES ('%s', 'MUSICIAN');

                    INSERT INTO tbl_collab_required_slot
                        (id, collab_id, instrument_id, required_count, filled_count)
                    VALUES ('%s', '%s', '%s', 1, 0);
                    """.formatted(
                    LEGACY_COLLAB_ID,
                    OWNER_ID,
                    CITY_ID,
                    LEGACY_COLLAB_ID,
                    LEGACY_SLOT_ID,
                    LEGACY_COLLAB_ID,
                    INSTRUMENT_ID
            ));
        }
    }

    @Test
    void migrationPreservesLegacyRowsKeepsDependentForeignKeysAndIsRerunnable() throws Exception {
        String migration = migrationSql();
        assertThat(migration).contains("BEGIN;", "COMMIT;", "tbl_collab_legacy_110826");

        executeMigration(migration);

        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            assertThat(singleInt(statement, "SELECT count(*) FROM tbl_collab_legacy_110826"))
                    .isEqualTo(1);
            assertThat(singleString(statement, """
                    SELECT title FROM tbl_collab_legacy_110826
                     WHERE id = '%s'
                    """.formatted(LEGACY_COLLAB_ID))).isEqualTo("Legacy ilan");
            assertThat(singleInt(statement, "SELECT count(*) FROM collab_target_roles"))
                    .isEqualTo(1);
            assertThat(singleInt(statement, "SELECT count(*) FROM tbl_collab_required_slot"))
                    .isEqualTo(1);
            assertThat(referencedTable(statement, "collab_target_roles", "fk_legacy_target_listing"))
                    .isEqualTo("tbl_collab_legacy_110826");
            assertThat(referencedTable(statement, "tbl_collab_required_slot", "fk_legacy_slot_listing"))
                    .isEqualTo("tbl_collab_legacy_110826");
            assertThat(singleInt(statement, "SELECT count(*) FROM tbl_collab"))
                    .isZero();
        }

        // Operators may safely rerun after an uncertain deployment result.
        executeMigration(migration);

        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            assertThat(singleInt(statement, "SELECT count(*) FROM tbl_collab_legacy_110826"))
                    .isEqualTo(1);
            assertThat(singleInt(statement, "SELECT count(*) FROM collab_target_roles"))
                    .isEqualTo(1);
            assertThat(singleInt(statement, "SELECT count(*) FROM tbl_collab_required_slot"))
                    .isEqualTo(1);
            assertThat(singleInt(statement, """
                    SELECT count(*)
                      FROM information_schema.tables
                     WHERE table_schema = current_schema()
                       AND table_name IN (
                           'tbl_collab_actor', 'tbl_collab', 'tbl_collab_genre',
                           'tbl_collab_application', 'tbl_collab_job', 'tbl_collab_review',
                           'tbl_collab_saved_listing', 'tbl_collab_report'
                       )
                    """)).isEqualTo(8);
            assertThat(singleInt(statement, """
                    SELECT count(*)
                      FROM information_schema.columns
                     WHERE table_schema = current_schema()
                       AND table_name = 'tbl_collab'
                       AND column_name IN ('daily', 'owner_role', 'required_count', 'filled_count')
                    """)).isZero();
        }
    }

    @Test
    void canonicalTablesMatchJpaMappingsAndExposeProductionIndexes() throws Exception {
        executeMigration(migrationSql());

        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            assertEntityColumns(
                    connection,
                    CollabActor.class,
                    Collab.class,
                    CollabApplication.class,
                    CollabJob.class,
                    CollabReview.class,
                    CollabSavedListing.class,
                    CollabReport.class
            );

            assertThat(columns(connection, "tbl_collab_genre"))
                    .containsExactly("collab_id", "genre", "position");
            assertThat(singleString(statement, """
                    SELECT data_type
                      FROM information_schema.columns
                     WHERE table_schema = current_schema()
                       AND table_name = 'tbl_collab'
                       AND column_name = 'created_at'
                    """)).isEqualTo("timestamp without time zone");
            assertThat(singleString(statement, """
                    SELECT data_type
                      FROM information_schema.columns
                     WHERE table_schema = current_schema()
                       AND table_name = 'tbl_collab'
                       AND column_name = 'scheduled_at'
                    """)).isEqualTo("timestamp with time zone");

            Set<String> indexes = new TreeSet<>();
            try (ResultSet result = statement.executeQuery("""
                    SELECT indexname
                      FROM pg_indexes
                     WHERE schemaname = current_schema()
                       AND tablename LIKE 'tbl_collab%'
                    """)) {
                while (result.next()) {
                    indexes.add(result.getString(1));
                }
            }
            assertThat(indexes).contains(
                    "idx_collab_discovery",
                    "idx_collab_city_discovery",
                    "idx_collab_wanted_instrument",
                    "idx_collab_wanted_branch",
                    "idx_collab_publisher",
                    "idx_collab_expiry",
                    "idx_collab_application_listing",
                    "idx_collab_application_user",
                    "uk_collab_one_accepted_application",
                    "idx_collab_job_publisher",
                    "idx_collab_job_applicant",
                    "idx_collab_review_target",
                    "idx_collab_saved_user",
                    "idx_collab_report_listing",
                    "idx_collab_report_reason"
            );
            assertThat(singleString(statement, """
                    SELECT indexdef
                      FROM pg_indexes
                     WHERE schemaname = current_schema()
                       AND indexname = 'uk_collab_one_accepted_application'
                    """)).contains("UNIQUE", "WHERE", "ACCEPTED");
        }
    }

    @Test
    void databaseConstraintsRejectInvalidDomainStatesAndConcurrencyConflicts() throws Exception {
        executeMigration(migrationSql());

        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            seedCanonicalActorsAndListing(statement);

            assertSqlState("23514", () -> statement.execute(validListingValues(
                    "00000000-0000-0000-0000-000000000611",
                    "'BAND'",
                    "'%s'".formatted(INSTRUMENT_ID),
                    "NULL",
                    "NULL",
                    "'REGULAR'",
                    "NULL",
                    "NULL",
                    "NULL",
                    "NULL",
                    "'DRAFT'",
                    "NULL",
                    "NULL",
                    "NULL",
                    "repeat('b', 64)"
            )));

            assertSqlState("23514", () -> statement.execute(validListingValues(
                    "00000000-0000-0000-0000-000000000612",
                    "'MUSICIAN'",
                    "'%s'".formatted(INSTRUMENT_ID),
                    "NULL",
                    "NULL",
                    "'EXTRA'",
                    "TIMESTAMPTZ '2026-08-12 20:00:00+03'",
                    "TIMESTAMPTZ '2026-08-12 21:00:00+03'",
                    "NULL",
                    "NULL",
                    "'DRAFT'",
                    "NULL",
                    "NULL",
                    "NULL",
                    "repeat('c', 64)"
            )));

            assertSqlState("23514", () -> statement.execute(validListingValues(
                    "00000000-0000-0000-0000-000000000613",
                    "'MUSICIAN'",
                    "'%s'".formatted(INSTRUMENT_ID),
                    "NULL",
                    "NULL",
                    "'REGULAR'",
                    "NULL",
                    "NULL",
                    "10000",
                    "NULL",
                    "'DRAFT'",
                    "NULL",
                    "NULL",
                    "NULL",
                    "repeat('d', 64)"
            )));

            assertSqlState("23514", () -> statement.execute(validListingValues(
                    "00000000-0000-0000-0000-000000000614",
                    "'MUSICIAN'",
                    "'%s'".formatted(INSTRUMENT_ID),
                    "NULL",
                    "NULL",
                    "'REGULAR'",
                    "NULL",
                    "NULL",
                    "NULL",
                    "NULL",
                    "'OPEN'",
                    "NULL",
                    "NULL",
                    "NULL",
                    "repeat('e', 64)"
            )));

            assertSqlState("23514", () -> statement.execute(validListingValues(
                    "00000000-0000-0000-0000-000000000615",
                    "'MUSICIAN'",
                    "'%s'".formatted(INSTRUMENT_ID),
                    "NULL",
                    "NULL",
                    "'REGULAR'",
                    "NULL",
                    "NULL",
                    "NULL",
                    "NULL",
                    "'DRAFT'",
                    "NULL",
                    "NULL",
                    "NULL",
                    "'not-a-sha256'"
            )));

            statement.execute("""
                    INSERT INTO tbl_collab_genre (collab_id, position, genre) VALUES
                        ('%s', 0, 'Rock'),
                        ('%s', 1, 'Blues'),
                        ('%s', 2, 'Funk')
                    """.formatted(LISTING_ID, LISTING_ID, LISTING_ID));
            assertSqlState("23514", () -> statement.execute("""
                    INSERT INTO tbl_collab_genre (collab_id, position, genre)
                    VALUES ('%s', 3, 'Jazz')
                    """.formatted(LISTING_ID)));

            assertSqlState("23514", () -> statement.execute(applicationInsert(
                    "00000000-0000-0000-0000-000000000703",
                    APPLICANT_ONE_ACTOR_ID,
                    APPLICANT_ONE_ID,
                    "00000000-0000-0000-0000-000000000713",
                    "'12-34'",
                    "'PENDING'",
                    "NULL"
            )));

            statement.execute(applicationInsert(
                    APPLICATION_ONE_ID,
                    APPLICANT_ONE_ACTOR_ID,
                    APPLICANT_ONE_ID,
                    "00000000-0000-0000-0000-000000000711",
                    "'+90 532 111 22 33'",
                    "'ACCEPTED'",
                    "TIMESTAMPTZ '2026-08-11 10:10:00+03'"
            ));
            assertSqlState("23505", () -> statement.execute(applicationInsert(
                    "00000000-0000-0000-0000-000000000704",
                    APPLICANT_TWO_ACTOR_ID,
                    APPLICANT_ONE_ID,
                    "00000000-0000-0000-0000-000000000714",
                    "'0532 111 22 33'",
                    "'PENDING'",
                    "NULL"
            )));
            statement.execute(applicationInsert(
                    APPLICATION_TWO_ID,
                    APPLICANT_TWO_ACTOR_ID,
                    APPLICANT_TWO_ID,
                    "00000000-0000-0000-0000-000000000712",
                    "'0532 222 33 44'",
                    "'PENDING'",
                    "NULL"
            ));

            assertSqlState("23505", () -> statement.execute("""
                    UPDATE tbl_collab_application
                       SET status = 'ACCEPTED',
                           status_changed_at = TIMESTAMPTZ '2026-08-11 10:11:00+03',
                           decided_at = TIMESTAMPTZ '2026-08-11 10:11:00+03'
                     WHERE id = '%s'
                    """.formatted(APPLICATION_TWO_ID)));

            assertSqlState("23503", () -> statement.execute("""
                    INSERT INTO tbl_collab_job
                        (id, collab_id, application_id, publisher_actor_id, applicant_actor_id,
                         publisher_user_id, applicant_user_id, status)
                    VALUES
                        ('00000000-0000-0000-0000-000000000802', '%s', '%s', '%s', '%s', '%s', '%s', 'ACTIVE')
                    """.formatted(
                    LISTING_ID,
                    APPLICATION_ONE_ID,
                    PUBLISHER_ACTOR_ID,
                    APPLICANT_TWO_ACTOR_ID,
                    OWNER_ID,
                    APPLICANT_ONE_ID
            )));

            statement.execute("""
                    INSERT INTO tbl_collab_job
                        (id, collab_id, application_id, publisher_actor_id, applicant_actor_id,
                         publisher_user_id, applicant_user_id, status)
                    VALUES
                        ('%s', '%s', '%s', '%s', '%s', '%s', '%s', 'ACTIVE')
                    """.formatted(
                    JOB_ID,
                    LISTING_ID,
                    APPLICATION_ONE_ID,
                    PUBLISHER_ACTOR_ID,
                    APPLICANT_ONE_ACTOR_ID,
                    OWNER_ID,
                    APPLICANT_ONE_ID
            ));

            assertSqlState("23514", () -> statement.execute("""
                    UPDATE tbl_collab_job SET status = 'COMPLETED' WHERE id = '%s'
                    """.formatted(JOB_ID)));

            statement.execute("""
                    UPDATE tbl_collab_job
                       SET status = 'COMPLETED',
                           publisher_confirmed_at = TIMESTAMPTZ '2026-08-11 11:00:00+03',
                           applicant_confirmed_at = TIMESTAMPTZ '2026-08-11 11:01:00+03',
                           completed_at = TIMESTAMPTZ '2026-08-11 11:01:00+03'
                     WHERE id = '%s'
                    """.formatted(JOB_ID));

            assertSqlState("23514", () -> statement.execute("""
                    INSERT INTO tbl_collab_review
                        (id, job_id, reviewer_actor_id, target_actor_id, reviewer_user_id,
                         client_request_id, request_payload_hash, rating, submitted_at)
                    VALUES
                        ('00000000-0000-0000-0000-000000000901', '%s', '%s', '%s', '%s',
                         '00000000-0000-0000-0000-000000000911', repeat('f', 64), 6,
                         TIMESTAMPTZ '2026-08-11 11:10:00+03')
                    """.formatted(
                    JOB_ID,
                    PUBLISHER_ACTOR_ID,
                    APPLICANT_ONE_ACTOR_ID,
                    OWNER_ID
            )));

            assertSqlState("23514", () -> statement.execute("""
                    INSERT INTO tbl_collab_report
                        (id, collab_id, reporter_user_id, client_request_id,
                         request_payload_hash, reason, details, reported_at)
                    VALUES
                        ('00000000-0000-0000-0000-000000000921', '%s', '%s',
                         '00000000-0000-0000-0000-000000000922', repeat('1', 64),
                         'OTHER', NULL, TIMESTAMPTZ '2026-08-11 11:20:00+03')
                    """.formatted(LISTING_ID, APPLICANT_TWO_ID)));

            statement.execute("""
                    INSERT INTO tbl_collab_saved_listing (id, user_id, collab_id)
                    VALUES ('00000000-0000-0000-0000-000000000931', '%s', '%s')
                    """.formatted(APPLICANT_TWO_ID, LISTING_ID));
            assertSqlState("23505", () -> statement.execute("""
                    INSERT INTO tbl_collab_saved_listing (id, user_id, collab_id)
                    VALUES ('00000000-0000-0000-0000-000000000932', '%s', '%s')
                    """.formatted(APPLICANT_TWO_ID, LISTING_ID)));
        }
    }

    @Test
    void concurrentSavedListingPutUpsertIsIdempotent() throws Exception {
        executeMigration(migrationSql());

        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            seedCanonicalActorsAndListing(statement);
        }

        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Integer> first = executor.submit(() -> upsertSavedListing(
                    start, "00000000-0000-0000-0000-000000000931"));
            Future<Integer> second = executor.submit(() -> upsertSavedListing(
                    start, "00000000-0000-0000-0000-000000000932"));
            start.countDown();

            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(0, 1);
        }

        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            assertThat(singleInt(statement, """
                    SELECT count(*) FROM tbl_collab_saved_listing
                     WHERE user_id = '%s' AND collab_id = '%s'
                    """.formatted(APPLICANT_TWO_ID, LISTING_ID))).isOne();
        }
    }

    private static void seedCanonicalActorsAndListing(Statement statement) throws SQLException {
        statement.execute("""
                INSERT INTO tbl_collab_actor
                    (id, profile_type, source_profile_id, display_name)
                VALUES
                    ('%s', 'MUSICIAN', '00000000-0000-0000-0000-000000001501', 'Publisher'),
                    ('%s', 'BAND', '00000000-0000-0000-0000-000000001502', 'Applicant Band'),
                    ('%s', 'STUDIO', '00000000-0000-0000-0000-000000001503', 'Applicant Studio')
                """.formatted(
                PUBLISHER_ACTOR_ID,
                APPLICANT_ONE_ACTOR_ID,
                APPLICANT_TWO_ACTOR_ID
        ));

        statement.execute(validListingValues(
                LISTING_ID,
                "'MUSICIAN'",
                "'%s'".formatted(INSTRUMENT_ID),
                "NULL",
                "NULL",
                "'REGULAR'",
                "NULL",
                "NULL",
                "NULL",
                "NULL",
                "'OPEN'",
                "NULL",
                "TIMESTAMPTZ '2026-08-11 10:00:00+03'",
                "NULL",
                "repeat('a', 64)"
        ));
    }

    private static int upsertSavedListing(CountDownLatch start, String id) throws Exception {
        start.await();
        try (Connection connection = connection(); var statement = connection.prepareStatement("""
                INSERT INTO tbl_collab_saved_listing (id, user_id, collab_id)
                VALUES (?::uuid, ?::uuid, ?::uuid)
                ON CONFLICT (user_id, collab_id) DO NOTHING
                """)) {
            statement.setString(1, id);
            statement.setString(2, APPLICANT_TWO_ID);
            statement.setString(3, LISTING_ID);
            return statement.executeUpdate();
        }
    }

    private static String validListingValues(
            String id,
            String wantedType,
            String instrumentId,
            String branch,
            String customSpecialty,
            String cadence,
            String scheduledAt,
            String expiresAt,
            String feeAmountMinor,
            String currency,
            String status,
            String closureReason,
            String publishedAt,
            String closedAt,
            String payloadHash
    ) {
        return """
                INSERT INTO tbl_collab
                    (id, owner_user_id, publisher_actor_id, client_request_id,
                     creation_payload_hash, cadence, wanted_type, instrument_id, branch,
                     custom_specialty, title, description, city_id, scheduled_at, expires_at,
                     fee_amount_minor, currency, status, closure_reason, published_at, closed_at)
                VALUES
                    ('%s', '%s', '%s', gen_random_uuid(), %s, %s, %s, %s, %s, %s,
                     'Bas gitarist ariyoruz', 'Sahne programimiz icin deneyimli bir ekip arkadasi ariyoruz.',
                     '%s', %s, %s, %s, %s, %s, %s, %s, %s)
                """.formatted(
                id,
                OWNER_ID,
                PUBLISHER_ACTOR_ID,
                payloadHash,
                cadence,
                wantedType,
                instrumentId,
                branch,
                customSpecialty,
                CITY_ID,
                scheduledAt,
                expiresAt,
                feeAmountMinor,
                currency,
                status,
                closureReason,
                publishedAt,
                closedAt
        );
    }

    private static String applicationInsert(
            String id,
            String actorId,
            String userId,
            String clientRequestId,
            String phone,
            String status,
            String decidedAt
    ) {
        return """
                INSERT INTO tbl_collab_application
                    (id, collab_id, applicant_actor_id, applicant_user_id, client_request_id,
                     request_payload_hash, phone_snapshot, message, status, submitted_at,
                     status_changed_at, decided_at)
                VALUES
                    ('%s', '%s', '%s', '%s', '%s', repeat('2', 64), %s,
                     'Bu ilan icin musaitim.', %s,
                     TIMESTAMPTZ '2026-08-11 10:05:00+03',
                     TIMESTAMPTZ '2026-08-11 10:05:00+03', %s)
                """.formatted(
                id,
                LISTING_ID,
                actorId,
                userId,
                clientRequestId,
                phone,
                status,
                decidedAt
        );
    }

    private static void assertEntityColumns(Connection connection, Class<?>... entityTypes)
            throws SQLException {
        for (Class<?> entityType : entityTypes) {
            Table table = entityType.getAnnotation(Table.class);
            assertThat(table)
                    .as("@Table on %s", entityType.getName())
                    .isNotNull();
            assertThat(columns(connection, table.name()))
                    .as("database columns for %s", table.name())
                    .containsExactlyElementsOf(mappedColumns(entityType));
        }
    }

    private static Set<String> columns(Connection connection, String tableName) throws SQLException {
        Set<String> resultColumns = new TreeSet<>();
        try (var statement = connection.prepareStatement("""
                SELECT column_name
                  FROM information_schema.columns
                 WHERE table_schema = current_schema()
                   AND table_name = ?
                 ORDER BY column_name
                """)) {
            statement.setString(1, tableName);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    resultColumns.add(result.getString(1));
                }
            }
        }
        return resultColumns;
    }

    private static Set<String> mappedColumns(Class<?> entityType) {
        Set<String> mapped = new TreeSet<>();
        for (Class<?> type = entityType; type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())
                        || field.isAnnotationPresent(Transient.class)
                        || field.isAnnotationPresent(OneToMany.class)
                        || field.isAnnotationPresent(ManyToMany.class)
                        || field.isAnnotationPresent(ElementCollection.class)) {
                    continue;
                }

                JoinColumn joinColumn = field.getAnnotation(JoinColumn.class);
                if (joinColumn != null) {
                    mapped.add(joinColumn.name());
                    continue;
                }

                Column column = field.getAnnotation(Column.class);
                String configuredName = column == null ? "" : column.name();
                mapped.add(configuredName.isBlank() ? snakeCase(field.getName()) : configuredName);
            }
        }
        return mapped;
    }

    private static String snakeCase(String value) {
        return value.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }

    private static String referencedTable(Statement statement, String table, String constraint)
            throws SQLException {
        return singleString(statement, """
                SELECT target.relname
                  FROM pg_constraint c
                  JOIN pg_class source ON source.oid = c.conrelid
                  JOIN pg_class target ON target.oid = c.confrelid
                 WHERE source.relname = '%s'
                   AND c.conname = '%s'
                   AND c.contype = 'f'
                """.formatted(table, constraint));
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword()
        );
    }

    private static String migrationSql() throws Exception {
        return Files.readString(Path.of(
                System.getProperty("user.dir"),
                "scripts", "db", "2026-08-11-collab-domain.sql"
        ));
    }

    private static void executeMigration(String sql) throws SQLException {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static int singleInt(Statement statement, String sql) throws SQLException {
        try (ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getInt(1);
        }
    }

    private static String singleString(Statement statement, String sql) throws SQLException {
        try (ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }

    private static void assertSqlState(String expected, SqlAction action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(SQLException.class)
                .satisfies(error -> assertThat(((SQLException) error).getSQLState()).isEqualTo(expected));
    }

    @FunctionalInterface
    private interface SqlAction {
        void run() throws SQLException;
    }
}

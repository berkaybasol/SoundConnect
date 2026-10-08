package com.berkayb.soundconnect.modules.event.management;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

/** Disposable PostgreSQL only: validates actual SQL, ordering and the online index migration. */
@Testcontainers
class EventManagementRepositoryPostgresTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine")
            .withDatabaseName("event_history_test").withUsername("history_test").withPassword("history_test").withReuse(false);
    private JdbcTemplate jdbc;
    private EventManagementRepository repository;
    private final UUID venue = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final UUID otherVenue = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private final LocalDate date = LocalDate.of(2026, 9, 21);
    private final LocalDateTime asOf = date.atTime(12, 0);

    @BeforeEach void setUp() {
        assertThat(POSTGRES.isRunning()).isTrue();
        var dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        repository = new EventManagementRepository(new NamedParameterJdbcTemplate(dataSource), new EventManagementTimeStorage(0));
        jdbc.execute("drop table if exists tbl_event");
        jdbc.execute("create table tbl_event (id uuid primary key, venue_id uuid not null, event_origin varchar(20), "
                + "event_date date not null, start_time time, end_time time)");
    }

    @Test void cutoffMatchesDartMinutePrecisionFallbacksAndOngoingEvents() {
        UUID ended = add(venue, "VENUE", date, "10:00", "11:59");
        UUID ongoing = add(venue, "VENUE", date, "11:00", "13:00");
        UUID equalBoundary = add(venue, "VENUE", date, "11:00", "12:00");
        UUID noEndPast = add(venue, "VENUE", date, "10:59", null);
        UUID noEndOngoing = add(venue, "VENUE", date, "11:01", null);
        UUID invalidEndPast = add(venue, "VENUE", date, "10:30", "09:00");
        UUID invalidEndOngoing = add(venue, "VENUE", date, "11:30", "09:00");
        UUID minutePrecision = add(venue, "VENUE", date, "11:00:59", "11:59:59");
        UUID noStartToday = add(venue, "VENUE", date, null, null);
        UUID noStartYesterday = add(venue, "VENUE", date.minusDays(1), null, null);
        add(otherVenue, "VENUE", date.minusDays(1), "10:00", "11:00");
        add(venue, "MUSICIAN", date.minusDays(1), "10:00", "11:00");

        assertThat(repository.upcomingIds(venue, asOf)).containsExactlyInAnyOrder(ongoing, equalBoundary,
                noEndOngoing, invalidEndOngoing, noStartToday);
        assertThat(repository.pastCount(venue, asOf)).isEqualTo(5);
        assertThat(repository.pastPositions(venue, asOf, null, 20)).extracting(EventHistoryCursor::id)
                .containsExactlyInAnyOrder(ended, noEndPast, invalidEndPast, minutePrecision, noStartYesterday);
    }

    @Test void endEqualityStaysUpcomingAndMidnightFallbackSpillsIntoNextDay() {
        UUID event = add(venue, "VENUE", date.minusDays(1), "23:30", null);
        LocalDateTime midnightBoundary = date.atTime(0, 30);
        assertThat(repository.upcomingIds(venue, midnightBoundary)).containsExactly(event);
        assertThat(repository.pastCount(venue, midnightBoundary)).isZero();
        assertThat(repository.pastCount(venue, midnightBoundary.plusNanos(1000))).isEqualTo(1);
        // A subsequent request carries the original boundary even after the event has ended.
        assertThat(repository.pastPositions(venue, midnightBoundary, null, 21)).isEmpty();
    }

    @Test void keysetTraversesTiesWithoutSkippingAfterEarlierRowsAndBoundaryAreDeleted() {
        for (int i = 0; i < 47; i++) add(venue, "VENUE", date.minusDays(1), "10:00", "11:00");
        add(otherVenue, "VENUE", date.minusDays(1), "10:00", "11:00");
        var original = repository.pastPositions(venue, asOf, null, 100);
        var first = repository.pastPositions(venue, asOf, null, 21);
        assertThat(first).hasSize(21).containsExactlyElementsOf(original.subList(0, 21));
        EventHistoryCursor boundary = first.get(19);
        for (var row : original.subList(0, 20)) jdbc.update("delete from tbl_event where id = ?", row.id());
        add(venue, "VENUE", date, "09:00", "10:00");
        var second = repository.pastPositions(venue, asOf, boundary, 21);
        assertThat(second).containsExactlyElementsOf(original.subList(20, 41));
        assertThat(repository.pastPositions(venue, asOf, second.get(19), 21))
                .containsExactlyElementsOf(original.subList(40, 47));
    }

    @Test void mixedDatesAndTimesUseDescendingStableTimelineOrderAndBoundTheResult() {
        UUID newest = add(venue, "VENUE", date, "10:30", "11:00");
        UUID earlierToday = add(venue, "VENUE", date, "08:00", "09:00");
        UUID yesterday = add(venue, "VENUE", date.minusDays(1), "23:00", "23:30");
        for (int i = 0; i < 200; i++) add(venue, "VENUE", date.minusDays(2 + i), "10:00", "11:00");
        assertThat(repository.pastPositions(venue, asOf, null, 21)).hasSize(21)
                .extracting(EventHistoryCursor::id).startsWith(newest, earlierToday, yesterday);
        assertThat(repository.pastCount(venue, asOf)).isEqualTo(203);
    }

    @Test void onlineMigrationIsRepeatableAndIndexesSupportBothCutoffAndKeysetQueries() throws Exception {
        String migration = Files.readString(Path.of("scripts/db/2026-09-21-event-management-history.sql"));
        for (int run = 0; run < 2; run++) {
            // Each CONCURRENTLY statement gets its own autocommit connection.
            for (String sql : migration.replaceAll("(?m)--.*$", "").split(";")) if (!sql.isBlank()) jdbc.execute(sql);
        }
        assertThat(jdbc.queryForList("select indexrelid::regclass::text from pg_index "
                + "where indrelid = 'tbl_event'::regclass and indisvalid", String.class))
                .contains("idx_event_owner_history_date");
        try (var connection = jdbc.getDataSource().getConnection(); var statement = connection.createStatement()) {
            statement.execute("set enable_seqscan = off");
            try (var result = statement.executeQuery("explain select count(*) from tbl_event where venue_id = '" + venue
                    + "' and event_origin = 'VENUE' and event_date < date '2026-09-20'")) {
                List<String> plan = new ArrayList<>(); while (result.next()) plan.add(result.getString(1));
                assertThat(String.join("\n", plan)).contains("idx_event_owner_history_date");
            }
            try (var result = statement.executeQuery("explain select id from tbl_event where venue_id = '" + venue
                    + "' and event_origin = 'VENUE' order by event_date desc, start_time desc, id desc limit 21")) {
                List<String> plan = new ArrayList<>(); while (result.next()) plan.add(result.getString(1));
                assertThat(String.join("\n", plan)).contains("idx_event_owner_history_date").doesNotContain("Sort");
            }
        }
    }

    @Test void actualNormalizedCursorQuerySeeksDateIndexAndLimitsBeforeMapping() throws Exception {
        String migration = Files.readString(Path.of("scripts/db/2026-09-21-event-management-history.sql"));
        for (String sql : migration.replaceAll("(?m)--.*$", "").split(";")) if (!sql.isBlank()) jdbc.execute(sql);
        for (int i = 1; i <= 100; i++) add(venue, "VENUE", date.minusDays(i), "18:00", "20:00");
        EventHistoryCursor cursor = new EventHistoryCursor(date.minusDays(50), LocalTime.of(20, 0), new UUID(-1, -1));
        try (var connection = jdbc.getDataSource().getConnection()) {
            var source = new SingleConnectionDataSource(connection, true);
            new JdbcTemplate(source).execute("set enable_seqscan = off");
            var recording = new RecordingJdbc(source);
            var productionQuery = new EventManagementRepository(recording, new EventManagementTimeStorage(7200));
            var positions = productionQuery.pastPositions(venue, asOf, cursor, 21);
            assertThat(positions).hasSize(21).allSatisfy(position -> assertThat(position.date()).isBeforeOrEqualTo(cursor.date()));
            var plan = recording.queryForList("explain (analyze, buffers) " + recording.lastSql, recording.lastParameters, String.class);
            String planText = String.join("\n", plan);
            assertThat(planText).contains("idx_event_owner_history_date", "Index Cond", "event_date <= '2026-08-02'", "Limit");
            // Normalized time can wrap at midnight, so date-prefix index + (incremental) sort is intentional.
            assertThat(recording.lastSql).contains("start_time + cast(:timeOffset as interval)", "event_date <= :date", "limit :limit");
        }
    }

    private static class RecordingJdbc extends NamedParameterJdbcTemplate {
        String lastSql;
        SqlParameterSource lastParameters;
        RecordingJdbc(javax.sql.DataSource source) { super(source); }
        @Override public <T> List<T> query(String sql, SqlParameterSource parameters, RowMapper<T> mapper) {
            lastSql = sql; lastParameters = parameters; return super.query(sql, parameters, mapper);
        }
    }

    private UUID add(UUID venueId, String origin, LocalDate day, String start, String end) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into tbl_event(id, venue_id, event_origin, event_date, start_time, end_time) values (?, ?, ?, ?, ?, ?)",
                id, venueId, origin, day, start == null ? null : LocalTime.parse(start), end == null ? null : LocalTime.parse(end));
        return id;
    }
}

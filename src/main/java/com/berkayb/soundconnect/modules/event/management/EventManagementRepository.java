package com.berkayb.soundconnect.modules.event.management;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** SQL selects only history positions, with a database limit before any card/media mapping. */
@Repository
@RequiredArgsConstructor
public class EventManagementRepository {
    private static final String START = "(start_time + cast(:timeOffset as interval))";
    private static final String END = "(end_time + cast(:timeOffset as interval))";
    // Minute precision and fallback match venueEventTimelineEnd after Hibernate's LocalTime conversion.
    static final String TIMELINE_END = "(case when start_time is null then event_date + time '23:59:59' "
            + "when end_time is null or date_trunc('minute', event_date + " + END + ") < date_trunc('minute', event_date + " + START + ") "
            + "then date_trunc('minute', event_date + " + START + ") + interval '1 hour' "
            + "else date_trunc('minute', event_date + " + END + ") end)";
    private static final String ORDER_START = "coalesce(" + START + ", time '00:00:00')";
    private static final String PAST = " and event_date <= :today and (event_date < :yesterday or " + TIMELINE_END + " < :asOf) ";
    private static final String SCOPE = " from tbl_event where venue_id = :venue and event_origin = 'VENUE' ";
    private final NamedParameterJdbcTemplate jdbc;
    private final EventManagementTimeStorage timeStorage;

    public List<UUID> upcomingIds(UUID venueId, LocalDateTime asOf) {
        return jdbc.query("select id" + SCOPE + " and event_date >= :yesterday and " + TIMELINE_END
                        + " >= :asOf order by event_date, " + ORDER_START + ", id",
                parameters(venueId, asOf), (row, index) -> row.getObject("id", UUID.class));
    }

    public long pastCount(UUID venueId, LocalDateTime asOf) {
        // Older days need no per-row clock expression; an index-only count covers the large historical range.
        Long count = jdbc.queryForObject("select (select count(*)" + SCOPE + " and event_date < :yesterday) + "
                        + "(select count(*)" + SCOPE + " and event_date between :yesterday and :today and " + TIMELINE_END + " < :asOf)",
                parameters(venueId, asOf), Long.class);
        return count == null ? 0 : count;
    }

    List<EventHistoryCursor> pastPositions(UUID venueId, LocalDateTime asOf, EventHistoryCursor cursor, int limit) {
        var parameters = parameters(venueId, asOf).addValue("limit", limit);
        String position = "";
        if (cursor != null) {
            position = " and event_date <= :date and (event_date, " + ORDER_START + ", id) < (:date, :start, :id) ";
            parameters.addValue("date", cursor.date()).addValue("start", cursor.startTime()).addValue("id", cursor.id());
        }
        return jdbc.query("select id, event_date, " + ORDER_START + " as start_time" + SCOPE + PAST + position
                        + " order by event_date desc, " + ORDER_START + " desc, id desc limit :limit",
                parameters, (row, index) -> new EventHistoryCursor(row.getDate("event_date").toLocalDate(),
                        row.getObject("start_time", java.time.LocalTime.class), row.getObject("id", UUID.class)));
    }

    private MapSqlParameterSource parameters(UUID venueId, LocalDateTime asOf) {
        return new MapSqlParameterSource().addValue("venue", venueId).addValue("asOf", asOf)
                .addValue("today", asOf.toLocalDate()).addValue("yesterday", asOf.toLocalDate().minusDays(1))
                .addValue("timeOffset", timeStorage.sqlInterval());
    }
}

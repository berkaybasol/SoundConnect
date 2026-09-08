package com.berkayb.soundconnect.modules.analytics;

import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.MessageDigest;
import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

@Repository
public class AnalyticsStore {
    static final ZoneId ZONE = ZoneId.of("Europe/Istanbul");
    private final NamedParameterJdbcTemplate jdbc;
    private final AnalyticsIdentity identity;
    private final TransactionTemplate write;
    private final TransactionTemplate read;

    public AnalyticsStore(NamedParameterJdbcTemplate jdbc, PlatformTransactionManager manager, AnalyticsIdentity identity) {
        this.jdbc = jdbc; this.identity = identity;
        write = new TransactionTemplate(manager);
        write.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        write.setTimeout(5);
        read = new TransactionTemplate(manager);
        read.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        read.setReadOnly(true); read.setTimeout(5);
    }

    public void observe(UUID userId, AnalyticsRequest request, Instant now) {
        write.executeWithoutResult(transaction -> {
            String actor = identity.actor(userId, request.clientId());
            byte[] actorHash = identity.receiptActor(actor);
            // One actor can send overlapping batches from multiple devices. Serialize only
            // that actor's writes so presence/proof locks keep a consistent ordering.
            jdbc.queryForList("SELECT pg_advisory_xact_lock(:actorLock)", Map.of("actorLock", ByteBuffer.wrap(actorHash).getLong()));
            var fresh = new ArrayList<AnalyticsRequest.Observation>();
            // Global deterministic receipt-lock order prevents overlapping batches from deadlocking.
            for (var observation : request.observations().stream().sorted(Comparator.comparing(o -> o.id().toString())).toList()) {
                byte[] payloadHash = identity.payload(request.clientId(), observation);
                var params = Map.<String, Object>of("id", observation.id(), "actor", actorHash, "payload", payloadHash,
                        "expiry", Timestamp.from(now.plus(Duration.ofHours(72))));
                var inserted = jdbc.queryForList("""
                        INSERT INTO tbl_venue_analytics_receipt(observation_id,actor_hash,payload_hash,expires_at)
                        VALUES (:id,:actor,:payload,:expiry) ON CONFLICT (observation_id) DO NOTHING RETURNING observation_id
                        """, params);
                if (inserted.isEmpty()) {
                    var saved = jdbc.queryForMap("SELECT actor_hash,payload_hash FROM tbl_venue_analytics_receipt WHERE observation_id=:id", params);
                    if (!MessageDigest.isEqual(actorHash, (byte[]) saved.get("actor_hash"))
                            || !MessageDigest.isEqual(payloadHash, (byte[]) saved.get("payload_hash"))) {
                        throw new SoundConnectException(ErrorType.ANALYTICS_CONFLICT);
                    }
                    continue;
                }
                if (observation.observedAt().isBefore(now.minus(Duration.ofHours(24)))
                        || observation.observedAt().isAfter(now.plus(Duration.ofMinutes(2)))) throw invalid();
                fresh.add(observation);
            }
            if (fresh.isEmpty()) return;
            if (userId != null && !eligibleActor(userId)) return;
            var eventIds = new LinkedHashSet<UUID>();
            var venueIds = new LinkedHashSet<UUID>();
            for (var observation : fresh) {
                if (observation.eventId() != null) eventIds.add(observation.eventId());
                if (observation.sourceEventId() != null) eventIds.add(observation.sourceEventId());
                if (observation.venueId() != null) venueIds.add(observation.venueId());
            }
            var events = publicEvents(eventIds);
            var venues = publicVenues(venueIds);
            fresh.sort(Comparator.comparing(AnalyticsRequest.Observation::observedAt)
                    .thenComparingInt(o -> o.type() == AnalyticsRequest.Type.EVENT_DETAIL_VIEW ? 0 : 1)
                    .thenComparing(o -> o.id().toString()));
            for (var observation : fresh) {
                VenueContext venue;
                if (observation.type() == AnalyticsRequest.Type.VENUE_PROFILE_VIEW) venue = venues.get(observation.venueId());
                else venue = events.get(observation.eventId());
                if (venue == null || venue.ownerId().equals(userId)) continue;
                byte[] viewer = identity.viewer(venue.venueId(), actor);
                UUID eventId = observation.eventId() == null ? AnalyticsIdentity.NONE : observation.eventId();
                UUID source = AnalyticsIdentity.NONE;
                if (observation.type() == AnalyticsRequest.Type.VENUE_PROFILE_VIEW && observation.sourceEventId() != null) {
                    VenueContext sourceVenue = events.get(observation.sourceEventId());
                    if (sourceVenue != null && sourceVenue.venueId().equals(venue.venueId())
                            && hasRecentDetail(venue.venueId(), observation.sourceEventId(), viewer, observation.observedAt(), now)) {
                        source = observation.sourceEventId();
                    }
                }
                var params = Map.<String, Object>of("venue", venue.venueId(), "day", observation.observedAt().atZone(ZONE).toLocalDate(),
                        "type", observation.type().name(), "event", eventId, "source", source, "viewer", viewer);
                jdbc.update("""
                        INSERT INTO tbl_venue_analytics_presence(venue_id,metric_day,metric_type,event_id,source_event_id,viewer_key)
                        VALUES (:venue,:day,:type,:event,:source,:viewer) ON CONFLICT DO NOTHING
                        """, params);
                if (observation.type() == AnalyticsRequest.Type.EVENT_DETAIL_VIEW) {
                    jdbc.update("""
                            INSERT INTO tbl_venue_analytics_recent_detail(venue_id,event_id,viewer_key,valid_windows,expires_at)
                            VALUES (:venue,:event,:viewer,tstzmultirange(tstzrange(:observed,:validUntil,'[]')),:expiry)
                            ON CONFLICT (venue_id,event_id,viewer_key) DO UPDATE SET
                                valid_windows=(tbl_venue_analytics_recent_detail.valid_windows + EXCLUDED.valid_windows)
                                    * tstzmultirange(tstzrange(:earliest,:latest,'[]')),
                                expires_at=GREATEST(tbl_venue_analytics_recent_detail.expires_at,EXCLUDED.expires_at)
                            """, Map.of("venue", venue.venueId(), "event", eventId, "viewer", viewer,
                            "observed", Timestamp.from(observation.observedAt()),
                            "validUntil",Timestamp.from(observation.observedAt().plus(Duration.ofMinutes(30))),
                            "earliest",Timestamp.from(now.minus(Duration.ofHours(24))),
                            "latest",Timestamp.from(now.plus(Duration.ofMinutes(32))),
                            "expiry", Timestamp.from(now.plus(Duration.ofHours(26)))));
                }
            }
            jdbc.update("UPDATE tbl_venue_analytics_state SET tracking_started_at=:now WHERE singleton=true AND tracking_started_at IS NULL",
                    Map.of("now", Timestamp.from(now)));
        });
    }

    private Map<UUID, VenueContext> publicEvents(Set<UUID> ids) {
        if (ids.isEmpty()) return Map.of();
        var rows = jdbc.queryForList("""
                SELECT event.id,venue.id AS venue_id,venue.owner_id FROM tbl_event event
                JOIN tbl_venues venue ON venue.id=event.venue_id JOIN tbl_user owner ON owner.id=venue.owner_id
                WHERE event.id IN (:ids) AND event.event_origin='VENUE' AND event.venue_calendar_approved
                    AND venue.status='APPROVED' AND owner.status='ACTIVE' AND owner.email_verified
                """, Map.of("ids", ids));
        var result = new HashMap<UUID, VenueContext>();
        for (var row : rows) result.put((UUID) row.get("id"), new VenueContext((UUID) row.get("venue_id"), (UUID) row.get("owner_id")));
        return result;
    }

    private boolean eligibleActor(UUID userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM tbl_user actor WHERE actor.id=:id AND actor.status='ACTIVE' AND actor.email_verified
                    AND NOT EXISTS(SELECT 1 FROM user_roles membership JOIN tbl_role role ON role.id=membership.role_id
                        WHERE membership.user_id=actor.id AND role.name='ROLE_ADMIN'))
                """, Map.of("id", userId), Boolean.class));
    }

    private Map<UUID, VenueContext> publicVenues(Set<UUID> ids) {
        if (ids.isEmpty()) return Map.of();
        var rows = jdbc.queryForList("""
                SELECT venue.id,venue.owner_id FROM tbl_venues venue JOIN tbl_user owner ON owner.id=venue.owner_id
                WHERE venue.id IN (:ids) AND venue.status='APPROVED' AND owner.status='ACTIVE' AND owner.email_verified
                """, Map.of("ids", ids));
        var result = new HashMap<UUID, VenueContext>();
        for (var row : rows) result.put((UUID) row.get("id"), new VenueContext((UUID) row.get("id"), (UUID) row.get("owner_id")));
        return result;
    }

    private boolean hasRecentDetail(UUID venue, UUID event, byte[] viewer, Instant observed, Instant now) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM tbl_venue_analytics_recent_detail WHERE venue_id=:venue AND event_id=:event
                    AND viewer_key=:viewer AND expires_at>:now AND valid_windows @> CAST(:observed AS timestamptz))
                """, Map.of("venue", venue, "event", event, "viewer", viewer, "now", Timestamp.from(now),
                "observed", Timestamp.from(observed)), Boolean.class));
    }

    public AnalyticsResponse.Summary summary(UUID owner, UUID venueId, UUID eventId, int days, Instant now) {
        return read.execute(transaction -> {
            requireOwner(owner, venueId);
            if (eventId != null) requireCurrentEvent(venueId, eventId);
            LocalDate to = now.atZone(ZONE).toLocalDate(), from = to.minusDays(days - 1L);
            var params = new HashMap<String, Object>(Map.of("venue", venueId, "from", from, "to", to));
            String eventFilter = "";
            if (eventId != null) {
                params.put("event", eventId);
                eventFilter = " AND (event_id=:event OR (metric_type='VENUE_PROFILE_VIEW' AND source_event_id=:event))";
            }
            // One bounded scan produces independent period and daily distinct counts.
            // A viewer seen on multiple days remains one period visitor.
            var rows = jdbc.queryForList("""
                    SELECT metric_day, count(DISTINCT viewer_key) FILTER (WHERE metric_type='EVENT_IMPRESSION') AS impressions,
                        count(DISTINCT viewer_key) FILTER (WHERE metric_type='EVENT_DETAIL_VIEW') AS detail_views,
                        count(DISTINCT viewer_key) FILTER (WHERE metric_type='VENUE_PROFILE_VIEW') AS profile_visits
                    FROM tbl_venue_analytics_presence WHERE venue_id=:venue AND metric_day BETWEEN :from AND :to
                    """ + eventFilter + " GROUP BY GROUPING SETS ((metric_day), ())", params);
            AnalyticsResponse.Metrics total = AnalyticsResponse.Metrics.ZERO;
            var byDay = new HashMap<LocalDate, AnalyticsResponse.Metrics>();
            for (var row : rows) {
                if (row.get("metric_day") == null) total = metrics(row);
                else byDay.put(((java.sql.Date) row.get("metric_day")).toLocalDate(), metrics(row));
            }
            Instant started = trackingStartedAt();
            LocalDate startDay = started == null ? null : started.atZone(ZONE).toLocalDate();
            var daily = from.datesUntil(to.plusDays(1)).map(day -> new AnalyticsResponse.DailyPoint(day,
                    startDay == null ? null : byDay.getOrDefault(day, day.isBefore(startDay) ? null : AnalyticsResponse.Metrics.ZERO),
                    day.equals(to) || (startDay != null && ((day.isBefore(startDay) && byDay.containsKey(day))
                            || (day.equals(startDay) && started.isAfter(day.atStartOfDay(ZONE).toInstant())))))).toList();
            var comparison = comparison(days, to, started, eventFilter, params);
            return new AnalyticsResponse.Summary(venueId, eventId, from, to, days, ZONE.getId(), started, now, total, daily, comparison);
        });
    }

    private AnalyticsResponse.PeriodComparison comparison(int days, LocalDate today, Instant started,
                                                          String eventFilter, Map<String, Object> params) {
        LocalDate currentTo = today.minusDays(1), currentFrom = today.minusDays(days);
        LocalDate previousTo = currentFrom.minusDays(1), previousFrom = currentFrom.minusDays(days);
        AnalyticsResponse.ComparisonStatus status;
        if (started == null) status = AnalyticsResponse.ComparisonStatus.NOT_STARTED;
        else if (previousFrom.isBefore(today.minusDays(89))) status = AnalyticsResponse.ComparisonStatus.RETENTION_LIMIT;
        else if (started.isAfter(previousFrom.atStartOfDay(ZONE).toInstant())) status = AnalyticsResponse.ComparisonStatus.INSUFFICIENT_HISTORY;
        else status = AnalyticsResponse.ComparisonStatus.AVAILABLE;
        AnalyticsResponse.Metrics current = null, previous = null;
        if (status == AnalyticsResponse.ComparisonStatus.AVAILABLE) {
            params.put("comparisonFrom", previousFrom); params.put("comparisonTo", currentTo); params.put("currentFrom", currentFrom);
            var rows = jdbc.queryForList("""
                    SELECT metric_day >= :currentFrom AS current_period,
                        count(DISTINCT viewer_key) FILTER (WHERE metric_type='EVENT_IMPRESSION') AS impressions,
                        count(DISTINCT viewer_key) FILTER (WHERE metric_type='EVENT_DETAIL_VIEW') AS detail_views,
                        count(DISTINCT viewer_key) FILTER (WHERE metric_type='VENUE_PROFILE_VIEW') AS profile_visits
                    FROM tbl_venue_analytics_presence
                    WHERE venue_id=:venue AND metric_day BETWEEN :comparisonFrom AND :comparisonTo
                    """ + eventFilter + " GROUP BY 1", params);
            current = AnalyticsResponse.Metrics.ZERO; previous = AnalyticsResponse.Metrics.ZERO;
            for (var row : rows) {
                if (Boolean.TRUE.equals(row.get("current_period"))) current = metrics(row);
                else previous = metrics(row);
            }
        }
        return new AnalyticsResponse.PeriodComparison(status, currentFrom, currentTo, previousFrom, previousTo, current, previous);
    }

    public AnalyticsResponse.EventPage events(UUID owner, UUID venueId, int days, int page, int size, Instant now) {
        return events(owner, venueId, days, page, size, AnalyticsResponse.EventSort.DATE, now);
    }

    public AnalyticsResponse.EventPage events(UUID owner, UUID venueId, int days, int page, int size,
                                               AnalyticsResponse.EventSort sort, Instant now) {
        return read.execute(transaction -> {
            requireOwner(owner, venueId);
            var params = new HashMap<String, Object>(Map.of("venue", venueId, "limit", size, "offset", (long) page * size,
                    "from", now.atZone(ZONE).toLocalDate().minusDays(days - 1L), "to", now.atZone(ZONE).toLocalDate()));
            var rows = sort == AnalyticsResponse.EventSort.DATE ? jdbc.queryForList("""
                    SELECT id,title,event_date FROM tbl_event
                    WHERE venue_id=:venue AND event_origin='VENUE' AND venue_calendar_approved
                    ORDER BY event_date DESC,start_time DESC,id LIMIT :limit OFFSET :offset
                    """, params) : metricSortedEvents(params, sort);
            long total = Objects.requireNonNull(jdbc.queryForObject("""
                    SELECT count(*) FROM tbl_event WHERE venue_id=:venue AND event_origin='VENUE' AND venue_calendar_approved
                    """, params, Long.class));
            var counts = new HashMap<UUID, AnalyticsResponse.Metrics>();
            if (sort != AnalyticsResponse.EventSort.DATE) {
                for (var row : rows) counts.put((UUID) row.get("id"), metrics(row));
            } else if (!rows.isEmpty()) {
                params.put("ids", rows.stream().map(row -> (UUID) row.get("id")).toList());
                var metrics = jdbc.queryForList("""
                        SELECT event_id,
                            count(DISTINCT viewer_key) FILTER (WHERE metric_type='EVENT_IMPRESSION') AS impressions,
                            count(DISTINCT viewer_key) FILTER (WHERE metric_type='EVENT_DETAIL_VIEW') AS detail_views,
                            count(DISTINCT viewer_key) FILTER (WHERE metric_type='VENUE_PROFILE_VIEW') AS profile_visits
                        FROM (
                            SELECT event_id,metric_type,viewer_key FROM tbl_venue_analytics_presence
                            WHERE venue_id=:venue AND metric_day BETWEEN :from AND :to AND event_id IN (:ids)
                            UNION ALL
                            SELECT source_event_id AS event_id,metric_type,viewer_key FROM tbl_venue_analytics_presence
                            WHERE venue_id=:venue AND metric_day BETWEEN :from AND :to
                                AND metric_type='VENUE_PROFILE_VIEW' AND source_event_id IN (:ids)
                        ) counts GROUP BY event_id
                        """, params);
                for (var metric : metrics) counts.put((UUID) metric.get("event_id"), metrics(metric));
            }
            var content = rows.stream().map(row -> new AnalyticsResponse.EventItem((UUID) row.get("id"), (String) row.get("title"),
                    ((java.sql.Date) row.get("event_date")).toLocalDate(), counts.getOrDefault((UUID) row.get("id"), AnalyticsResponse.Metrics.ZERO))).toList();
            return new AnalyticsResponse.EventPage(content, page, size, total, Math.toIntExact((total + size - 1) / size),
                    (long) (page + 1) * size < total, sort);
        });
    }

    private List<Map<String, Object>> metricSortedEvents(Map<String, Object> params, AnalyticsResponse.EventSort sort) {
        // Only server-owned enum constants select SQL identifiers. PostgreSQL ranks the
        // complete eligible set before LIMIT/OFFSET; Java receives at most one page.
        String metric = switch (sort) {
            case REACH -> "impressions";
            case DETAIL_VIEWS -> "detail_views";
            case PROFILE_VISITS -> "profile_visits";
            case DATE -> throw new IllegalArgumentException("Date pages use the indexed event query");
        };
        return jdbc.queryForList("""
                WITH counts AS (
                    SELECT CASE WHEN metric_type='VENUE_PROFILE_VIEW' THEN source_event_id ELSE event_id END AS counted_event_id,
                        count(DISTINCT viewer_key) FILTER (WHERE metric_type='EVENT_IMPRESSION') AS impressions,
                        count(DISTINCT viewer_key) FILTER (WHERE metric_type='EVENT_DETAIL_VIEW') AS detail_views,
                        count(DISTINCT viewer_key) FILTER (WHERE metric_type='VENUE_PROFILE_VIEW') AS profile_visits
                    FROM tbl_venue_analytics_presence presence
                    JOIN tbl_event eligible ON eligible.id=CASE WHEN metric_type='VENUE_PROFILE_VIEW' THEN source_event_id ELSE event_id END
                        AND eligible.venue_id=:venue AND eligible.event_origin='VENUE' AND eligible.venue_calendar_approved
                    WHERE presence.venue_id=:venue AND metric_day BETWEEN :from AND :to
                    GROUP BY 1
                )
                SELECT event.id,event.title,event.event_date,
                    COALESCE(counts.impressions,0) AS impressions,
                    COALESCE(counts.detail_views,0) AS detail_views,
                    COALESCE(counts.profile_visits,0) AS profile_visits
                FROM tbl_event event LEFT JOIN counts ON counts.counted_event_id=event.id
                WHERE event.venue_id=:venue AND event.event_origin='VENUE' AND event.venue_calendar_approved
                ORDER BY """ + " " + metric + " DESC,event.event_date DESC,event.start_time DESC,event.id LIMIT :limit OFFSET :offset", params);
    }

    private void requireOwner(UUID owner, UUID venue) {
        if (owner == null || !Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM tbl_venues venue JOIN tbl_user owner ON owner.id=venue.owner_id WHERE venue.id=:venue AND owner.id=:owner AND owner.status='ACTIVE' AND owner.email_verified)",
                Map.of("venue", venue, "owner", owner), Boolean.class))) throw new SoundConnectException(ErrorType.VENUE_NOT_FOUND);
    }
    private void requireCurrentEvent(UUID venue, UUID event) {
        if (!Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM tbl_event WHERE id=:event AND venue_id=:venue AND event_origin='VENUE')
                """, Map.of("venue", venue, "event", event), Boolean.class))) throw new SoundConnectException(ErrorType.EVENT_NOT_FOUND);
    }
    private Instant trackingStartedAt() {
        Timestamp timestamp = jdbc.queryForObject("SELECT tracking_started_at FROM tbl_venue_analytics_state WHERE singleton=true", Map.of(), Timestamp.class);
        return timestamp == null ? null : timestamp.toInstant();
    }
    public int cleanup(Instant now) {
        return Objects.requireNonNull(write.execute(transaction -> {
            int largestBatch = 0;
            var params = Map.<String, Object>of("now", Timestamp.from(now), "cutoff", now.atZone(ZONE).toLocalDate().minusDays(89));
            for (String table : List.of("tbl_venue_analytics_receipt", "tbl_venue_analytics_recent_detail")) {
                largestBatch = Math.max(largestBatch, jdbc.update("DELETE FROM " + table + " WHERE ctid IN (SELECT ctid FROM " + table
                        + " WHERE expires_at<:now ORDER BY expires_at LIMIT 1000 FOR UPDATE SKIP LOCKED)", params));
            }
            return Math.max(largestBatch, jdbc.update("""
                    DELETE FROM tbl_venue_analytics_presence WHERE ctid IN (
                        SELECT ctid FROM tbl_venue_analytics_presence WHERE metric_day<:cutoff
                        ORDER BY metric_day LIMIT 1000 FOR UPDATE SKIP LOCKED)
                    """, params));
        }));
    }
    public boolean retentionHealthy(Instant now) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM tbl_venue_analytics_state WHERE singleton)
                    AND NOT EXISTS(SELECT 1 FROM tbl_venue_analytics_receipt WHERE expires_at<:overdue)
                    AND NOT EXISTS(SELECT 1 FROM tbl_venue_analytics_recent_detail WHERE expires_at<:overdue)
                    AND NOT EXISTS(SELECT 1 FROM tbl_venue_analytics_presence WHERE metric_day<:oldest)
                """, Map.of("overdue",Timestamp.from(now.minus(Duration.ofHours(1))),
                "oldest",now.atZone(ZONE).toLocalDate().minusDays(90)),Boolean.class));
    }
    public boolean schemaInstalled() {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT to_regclass('public.tbl_venue_analytics_state') IS NOT NULL
                    AND to_regclass('public.tbl_venue_analytics_receipt') IS NOT NULL
                    AND to_regclass('public.tbl_venue_analytics_presence') IS NOT NULL
                    AND to_regclass('public.tbl_venue_analytics_recent_detail') IS NOT NULL
                """,Map.of(),Boolean.class));
    }
    private static AnalyticsResponse.Metrics metrics(Map<String, Object> row) {
        return new AnalyticsResponse.Metrics(((Number) row.get("impressions")).longValue(),
                ((Number) row.get("detail_views")).longValue(), ((Number) row.get("profile_visits")).longValue());
    }
    private static SoundConnectException invalid() { return new SoundConnectException(ErrorType.ANALYTICS_INVALID); }
    private record VenueContext(UUID venueId, UUID ownerId) { }
}

package com.berkayb.soundconnect.modules.analytics;

import com.berkayb.soundconnect.modules.feed.musician.announcement.AnnouncementImpressionHistory;
import com.berkayb.soundconnect.modules.feed.musician.delivery.MusicianFeedDeliveryLookup;
import com.berkayb.soundconnect.modules.profile.shared.media.enums.ProfileType;
import com.berkayb.soundconnect.modules.promotion.announcement.AnnouncementAccess;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Promotion reporting inside the existing analytics pipeline; no second receipt or transport system. */
@Repository
public class AnnouncementAnalyticsStore implements AnnouncementImpressionHistory {
    public enum EngagementMetric { LIKE, COMMENT, HIDE }
    private static final ZoneId ZONE = ZoneId.of("Europe/Istanbul");
    private final NamedParameterJdbcTemplate jdbc;
    private final AnalyticsIdentity identity;
    private final AnalyticsProperties properties;
    private final AnnouncementAccess access;
    private final MusicianFeedDeliveryLookup deliveries;

    public AnnouncementAnalyticsStore(NamedParameterJdbcTemplate jdbc, AnalyticsIdentity identity,
                                     AnalyticsProperties properties, AnnouncementAccess access,
                                     MusicianFeedDeliveryLookup deliveries) {
        this.jdbc = jdbc; this.identity = identity; this.properties = properties; this.access = access;
        this.deliveries = deliveries;
    }

    // Called within AnalyticsStore's existing bounded write transaction, after receipt validation.
    void observe(UUID user, AnalyticsRequest.Observation observation, Instant now) {
        if (user == null || access.canManage(user)) return;
        Optional<ProfileType> profile = access.visibleProfile(user, observation.announcementId());
        if (profile.isEmpty()) return; // A queued view may outlive publication, targeting or the account.
        UUID deliveryId = observation.source() == AnalyticsRequest.Source.FEED
                ? deliveries.requireForObservation(observation.impressionToken(), user,
                    "ANNOUNCEMENT:" + observation.announcementId(), observation.observedAt(), now).deliveryId()
                : null;
        byte[] viewer = identity.viewer(observation.announcementId(), identity.actor(user, null));
        var parameters = new MapSqlParameterSource()
                .addValue("id", observation.id()).addValue("promotion", observation.announcementId())
                .addValue("actor", identity.receiptActor(identity.actor(user, null))).addValue("viewer", viewer)
                .addValue("profile", profile.get().name()).addValue("source", observation.source().name())
                .addValue("type", observation.type().name()).addValue("observed", Timestamp.from(observation.observedAt()))
                .addValue("recorded", Timestamp.from(now)).addValue("playback", observation.playbackId())
                .addValue("delivery", deliveryId).addValue("user", user);
        if (observation.source() == AnalyticsRequest.Source.FEED && !Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from tbl_musician_feed_delivery d
                    where d.id=:delivery and d.viewer_user_id=:user and d.item_type='ANNOUNCEMENT'
                      and d.target_type='ANNOUNCEMENT' and d.target_id=:promotion
                      and d.delivered_at<=cast(:observed as timestamptz) + interval '2 minutes'
                      and d.expires_at>=:observed)
                """, parameters, Boolean.class))) return;
        if (observation.type().video()) {
            if (!Boolean.TRUE.equals(jdbc.queryForObject("""
                    select exists(select 1 from tlb_promotion p join tbl_media_asset m on m.id=p.media_asset_id
                        where p.id=:promotion and m.kind='VIDEO' and m.status='READY')
                    """, parameters, Boolean.class))) return;
            if (observation.type() == AnalyticsRequest.Type.ANNOUNCEMENT_VIDEO_COMPLETE
                    && !Boolean.TRUE.equals(jdbc.queryForObject("""
                        select exists(select 1 from tbl_promotion_analytics_event e
                            where e.promotion_id=:promotion and e.viewer_key=:viewer and e.playback_id=:playback
                              and e.metric_type='ANNOUNCEMENT_VIDEO_START' and e.source=:source
                              and e.observed_at<=:observed)
                        """, parameters, Boolean.class))) return;
        }
        jdbc.update("""
                insert into tbl_promotion_analytics_event(observation_id,promotion_id,actor_key,viewer_key,
                    profile_type,source,metric_type,observed_at,recorded_at,playback_id,delivery_id)
                values(:id,:promotion,:actor,:viewer,:profile,:source,:type,:observed,:recorded,:playback,:delivery)
                on conflict do nothing
                """, parameters);
        trackingStarted(now);
    }

    /** Server-confirmed entity attribution, in the existing mutation transaction. */
    @Transactional
    public void recordEngagement(UUID user, UUID announcement, UUID entityId, EngagementMetric metric, Instant occurredAt) {
        if (!properties.isEnabled()) return;
        identity.requireEnabled();
        if (user == null || announcement == null || entityId == null || metric == null || occurredAt == null) throw invalid();
        if (access.canManage(user)) return;
        ProfileType profile = access.viewerProfile(user);
        String source = metric == EngagementMetric.HIDE ? "FEED" : interactionSource();
        jdbc.update("""
                insert into tbl_promotion_analytics_engagement(entity_id,metric_type,promotion_id,viewer_key,
                    profile_type,source,occurred_at)
                values(:entity,:metric,:promotion,:viewer,:profile,:source,:time) on conflict do nothing
                """, new MapSqlParameterSource().addValue("entity", entityId).addValue("metric", metric.name())
                .addValue("promotion", announcement).addValue("viewer", identity.viewer(announcement, identity.actor(user, null)))
                .addValue("profile", profile.name()).addValue("source", source).addValue("time", Timestamp.from(occurredAt)));
        trackingStarted(occurredAt);
    }

    private String interactionSource() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes request)) return "UNATTRIBUTED";
        String source = request.getRequest().getHeader("X-Announcement-Source");
        if (source == null) return "UNATTRIBUTED";
        if (!Set.of("FEED", "DIRECTORY").contains(source)) throw invalid();
        return source;
    }

    private void trackingStarted(Instant now) {
        jdbc.update("""
                update tbl_promotion_analytics_state set tracking_started_at=:now
                where singleton=true and tracking_started_at is null
                """, Map.of("now", Timestamp.from(now)));
    }

    @Override
    @Transactional(readOnly = true, timeout = 4)
    public Map<UUID, Long> qualifiedImpressionCounts(UUID viewer, List<UUID> ids, Instant recordedBefore) {
        identity.requireEnabled();
        if (viewer == null || ids == null || ids.size() > 200 || recordedBefore == null) throw invalid();
        if (ids.isEmpty()) return Map.of();
        var result = new HashMap<UUID, Long>();
        jdbc.query("""
                select promotion_id,count(*) as n from tbl_promotion_analytics_event
                where actor_key=:actor and promotion_id in (:ids)
                  and metric_type='ANNOUNCEMENT_IMPRESSION' and recorded_at<=:anchor
                group by promotion_id
                """, Map.of("actor", identity.receiptActor(identity.actor(viewer, null)), "ids", ids,
                "anchor", Timestamp.from(recordedBefore.truncatedTo(ChronoUnit.MICROS))), row -> {
            result.put((UUID) row.getObject("promotion_id"), row.getLong("n"));
        });
        return Map.copyOf(result);
    }

    // Writable only because the existing authority guard holds shared locks through this snapshot.
    @Transactional(isolation = Isolation.REPEATABLE_READ, timeout = 5)
    public AnnouncementAnalyticsResponse.Summary summary(UUID admin, UUID announcement, LocalDate from,
                                                         LocalDate to, ProfileType profile, AnalyticsRequest.Source source,
                                                         Instant now) {
        // The legacy reporting flag controls venue-owner launch visibility.
        // Announcement reports are an explicitly authorized admin feature.
        identity.requireEnabled();
        access.requireManage(admin, announcement);
        LocalDate today = now.atZone(ZONE).toLocalDate();
        LocalDate last = to == null ? today : to;
        if (last.isBefore(LocalDate.of(1970, 1, 1)) || last.isAfter(today)) throw invalid();
        LocalDate first = from == null ? last.minusDays(29) : from;
        if (first.isBefore(LocalDate.of(1970, 1, 1)) || first.isAfter(last) || last.isAfter(today)
                || ChronoUnit.DAYS.between(first, last) >= 366
                || (profile != null && !AnnouncementAccess.PROFILES.contains(profile))) throw invalid();
        var params = new MapSqlParameterSource().addValue("promotion", announcement)
                .addValue("start", Timestamp.from(first.atStartOfDay(ZONE).toInstant()))
                .addValue("end", Timestamp.from(last.plusDays(1).atStartOfDay(ZONE).toInstant()))
                .addValue("zone", ZONE.getId());
        String dimensions = "";
        if (profile != null) { dimensions += " and profile_type=:profile"; params.addValue("profile", profile.name()); }
        if (source != null) { dimensions += " and source=:source"; params.addValue("source", source.name()); }
        String events = "promotion_id=:promotion and observed_at>=:start and observed_at<:end" + dimensions;
        String engagements = "promotion_id=:promotion and occurred_at>=:start and occurred_at<:end" + dimensions;
        var observed = eventMetrics("select " + EVENT_METRICS + " from tbl_promotion_analytics_event where " + events, params);
        var current = engagementMetrics("select " + ENGAGEMENT_METRICS + " from tbl_promotion_analytics_engagement a where "
                + engagements + " and " + LIVE_ENGAGEMENT, params);
        var dailyObserved = dailyEventMetrics("select (observed_at at time zone :zone)::date as day," + EVENT_METRICS
                + " from tbl_promotion_analytics_event where " + events + " group by day", params);
        var dailyCurrent = dailyEngagementMetrics("select (occurred_at at time zone :zone)::date as day," + ENGAGEMENT_METRICS
                + " from tbl_promotion_analytics_engagement a where " + engagements + " and " + LIVE_ENGAGEMENT
                + " group by day", params);
        var daily = new ArrayList<AnnouncementAnalyticsResponse.DailyPoint>();
        for (LocalDate date = first; !date.isAfter(last); date = date.plusDays(1)) {
            daily.add(new AnnouncementAnalyticsResponse.DailyPoint(date, merge(
                    dailyObserved.getOrDefault(date, new long[5]), dailyCurrent.getOrDefault(date, new long[3]))));
        }
        Instant started = jdbc.query("select tracking_started_at from tbl_promotion_analytics_state where singleton=true",
                Map.of(), rows -> rows.next() && rows.getTimestamp(1) != null ? rows.getTimestamp(1).toInstant() : null);
        return new AnnouncementAnalyticsResponse.Summary(announcement, first, last, ZONE.getId(), now, started,
                merge(observed, current), List.copyOf(daily));
    }

    private static final String EVENT_METRICS = """
            count(*) filter(where metric_type='ANNOUNCEMENT_IMPRESSION') as impressions,
            count(distinct viewer_key) filter(where metric_type='ANNOUNCEMENT_IMPRESSION') as reach,
            count(*) filter(where metric_type='ANNOUNCEMENT_DETAIL_VIEW') as details,
            count(*) filter(where metric_type='ANNOUNCEMENT_VIDEO_START') as starts,
            count(*) filter(where metric_type='ANNOUNCEMENT_VIDEO_COMPLETE') as completes
            """;
    private static final String ENGAGEMENT_METRICS = """
            count(*) filter(where metric_type='LIKE') as likes,
            count(*) filter(where metric_type='COMMENT') as comments,
            count(distinct viewer_key) filter(where metric_type='HIDE') as hiders
            """;
    // Only surviving server-side entities count. Unlike and soft-deleted comments need no client telemetry.
    private static final String LIVE_ENGAGEMENT = """
            ((a.metric_type='LIKE' and exists(select 1 from tbl_like l
                where l.id=a.entity_id and l.target_type='ANNOUNCEMENT' and l.target_id=a.promotion_id))
             or (a.metric_type='COMMENT' and exists(select 1 from tbl_comment c
                where c.id=a.entity_id and c.target_type='ANNOUNCEMENT' and c.target_id=a.promotion_id and not c.is_deleted))
             or (a.metric_type='HIDE' and exists(select 1 from tbl_musician_feed_feedback f
                where f.id=a.entity_id and f.action='HIDE' and f.item_type='ANNOUNCEMENT')))
            """;

    private long[] eventMetrics(String sql, MapSqlParameterSource params) {
        return jdbc.queryForObject(sql, params, (row, index) -> new long[]{row.getLong("impressions"), row.getLong("reach"),
                row.getLong("details"), row.getLong("starts"), row.getLong("completes")});
    }
    private long[] engagementMetrics(String sql, MapSqlParameterSource params) {
        return jdbc.queryForObject(sql, params, (row, index) -> new long[]{row.getLong("likes"),row.getLong("comments"),row.getLong("hiders")});
    }
    private Map<LocalDate, long[]> dailyEventMetrics(String sql, MapSqlParameterSource params) {
        Map<LocalDate, long[]> result = new HashMap<>();
        jdbc.query(sql, params, row -> { result.put(row.getDate("day").toLocalDate(), new long[]{row.getLong("impressions"),
                row.getLong("reach"),row.getLong("details"),row.getLong("starts"),row.getLong("completes")}); });
        return result;
    }
    private Map<LocalDate, long[]> dailyEngagementMetrics(String sql, MapSqlParameterSource params) {
        Map<LocalDate, long[]> result = new HashMap<>();
        jdbc.query(sql, params, row -> { result.put(row.getDate("day").toLocalDate(), new long[]{row.getLong("likes"),
                row.getLong("comments"),row.getLong("hiders")}); });
        return result;
    }
    private AnnouncementAnalyticsResponse.Metrics merge(long[] events, long[] engagements) {
        return new AnnouncementAnalyticsResponse.Metrics(events[0],events[1],events[2],events[3],events[4],
                engagements[0],engagements[1],engagements[2]);
    }
    private static SoundConnectException invalid() { return new SoundConnectException(ErrorType.ANALYTICS_INVALID); }
}

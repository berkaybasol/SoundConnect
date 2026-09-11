package com.berkayb.soundconnect.modules.feed.musician.delivery;

import com.berkayb.soundconnect.modules.feed.musician.core.MusicianFeedProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;

@Component
@Slf4j
@ConditionalOnProperty(prefix = "app.feed.musician", name = "cleanup-enabled",
        havingValue = "true", matchIfMissing = true)
public class MusicianFeedDeliveryCleanup {
    private final NamedParameterJdbcTemplate jdbc;
    private final MusicianFeedProperties properties;
    private final Clock clock;

    @Autowired
    public MusicianFeedDeliveryCleanup(NamedParameterJdbcTemplate jdbc, MusicianFeedProperties properties) {
        this(jdbc, properties, Clock.systemUTC());
    }

    MusicianFeedDeliveryCleanup(NamedParameterJdbcTemplate jdbc, MusicianFeedProperties properties,
                                Clock clock) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(cron = "${app.feed.musician.cleanup-cron:0 */5 * * * *}")
    public int purge() {
        int limit = Math.max(1, Math.min(properties.getCleanupBatchSize(), 10_000));
        int batches = Math.max(1, Math.min(properties.getCleanupMaxBatches(), 100));
        long deadline = System.nanoTime() + properties.getCleanupTimeBudget().toNanos();
        int deliveryTotal = 0;
        int telemetryTotal = 0;
        int replayTotal = 0;
        for (int batch = 0; batch < batches && System.nanoTime() < deadline; batch++) {
            Instant now = clock.instant();
            int telemetryDeleted = purgeTelemetryBatch(limit, now);
            int replayDeleted = purgeReplayBatch(limit, now);
            int deliveryDeleted = purgeDeliveryBatch(limit, now);
            telemetryTotal += telemetryDeleted;
            replayTotal += replayDeleted;
            deliveryTotal += deliveryDeleted;
            if (telemetryDeleted < limit && replayDeleted < limit && deliveryDeleted < limit) break;
        }
        if (deliveryTotal + telemetryTotal + replayTotal > 0) {
            log.info("Purged musician-feed retention rows: deliveries={}, telemetry={}, replays={}",
                    deliveryTotal, telemetryTotal, replayTotal);
        }
        return deliveryTotal + telemetryTotal + replayTotal;
    }

    int purgeDeliveryBatch(int limit, Instant now) {
        return jdbc.update("""
                with due as (
                    select id from tbl_musician_feed_delivery
                    where purge_after<:now order by purge_after,id
                    limit :limit for update skip locked
                )
                delete from tbl_musician_feed_delivery delivery
                using due where delivery.id=due.id
                """, new MapSqlParameterSource().addValue("now", Timestamp.from(now))
                .addValue("limit", limit));
    }

    int purgeTelemetryBatch(int limit, Instant now) {
        return jdbc.update("""
                with due as (
                    select id from tbl_musician_feed_telemetry_event
                    where recorded_at<:cutoff order by recorded_at,id
                    limit :limit for update skip locked
                )
                delete from tbl_musician_feed_telemetry_event event
                using due where event.id=due.id
                """, new MapSqlParameterSource()
                .addValue("cutoff", Timestamp.from(now.minus(properties.getTelemetryRetention())))
                .addValue("limit", limit));
    }

    int purgeReplayBatch(int limit, Instant now) {
        return jdbc.update("""
                with due as (
                    select id from tbl_musician_feed_page_replay
                    where expires_at<=:now order by expires_at,id
                    limit :limit for update skip locked
                )
                delete from tbl_musician_feed_page_replay replay
                using due where replay.id=due.id
                """, new MapSqlParameterSource().addValue("now", Timestamp.from(now))
                .addValue("limit", limit));
    }
}

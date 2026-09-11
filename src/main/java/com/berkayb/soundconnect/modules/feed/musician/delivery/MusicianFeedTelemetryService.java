package com.berkayb.soundconnect.modules.feed.musician.delivery;

import com.berkayb.soundconnect.modules.feed.musician.api.*;
import com.berkayb.soundconnect.modules.feed.musician.core.MusicianFeedProperties;
import com.berkayb.soundconnect.modules.feed.musician.core.MusicianFeedViewerGuard;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class MusicianFeedTelemetryService {
    private final NamedParameterJdbcTemplate jdbc;
    private final MusicianFeedDeliveryService deliveries;
    private final MusicianFeedViewerGuard viewerGuard;
    private final MusicianFeedProperties properties;
    private final Clock clock;

    @Autowired
    public MusicianFeedTelemetryService(NamedParameterJdbcTemplate jdbc,
                                        MusicianFeedDeliveryService deliveries,
                                        MusicianFeedViewerGuard viewerGuard,
                                        MusicianFeedProperties properties) {
        this(jdbc, deliveries, viewerGuard, properties, Clock.systemUTC());
    }

    MusicianFeedTelemetryService(NamedParameterJdbcTemplate jdbc,
                                 MusicianFeedDeliveryService deliveries,
                                 MusicianFeedViewerGuard viewerGuard,
                                 MusicianFeedProperties properties,
                                 Clock clock) {
        this.jdbc = jdbc;
        this.deliveries = deliveries;
        this.viewerGuard = viewerGuard;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public MusicianFeedTelemetryResponse record(UUID viewerId, MusicianFeedTelemetryRequest request) {
        viewerGuard.requireMusicianProfile(viewerId);
        if (request == null || request.clientEventId() == null || request.eventType() == null) throw invalid();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        MusicianFeedDeliveredItem delivery = deliveries.require(request.impressionToken(), viewerId, null, now);
        validateEvent(request.eventType(), delivery);
        Instant clientTime = request.occurredAt();
        if (clientTime != null && (clientTime.isBefore(delivery.deliveredAt().minus(properties.getTelemetryClockSkew()))
                || clientTime.isAfter(now.plus(properties.getTelemetryClockSkew())))) throw invalid();

        UUID eventId = UUID.randomUUID();
        var parameters = new MapSqlParameterSource().addValue("id", eventId)
                .addValue("viewerId", viewerId).addValue("clientEventId", request.clientEventId())
                .addValue("deliveryId", delivery.deliveryId()).addValue("eventType", request.eventType().name())
                .addValue("clientOccurredAt", clientTime == null ? null : Timestamp.from(clientTime))
                .addValue("recordedAt", Timestamp.from(now));
        List<Map<String, Object>> inserted = jdbc.queryForList("""
                insert into tbl_musician_feed_telemetry_event(
                    id,viewer_user_id,client_event_id,delivery_id,event_type,client_occurred_at,recorded_at)
                values(:id,:viewerId,:clientEventId,:deliveryId,:eventType,:clientOccurredAt,:recordedAt)
                on conflict do nothing
                returning id,recorded_at
                """, parameters);
        if (!inserted.isEmpty()) return new MusicianFeedTelemetryResponse(eventId,
                request.clientEventId(), request.eventType(), false, now);

        Optional<StoredEvent> byClientId = findByClientId(viewerId, request.clientEventId());
        if (byClientId.isPresent()) {
            StoredEvent existing = byClientId.get();
            if (!request.eventType().name().equals(existing.eventType())
                    || !delivery.deliveryId().equals(existing.deliveryId())) throw invalid();
            return duplicate(existing, request);
        }

        StoredEvent existing = findByDeliveryEvent(viewerId, delivery.deliveryId(), request.eventType())
                .orElseThrow(this::invalid);
        return duplicate(existing, request);
    }

    private Optional<StoredEvent> findByClientId(UUID viewerId, UUID clientEventId) {
        return jdbc.query("""
                        select id,delivery_id,event_type,recorded_at
                        from tbl_musician_feed_telemetry_event
                        where viewer_user_id=:viewerId and client_event_id=:clientEventId
                        """,
                new MapSqlParameterSource().addValue("viewerId", viewerId)
                        .addValue("clientEventId", clientEventId),
                (row, number) -> storedEvent(row)).stream().findFirst();
    }

    private Optional<StoredEvent> findByDeliveryEvent(UUID viewerId, UUID deliveryId,
                                                       MusicianFeedTelemetryEventType eventType) {
        return jdbc.query("""
                        select id,delivery_id,event_type,recorded_at
                        from tbl_musician_feed_telemetry_event
                        where viewer_user_id=:viewerId and delivery_id=:deliveryId and event_type=:eventType
                        """,
                new MapSqlParameterSource().addValue("viewerId", viewerId)
                        .addValue("deliveryId", deliveryId).addValue("eventType", eventType.name()),
                (row, number) -> storedEvent(row)).stream().findFirst();
    }

    private StoredEvent storedEvent(java.sql.ResultSet row) throws java.sql.SQLException {
        return new StoredEvent(row.getObject("id", UUID.class),
                row.getObject("delivery_id", UUID.class), row.getString("event_type"),
                row.getTimestamp("recorded_at").toInstant());
    }

    private MusicianFeedTelemetryResponse duplicate(StoredEvent existing,
                                                      MusicianFeedTelemetryRequest request) {
        return new MusicianFeedTelemetryResponse(existing.id(), request.clientEventId(),
                request.eventType(), true, existing.recordedAt());
    }

    private void validateEvent(MusicianFeedTelemetryEventType event,
                               MusicianFeedDeliveredItem delivery) {
        boolean valid = switch (event) {
            case IMPRESSION, OPEN -> true;
            case CTA -> delivery.campaignId() != null;
            case FOLLOW -> "PROFILE".equals(delivery.targetType());
            case SAVE, APPLY -> "COLLAB".equals(delivery.targetType());
            case HIDE -> delivery.feedbackCapabilities().contains(MusicianFeedFeedbackAction.HIDE);
            case REPORT -> delivery.feedbackCapabilities().contains(MusicianFeedFeedbackAction.REPORT);
            case MUTE -> delivery.authorProfileId() != null;
        };
        if (!valid) throw invalid();
    }

    private SoundConnectException invalid() {
        return new SoundConnectException(ErrorType.BAD_REQUEST);
    }

    private record StoredEvent(UUID id, UUID deliveryId, String eventType, Instant recordedAt) { }
}

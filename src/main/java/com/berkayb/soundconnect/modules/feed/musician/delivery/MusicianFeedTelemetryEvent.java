package com.berkayb.soundconnect.modules.feed.musician.delivery;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Getter
@NoArgsConstructor
@Table(name = "tbl_musician_feed_telemetry_event",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_musician_feed_telemetry_client",
                        columnNames = {"viewer_user_id", "client_event_id"}),
                @UniqueConstraint(name = "uk_musician_feed_telemetry_delivery_event",
                        columnNames = {"viewer_user_id", "delivery_id", "event_type"})
        },
        indexes = @Index(name = "idx_musician_feed_telemetry_delivery",
                columnList = "delivery_id,event_type,recorded_at"))
public class MusicianFeedTelemetryEvent {
    @Id private UUID id;
    @Column(name = "viewer_user_id", nullable = false) private UUID viewerUserId;
    @Column(name = "client_event_id", nullable = false) private UUID clientEventId;
    @Column(name = "delivery_id", nullable = false) private UUID deliveryId;
    @Column(name = "event_type", nullable = false, length = 24) private String eventType;
    @Column(name = "client_occurred_at") private Instant clientOccurredAt;
    @Column(name = "recorded_at", nullable = false) private Instant recordedAt;
}

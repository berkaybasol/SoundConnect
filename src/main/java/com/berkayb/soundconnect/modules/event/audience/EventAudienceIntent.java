package com.berkayb.soundconnect.modules.event.audience;

import jakarta.persistence.*;
import lombok.*;
import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "tbl_event_audience_intent") @Getter @Setter @NoArgsConstructor
public class EventAudienceIntent {
    @EmbeddedId private Id id;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private EventIntent intent = EventIntent.NONE;
    @Column(name = "published_on_profile", nullable = false) private boolean publishedOnProfile;
    @Column(length = 500) private String note;
    @Column(nullable = false) private long version;
    @Column(name = "updated_at") private Instant updatedAt;
    @Column(name = "published_at") private Instant publishedAt;
    public EventAudienceIntent(UUID userId, UUID eventId) { id = new Id(userId, eventId); }

    @Embeddable @Getter @Setter @NoArgsConstructor @AllArgsConstructor @EqualsAndHashCode
    public static class Id implements Serializable {
        @Column(name = "user_id", nullable = false) private UUID userId;
        @Column(name = "event_id", nullable = false) private UUID eventId;
    }
}

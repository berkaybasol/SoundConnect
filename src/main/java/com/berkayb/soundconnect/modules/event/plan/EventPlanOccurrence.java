package com.berkayb.soundconnect.modules.event.plan;

import jakarta.persistence.*;
import lombok.*;
import java.io.Serializable;
import java.time.LocalDate;
import java.util.UUID;

@Entity @Table(name = "event_plan_occurrences", uniqueConstraints = @UniqueConstraint(name = "uk_event_plan_occurrence_event", columnNames = "event_id"))
@Getter @Setter @NoArgsConstructor
public class EventPlanOccurrence {
    @EmbeddedId private Id id;
    @Column(name = "event_id") private UUID eventId;
    @Column(name = "event_date", nullable = false) private LocalDate eventDate;
    // Historical column names: these are the raw target snapshot for BOTH generated and overridden dates.
    // Past dates must never acquire the current plan's later performer identity.
    @Column(name = "override_musician_profile_id") private UUID overrideMusicianProfileId;
    @Column(name = "override_band_id") private UUID overrideBandId;
    @Column(name = "override_manual_performer_name", length = 120) private String overrideManualPerformerName;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private EventPlanOccurrenceStatus status;
    public EventPlanOccurrence(UUID planId, LocalDate date) { id = new Id(planId, date); eventDate = date; }
    @Embeddable @Getter @Setter @NoArgsConstructor @AllArgsConstructor @EqualsAndHashCode
    public static class Id implements Serializable {
        @Column(name = "plan_id", nullable = false) private UUID planId;
        @Column(name = "scheduled_date", nullable = false) private LocalDate scheduledDate;
    }
}

package com.berkayb.soundconnect.modules.event.publication;

import jakarta.persistence.*;
import lombok.*;
import java.io.Serializable;
import java.util.UUID;

/** Only an explicit member choice publishes a band's event on that member's profile. */
@Entity
@Table(name = "event_member_publications")
@org.hibernate.annotations.Check(name = "ck_event_member_publication_version", constraints = "version >= 0")
@Getter @Setter @NoArgsConstructor
public class EventMemberPublication {
    @EmbeddedId private Id id;
    @Column(nullable = false) private boolean visible;
    @Column(nullable = false) private long version;

    public EventMemberPublication(UUID eventId, UUID profileId) { id = new Id(eventId, profileId); }

    @Embeddable @Getter @Setter @NoArgsConstructor @AllArgsConstructor @EqualsAndHashCode
    public static class Id implements Serializable {
        @Column(name = "event_id", nullable = false) private UUID eventId;
        @Column(name = "musician_profile_id", nullable = false) private UUID musicianProfileId;
    }
}

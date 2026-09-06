package com.berkayb.soundconnect.modules.event.creation;

import com.berkayb.soundconnect.shared.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.*;
import java.util.UUID;

/** Compatibility mapping for already-applied schema; musician event creation is no longer exposed. */
@Getter @Builder @NoArgsConstructor @AllArgsConstructor
@Entity
@Table(name = "musician_event_creations", uniqueConstraints = {
        @UniqueConstraint(name = "uk_musician_event_creation_client", columnNames = {"organizer_user_id", "client_request_id"}),
        @UniqueConstraint(name = "uk_musician_event_creation_event", columnNames = "event_id")
})
public class MusicianEventCreation extends BaseEntity {
    @Column(name = "organizer_user_id", nullable = false, updatable = false)
    private UUID organizerUserId;

    @Column(name = "client_request_id", nullable = false, updatable = false)
    private UUID clientRequestId;

    @Column(name = "payload_hash", nullable = false, updatable = false, length = 64)
    private String payloadHash;

    // Deliberately no event FK: the immutable receipt remains a tombstone after event deletion.
    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;
}

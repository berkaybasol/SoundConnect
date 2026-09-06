package com.berkayb.soundconnect.modules.event.venue.entity;

import com.berkayb.soundconnect.modules.event.entity.Event;
import com.berkayb.soundconnect.modules.event.venue.enums.EventVenueRequestPurpose;
import com.berkayb.soundconnect.modules.event.venue.enums.EventVenueRequestStatus;
import com.berkayb.soundconnect.modules.venue.entity.Venue;
import com.berkayb.soundconnect.shared.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import java.time.LocalDateTime;
import java.util.UUID;

/** Compatibility-only mapping: the reciprocal venue invitation workflow has been removed. */
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
@Entity
@Table(name = "event_venue_requests",
        uniqueConstraints = @UniqueConstraint(name = "uk_event_venue_request_event", columnNames = "event_id"),
        indexes = @Index(name = "idx_event_venue_request_venue_status", columnList = "venue_id,status,created_at"))
@Check(name = "ck_event_venue_request_contract", constraints = """
        trim(venue_name_snapshot) <> ''
        and request_purpose in ('VENUE_CONSENT', 'PROFILE_VISIBILITY')
        and ((status = 'PENDING' and decided_by_user_id is null and decided_at is null)
          or (status in ('ACCEPTED', 'REJECTED') and decided_by_user_id is not null and decided_at is not null))
        and version >= 0
        """)
public class EventVenueRequest extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Event event;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "venue_id", nullable = false, updatable = false)
    private Venue venue;

    @Column(name = "venue_name_snapshot", nullable = false, updatable = false, length = 255)
    private String venueNameSnapshot;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EventVenueRequestStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "request_purpose", nullable = false, updatable = false, length = 30)
    private EventVenueRequestPurpose requestPurpose;

    @Column(name = "requested_by_user_id", nullable = false, updatable = false)
    private UUID requestedByUserId;

    @Column(name = "decided_by_user_id")
    private UUID decidedByUserId;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @Version @Column(nullable = false)
    private long version;
}

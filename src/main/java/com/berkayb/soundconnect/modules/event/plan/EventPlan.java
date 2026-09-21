package com.berkayb.soundconnect.modules.event.plan;

import com.berkayb.soundconnect.shared.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Private recurrence aggregate. Generated events remain ordinary public events. */
@Entity @Table(name = "event_plans", uniqueConstraints = @UniqueConstraint(name = "uk_event_plan_creation",
        columnNames = {"organizer_user_id", "client_request_id"}))
@Getter @Setter @NoArgsConstructor
public class EventPlan extends BaseEntity {
    @Column(name = "organizer_user_id", nullable = false, updatable = false) private UUID organizerUserId;
    @Column(name = "venue_id", nullable = false, updatable = false) private UUID venueId;
    @Column(name = "client_request_id", nullable = false, updatable = false) private UUID clientRequestId;
    @Column(name = "creation_hash", nullable = false, length = 64, updatable = false) private String creationHash;
    // Application revision only. Rolling materialization must not invalidate an open editor.
    @Column(nullable = false) private long version;
    @Column(name = "consent_revision", nullable = false) private long consentRevision;
    @Column(name = "start_date", nullable = false) private LocalDate startDate;
    @Column(name = "until_date") private LocalDate untilDate;
    @Column(name = "weekday_mask", nullable = false) private int weekdayMask;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "excluded_dates", nullable = false, columnDefinition = "jsonb")
    private List<String> excludedDates = new ArrayList<>();
    @Column(nullable = false, length = 255) private String title;
    @Column(length = 500) private String description;
    @Column(name = "start_time", nullable = false) private LocalTime startTime;
    @Column(name = "end_time") private LocalTime endTime;
    @Column(name = "poster_image", length = 255) private String posterImage;
    // Target identities survive target deletion; missing targets can never regain consent implicitly.
    @Column(name = "musician_profile_id") private UUID musicianProfileId;
    @Column(name = "band_id") private UUID bandId;
    @Column(name = "manual_performer_name", length = 120) private String manualPerformerName;
    @Column(name = "performer_name_snapshot", length = 120) private String performerNameSnapshot;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private EventPlanStatus status;
    @Enumerated(EnumType.STRING) @Column(name = "consent_status", nullable = false, length = 20)
    private EventPlanConsentStatus consentStatus;
    @Column(name = "show_on_profile", nullable = false) private boolean showOnProfile;
    @Column(name = "accepted_publication") private Boolean acceptedPublication;
    @Column(name = "decided_by_user_id") private UUID decidedByUserId;
    @Column(name = "generated_through") private LocalDate generatedThrough;
    @Column(name = "cancel_future", nullable = false) private boolean cancelFuture;
}

package com.berkayb.soundconnect.modules.collab.entity;

import com.berkayb.soundconnect.modules.collab.enums.*;
import com.berkayb.soundconnect.modules.instrument.entity.Instrument;
import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.shared.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.BatchSize;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "tbl_collab", uniqueConstraints = {
        @UniqueConstraint(name = "uk_collab_create_request", columnNames = {"owner_user_id", "client_request_id"}),
        @UniqueConstraint(name = "uk_collab_job_publisher_reference",
                columnNames = {"id", "publisher_actor_id", "owner_user_id"})
},
        indexes = {
                @Index(name = "idx_collab_discovery", columnList = "status,cadence,published_at,id"),
                @Index(name = "idx_collab_city_discovery", columnList = "city_id,status,cadence,published_at,id"),
                @Index(name = "idx_collab_wanted_instrument", columnList = "wanted_type,instrument_id,status,published_at"),
                @Index(name = "idx_collab_publisher", columnList = "publisher_actor_id,status,published_at"),
                @Index(name = "idx_collab_expiry", columnList = "status,expires_at,id")
        })
public class Collab extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_user_id", nullable = false)
    private User owner;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "publisher_actor_id", nullable = false)
    private CollabActor publisherActor;

    @Column(name = "client_request_id", nullable = false, columnDefinition = "uuid")
    private UUID clientRequestId;

    @Column(name = "creation_payload_hash", nullable = false, length = 64, updatable = false)
    private String creationPayloadHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private CollabCadence cadence;

    @Enumerated(EnumType.STRING)
    @Column(name = "wanted_type", nullable = false, length = 16)
    private CollabWantedType wantedType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "instrument_id")
    private Instrument instrument;

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private CollabBranch branch;

    @Column(name = "custom_specialty", length = 80)
    private String customSpecialty;

    @Column(nullable = false, length = 100)
    private String title;

    @Column(nullable = false, length = 500)
    private String description;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "city_id", nullable = false)
    private City city;

    @ElementCollection(fetch = FetchType.LAZY)
    @BatchSize(size = 50)
    @CollectionTable(name = "tbl_collab_genre", joinColumns = @JoinColumn(name = "collab_id"))
    @OrderColumn(name = "position")
    @Column(name = "genre", nullable = false, length = 40)
    @Builder.Default
    private List<String> genres = new ArrayList<>();

    @Column(name = "scheduled_at")
    private Instant scheduledAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "fee_amount_minor")
    private Long feeAmountMinor;

    @Column(length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private CollabListingStatus status = CollabListingStatus.DRAFT;

    @Enumerated(EnumType.STRING)
    @Column(name = "closure_reason", length = 24)
    private CollabClosureReason closureReason;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Version
    @Builder.Default
    @Column(nullable = false)
    private long version = 0;
}

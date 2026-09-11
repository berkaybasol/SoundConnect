package com.berkayb.soundconnect.modules.feed.musician.delivery;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/** Hibernate-first schema mirror; replay writes remain atomic JDBC operations. */
@Entity
@Getter
@NoArgsConstructor
@Table(name = "tbl_musician_feed_page_replay",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_musician_feed_replay_position",
                        columnNames = {"viewer_user_id", "feed_session_id", "request_position"}),
                @UniqueConstraint(name = "uk_musician_feed_replay_fingerprint",
                        columnNames = {"viewer_user_id", "feed_session_id", "request_fingerprint"})
        }, indexes = @Index(name = "idx_musician_feed_replay_expiry", columnList = "expires_at,id"))
public class MusicianFeedPageReplay {
    @Id private UUID id;
    @Column(name = "viewer_user_id", nullable = false) private UUID viewerUserId;
    @Column(name = "feed_session_id", nullable = false) private UUID feedSessionId;
    @Column(name = "request_position", nullable = false) private long requestPosition;
    @Column(name = "request_fingerprint", nullable = false, length = 64) private String requestFingerprint;
    @Column(name = "requested_limit", nullable = false) private int requestedLimit;
    @Column(name = "supported_types", nullable = false, length = 768) private String supportedTypes;
    @Column(name = "schema_version", nullable = false) private int schemaVersion;
    @Column(name = "algorithm_version", nullable = false, length = 64) private String algorithmVersion;
    @Column(name = "response_json", nullable = false, columnDefinition = "text") private String responseJson;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "expires_at", nullable = false) private Instant expiresAt;
}

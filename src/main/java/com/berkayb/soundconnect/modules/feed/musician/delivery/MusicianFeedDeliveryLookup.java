package com.berkayb.soundconnect.modules.feed.musician.delivery;

import com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedFeedbackAction;
import com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedItemType;
import com.berkayb.soundconnect.modules.feed.musician.candidate.MusicianFeedLane;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Validates delivery evidence without depending on feed generation or source visibility services. */
@Service
public class MusicianFeedDeliveryLookup {
    private final NamedParameterJdbcTemplate jdbc;
    private final MusicianFeedDeliveryTokenCodec tokens;

    public MusicianFeedDeliveryLookup(NamedParameterJdbcTemplate jdbc, MusicianFeedDeliveryTokenCodec tokens) {
        this.jdbc = jdbc;
        this.tokens = tokens;
    }

    @Transactional(readOnly = true)
    public MusicianFeedDeliveredItem require(String token, UUID viewerId, String expectedItemId, Instant now) {
        MusicianFeedDeliveryTokenCodec.Claims claims = tokens.decode(token, viewerId, now);
        List<MusicianFeedDeliveredItem> rows = jdbc.query("""
                select * from tbl_musician_feed_delivery
                where id=:id and viewer_user_id=:viewerId and expires_at>:now
                """, new MapSqlParameterSource().addValue("id", claims.deliveryId())
                .addValue("viewerId", viewerId).addValue("now", Timestamp.from(now)), this::map);
        if (rows.size() != 1) throw invalid();
        MusicianFeedDeliveredItem row = rows.getFirst();
        validateClaims(claims, row, expectedItemId);
        return row;
    }

    /** Offline analytics prove a past exposure; present-time feedback still uses require(now). */
    @Transactional(readOnly = true)
    public MusicianFeedDeliveredItem requireForObservation(String token, UUID viewerId, String expectedItemId,
                                                           Instant observedAt, Instant receivedAt) {
        if (observedAt == null || receivedAt == null
                || observedAt.isBefore(receivedAt.minus(Duration.ofHours(24)))
                || observedAt.isAfter(receivedAt.plus(Duration.ofMinutes(2)))) throw invalid();
        return require(token, viewerId, expectedItemId, observedAt);
    }

    MusicianFeedDeliveredItem map(ResultSet row, int index) throws SQLException {
        String capabilities = row.getString("feedback_capabilities");
        EnumSet<MusicianFeedFeedbackAction> parsed = EnumSet.noneOf(MusicianFeedFeedbackAction.class);
        if (capabilities != null && !capabilities.isBlank()) {
            for (String value : capabilities.split(",")) parsed.add(MusicianFeedFeedbackAction.valueOf(value));
        }
        return new MusicianFeedDeliveredItem(row.getObject("id", UUID.class),
                row.getObject("viewer_user_id", UUID.class), row.getObject("feed_session_id", UUID.class),
                row.getString("item_id"), MusicianFeedItemType.valueOf(row.getString("item_type")),
                row.getString("target_type"), row.getObject("target_id", UUID.class),
                row.getString("author_profile_type"), row.getObject("author_profile_id", UUID.class),
                row.getString("reason_code"), Set.copyOf(parsed), row.getInt("schema_version"),
                row.getString("algorithm_version"), row.getLong("absolute_position"),
                row.getObject("campaign_id", UUID.class), row.getString("evidence_json"),
                row.getTimestamp("delivered_at").toInstant(), row.getTimestamp("expires_at").toInstant(),
                row.getTimestamp("purge_after").toInstant(),
                MusicianFeedLane.valueOf(row.getString("feed_lane")));
    }

    void validateClaims(MusicianFeedDeliveryTokenCodec.Claims claims,
                        MusicianFeedDeliveredItem row, String expectedItemId) {
        if ((expectedItemId != null && !expectedItemId.equals(row.itemId()))
                || !claims.feedSessionId().equals(row.feedSessionId())
                || !claims.itemId().equals(row.itemId())
                || !claims.targetType().equals(row.targetType())
                || !claims.targetId().equals(row.targetId())
                || claims.schemaVersion() != row.schemaVersion()
                || !claims.algorithmVersion().equals(row.algorithmVersion())
                || claims.absolutePosition() != row.absolutePosition()) throw invalid();
    }

    private SoundConnectException invalid() { return new SoundConnectException(ErrorType.BAD_REQUEST); }
}

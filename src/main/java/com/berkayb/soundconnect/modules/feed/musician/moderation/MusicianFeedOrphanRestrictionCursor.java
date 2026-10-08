package com.berkayb.soundconnect.modules.feed.musician.moderation;

import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/** An opaque, validated list position; moderation authority is checked independently. */
@Component
public class MusicianFeedOrphanRestrictionCursor {
    private static final String PURPOSE = "orphan-feed-restrictions-v1";
    private final ObjectMapper mapper;

    public MusicianFeedOrphanRestrictionCursor(ObjectMapper mapper) { this.mapper = mapper; }

    public String encode(UUID actor, Position position) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mapper.writeValueAsBytes(
                    new Payload(PURPOSE, actor, position.anchor().toString(), position.appliedAt().toString(), position.reportId())));
        } catch (Exception failure) { throw new IllegalStateException("Cannot encode restriction position", failure); }
    }

    public Position decode(UUID actor, String cursor, Instant now) {
        try {
            if (cursor == null || cursor.isBlank() || cursor.length() > 1024) throw invalid();
            var value = mapper.readValue(Base64.getUrlDecoder().decode(cursor), Payload.class);
            if (!PURPOSE.equals(value.purpose()) || actor == null || !actor.equals(value.actor()) || value.reportId() == null) throw invalid();
            Instant anchor = Instant.parse(value.anchor());
            Instant appliedAt = Instant.parse(value.appliedAt());
            if (anchor.isAfter(now.plusSeconds(30)) || !anchor.isAfter(now.minus(Duration.ofHours(24)))
                    || appliedAt.isBefore(Instant.EPOCH) || appliedAt.isAfter(anchor)) throw invalid();
            return new Position(anchor, appliedAt, value.reportId());
        } catch (Exception invalid) { throw invalid(); }
    }

    private SoundConnectException invalid() { return new SoundConnectException(ErrorType.MUSICIAN_FEED_REPORT_CURSOR_INVALID); }
    public record Position(Instant anchor, Instant appliedAt, UUID reportId) { }
    private record Payload(String purpose, UUID actor, String anchor, String appliedAt, UUID reportId) { }
}

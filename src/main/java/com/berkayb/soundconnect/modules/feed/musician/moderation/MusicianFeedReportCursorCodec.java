package com.berkayb.soundconnect.modules.feed.musician.moderation;

import com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedItemType;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

/** Opaque position only: every request independently requires moderation authority. */
@Component
public class MusicianFeedReportCursorCodec {
    private static final Duration TTL = Duration.ofHours(24);
    private final ObjectMapper mapper;

    public MusicianFeedReportCursorCodec(ObjectMapper mapper) { this.mapper = mapper; }

    public String encode(UUID viewer, MusicianFeedReportStatus status, MusicianFeedItemType type, Position position) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mapper.writeValueAsBytes(
                    new Payload(1, viewer, status, type, position.anchor().toString(),
                            position.reportedAt().toString(), position.id())));
        } catch (Exception impossible) {
            throw new IllegalStateException("Cannot encode report queue position", impossible);
        }
    }

    public Position decode(String token, UUID viewer, MusicianFeedReportStatus status,
                           MusicianFeedItemType type, Instant now) {
        try {
            if (token == null || token.isBlank() || token.length() > 1024) throw invalid();
            Payload value = mapper.readValue(Base64.getUrlDecoder().decode(token), Payload.class);
            if (value.version() != 1 || viewer == null || !viewer.equals(value.viewer())
                    || value.status() != status || !Objects.equals(value.itemType(), type)
                    || value.id() == null) throw invalid();
            Instant anchor = Instant.parse(value.anchor());
            Instant reportedAt = Instant.parse(value.reportedAt());
            if (anchor.isAfter(now.plusSeconds(30)) || !anchor.isAfter(now.minus(TTL))
                    || reportedAt.isBefore(Instant.EPOCH) || reportedAt.isAfter(anchor)) throw invalid();
            return new Position(anchor, reportedAt, value.id());
        } catch (SoundConnectException known) {
            throw known;
        } catch (Exception malformed) {
            throw invalid();
        }
    }

    private SoundConnectException invalid() {
        return new SoundConnectException(ErrorType.MUSICIAN_FEED_REPORT_CURSOR_INVALID);
    }

    public record Position(Instant anchor, Instant reportedAt, UUID id) { }
    private record Payload(int version, UUID viewer, MusicianFeedReportStatus status,
                           MusicianFeedItemType itemType, String anchor, String reportedAt, UUID id) { }
}

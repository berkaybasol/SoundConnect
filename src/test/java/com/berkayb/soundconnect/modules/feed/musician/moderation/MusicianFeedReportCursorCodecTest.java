package com.berkayb.soundconnect.modules.feed.musician.moderation;

import com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedItemType;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class MusicianFeedReportCursorCodecTest {
    private static final Instant NOW = Instant.parse("2026-09-13T12:00:00Z");
    private final ObjectMapper mapper = new ObjectMapper();
    private final MusicianFeedReportCursorCodec codec = new MusicianFeedReportCursorCodec(mapper);
    private final UUID viewer = UUID.randomUUID();
    private final MusicianFeedReportCursorCodec.Position position =
            new MusicianFeedReportCursorCodec.Position(NOW, NOW.minusSeconds(10), UUID.randomUUID());

    @Test
    void roundTripPreservesPositionAndRejectsDifferentViewerOrFilter() {
        String token = token();
        assertThat(codec.decode(token, viewer, MusicianFeedReportStatus.NEW, MusicianFeedItemType.TRACK, NOW))
                .isEqualTo(position);
        invalid(() -> codec.decode(token, UUID.randomUUID(), MusicianFeedReportStatus.NEW, MusicianFeedItemType.TRACK, NOW));
        invalid(() -> codec.decode(token, viewer, MusicianFeedReportStatus.REVIEWING, MusicianFeedItemType.TRACK, NOW));
        invalid(() -> codec.decode(token, viewer, MusicianFeedReportStatus.NEW, null, NOW));
    }

    @Test
    void malformedOversizedExpiredAndUnsafeTemporalPositionsAreBadRequests() throws Exception {
        for (String value : new String[]{"", "not-base64!", "x".repeat(1025),
                replace("version", 2), replace("reportedAt", Instant.MIN.toString()),
                replace("reportedAt", NOW.plusSeconds(1).toString()),
                replace("anchor", NOW.plusSeconds(31).toString()),
                replace("anchor", NOW.minusSeconds(86_400).toString())}) {
            invalid(() -> codec.decode(value, viewer, MusicianFeedReportStatus.NEW, MusicianFeedItemType.TRACK, NOW));
        }
    }

    private String token() { return codec.encode(viewer, MusicianFeedReportStatus.NEW, MusicianFeedItemType.TRACK, position); }
    private String replace(String field, Object value) throws Exception {
        ObjectNode body = (ObjectNode) mapper.readTree(Base64.getUrlDecoder().decode(token()));
        if (value instanceof Integer number) body.put(field, number); else body.put(field, value.toString());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mapper.writeValueAsBytes(body));
    }
    private void invalid(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(SoundConnectException.class,
                error -> assertThat(error.getErrorType()).isEqualTo(ErrorType.MUSICIAN_FEED_REPORT_CURSOR_INVALID));
    }
}

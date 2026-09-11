package com.berkayb.soundconnect.modules.feed.musician.cursor;

import com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedItemType;
import com.berkayb.soundconnect.modules.feed.musician.core.MusicianFeedProperties;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MusicianFeedCursorCodecTest {
    private static final Instant ANCHOR = Instant.parse("2026-09-11T10:15:30Z");
    private static final Set<MusicianFeedItemType> TYPES = Set.of(
            MusicianFeedItemType.TRACK, MusicianFeedItemType.COLLAB);

    private MusicianFeedCursorCodec codec;
    private UUID viewer;
    private MusicianFeedCursorState state;

    @BeforeEach
    void setUp() {
        MusicianFeedProperties properties = new MusicianFeedProperties();
        properties.setCursorSecret("unit-test-musician-feed-secret-at-least-32-bytes");
        properties.setCursorTtl(Duration.ofHours(24));
        codec = new MusicianFeedCursorCodec(new ObjectMapper(), properties);
        viewer = UUID.randomUUID();
        state = new MusicianFeedCursorState(viewer, UUID.randomUUID(), ANCHOR,
                new MusicianFeedCursorState.CursorPosition(42_001L,
                        ANCHOR.minusSeconds(60), "TRACK:" + UUID.randomUUID()), 17);
    }

    @Test
    void signedCursorRoundTripsEveryPaginationInvariant() {
        String token = codec.encode(state, TYPES);

        MusicianFeedCursorState decoded = codec.decode(token, viewer, TYPES, ANCHOR.plusSeconds(5));

        assertThat(decoded).isEqualTo(state);
        assertThat(token).doesNotContain(viewer.toString()).doesNotContain("TRACK:");
    }

    @Test
    void tamperingViewerOrAdvertisedRendererSetFailsClosed() {
        String token = codec.encode(state, TYPES);
        int flipAt = token.indexOf('.') / 2;
        char replacement = token.charAt(flipAt) == 'A' ? 'B' : 'A';
        String tampered = token.substring(0, flipAt) + replacement + token.substring(flipAt + 1);

        assertInvalid(() -> codec.decode(tampered, viewer, TYPES, ANCHOR));
        assertInvalid(() -> codec.decode(token, UUID.randomUUID(), TYPES, ANCHOR));
        assertInvalid(() -> codec.decode(token, viewer, Set.of(MusicianFeedItemType.TRACK), ANCHOR));
    }

    @Test
    void expiredOrImplausiblyFutureAnchorsFailClosed() {
        String token = codec.encode(state, TYPES);

        assertInvalid(() -> codec.decode(token, viewer, TYPES, ANCHOR.plus(Duration.ofHours(24))));
        assertInvalid(() -> codec.decode(token, viewer, TYPES, ANCHOR.plus(Duration.ofHours(24)).plusMillis(1)));
        assertInvalid(() -> codec.decode(token, viewer, TYPES, ANCHOR.minusSeconds(31)));
    }

    @Test
    void cursorFromAnotherSchemaOrRankingVersionCannotCrossADeployBoundary() {
        MusicianFeedProperties properties = new MusicianFeedProperties();
        properties.setCursorSecret("unit-test-musician-feed-secret-at-least-32-bytes");
        MusicianFeedCursorCodec previous = new MusicianFeedCursorCodec(
                new ObjectMapper(), properties, 1, "musician-v0.9.0");
        String oldToken = previous.encode(state, TYPES);

        assertInvalid(() -> codec.decode(oldToken, viewer, TYPES, ANCHOR));
        assertInvalid(() -> codec.decodeForReplay(oldToken, viewer, TYPES, ANCHOR));
    }

    @Test
    void replayDecodeIgnoresOnlyMutableRankingContext() {
        String token = codec.encode(state, TYPES);

        MusicianFeedCursorState decoded = codec.decodeForReplay(token, viewer, TYPES, ANCHOR.plusSeconds(1));

        assertThat(decoded).isEqualTo(state);
        assertInvalid(() -> codec.decode(token, viewer, TYPES, ANCHOR.plusSeconds(1), "changed-context"));
    }

    private static void assertInvalid(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(SoundConnectException.class, failure ->
                        assertThat(failure.getErrorType()).isEqualTo(ErrorType.MUSICIAN_FEED_CURSOR_INVALID));
    }
}

package com.berkayb.soundconnect.modules.feed.musician.feedback;

import com.berkayb.soundconnect.modules.feed.musician.core.MusicianFeedProperties;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class MusicianFeedMutedAuthorsCursorCodecTest {
    private static final Instant NOW = Instant.parse("2026-09-13T15:20:00.123456Z");
    private final UUID viewer = UUID.randomUUID();
    private final MusicianFeedProperties properties = new MusicianFeedProperties();
    private final MusicianFeedMutedAuthorsCursorCodec codec =
            new MusicianFeedMutedAuthorsCursorCodec(new ObjectMapper(), properties);

    @Test
    void retainsTheExactMicrosecondKeysetAndSessionAnchor() {
        var position = new MusicianFeedMutedAuthorsCursorCodec.Position(NOW, NOW.minusNanos(1000), UUID.randomUUID());
        assertThat(codec.decode(codec.encode(viewer, position), viewer, NOW.plusSeconds(1))).isEqualTo(position);
    }

    @Test
    void rejectsAnotherViewerTamperingAndSecretRotation() {
        String token = codec.encode(viewer, new MusicianFeedMutedAuthorsCursorCodec.Position(NOW, NOW, UUID.randomUUID()));
        invalid(token, UUID.randomUUID(), NOW);
        String body = token.substring(0, token.indexOf('.'));
        String modifiedBody = Base64.getUrlEncoder().withoutPadding().encodeToString(
                (new String(Base64.getUrlDecoder().decode(body), java.nio.charset.StandardCharsets.UTF_8) + " ")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        invalid(modifiedBody + token.substring(token.indexOf('.')), viewer, NOW);
        properties.setCursorSecret("a-different-dedicated-secret-at-least-32-bytes");
        invalid(token, viewer, NOW);
    }

    @Test
    void rejectsExpiryFutureAnchorsAndPositionsAfterTheAnchor() {
        invalid(codec.encode(viewer, new MusicianFeedMutedAuthorsCursorCodec.Position(
                NOW.minus(properties.getCursorTtl()), NOW.minus(properties.getCursorTtl()), UUID.randomUUID())), viewer, NOW);
        invalid(codec.encode(viewer, new MusicianFeedMutedAuthorsCursorCodec.Position(
                NOW.plusSeconds(31), NOW, UUID.randomUUID())), viewer, NOW);
        invalid(codec.encode(viewer, new MusicianFeedMutedAuthorsCursorCodec.Position(
                NOW, NOW.plusNanos(1000), UUID.randomUUID())), viewer, NOW);
    }

    @Test
    void rejectsMalformedAndOversizedInput() {
        invalid(null, viewer, NOW);
        for (String value : new String[]{"", " ", "not-a-cursor", "a.b.c", ".", "@.@", "x".repeat(2049)}) {
            invalid(value, viewer, NOW);
        }
    }

    private void invalid(String token, UUID expectedViewer, Instant now) {
        assertThatThrownBy(() -> codec.decode(token, expectedViewer, now))
                .isInstanceOfSatisfying(SoundConnectException.class,
                        failure -> assertThat(failure.getErrorType()).isEqualTo(ErrorType.MUSICIAN_FEED_CURSOR_INVALID));
    }
}

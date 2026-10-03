package com.berkayb.soundconnect.modules.message.dm.service;

import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class DmConversationCursorTest {
    private final UUID owner = UUID.fromString("01234567-89ab-cdef-0123-456789abcdef");
    private final UUID conversation = UUID.fromString("abcdef01-2345-6789-abcd-ef0123456789");

    private String token(String payload) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }

    private void rejected(String value) {
        assertThatThrownBy(() -> DmConversationCursor.decode(owner, value))
                .isInstanceOfSatisfying(SoundConnectException.class, error -> {
                    assertThat(error.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
                    assertThat(error.getDetails()).isNull();
                    assertThat(error.getCause()).isNull();
                });
    }

    @Test void nullPositionDenotesInitialPageAndEmptyConversationRoundTrips() {
        assertThat(DmConversationCursor.decode(owner, null)).isNull();
        assertThat(DmConversationCursor.encode(owner, null)).isNull();
        var position = new DmConversationCursor.Cursor(null, conversation);
        assertThat(DmConversationCursor.decode(owner, DmConversationCursor.encode(owner, position))).isEqualTo(position);
    }

    @ParameterizedTest
    @ValueSource(strings={"2026-09-23T12:34", "2026-09-23T12:34:56.123456", "2026-09-23T12:34:56.123456789"})
    void timestampsPreserveExactPrecision(String value) {
        var position = new DmConversationCursor.Cursor(LocalDateTime.parse(value), conversation);
        String encoded = DmConversationCursor.encode(owner, position);
        assertThat(encoded).matches("[A-Za-z0-9_-]+").hasSizeLessThan(512);
        assertThat(DmConversationCursor.decode(owner, encoded)).isEqualTo(position);
    }

    @Test void anotherOwnerCannotReusePosition() {
        rejected(DmConversationCursor.encode(UUID.randomUUID(), new DmConversationCursor.Cursor(null, conversation)));
    }

    @ParameterizedTest
    @ValueSource(strings={"", " ", "\t", "not-base64!", "eA==", "A", "_", "MQ"})
    void malformedBlankPaddedOrNonCanonicalBase64IsRejected(String value) {
        rejected(value);
    }

    @Test void oversizedValueIsRejectedBeforeDecoding() {
        rejected("A".repeat(513));
    }

    @Test void versionShapeUuidAliasesAndInvalidTimeAreRejected() {
        for (String payload : new String[] {
                "2|" + owner + "||" + conversation,
                "1|" + owner + "||" + conversation + "|extra",
                "1|" + owner + "|",
                "1|" + owner + "||",
                "1|1-1-1-1-1||" + conversation,
                "1|" + owner + "||1-1-1-1-1",
                "1|" + owner.toString().toUpperCase() + "||" + conversation,
                "1|" + owner + "||" + conversation.toString().toUpperCase(),
                "1|" + owner + "|2026-02-30T12:00|" + conversation,
                "1|" + owner + "|2026-09-23T12:00Z|" + conversation,
                "1|" + owner + "|not-a-time|" + conversation,
                "1|" + owner + "|+10000-01-01T00:00|" + conversation,
                "1|" + owner + "|+999999999-01-01T00:00|" + conversation,
                "1|" + owner + "|0000-01-01T00:00|" + conversation,
                "1|" + owner + "|-0001-01-01T00:00|" + conversation
        }) rejected(token(payload));
    }

    @Test void absentOwnerAndConversationFailClosed() {
        assertThatThrownBy(() -> new DmConversationCursor.Cursor(null, null))
                .isInstanceOf(SoundConnectException.class);
        assertThatThrownBy(() -> DmConversationCursor.encode(null, new DmConversationCursor.Cursor(null, conversation)))
                .isInstanceOf(SoundConnectException.class);
        assertThatThrownBy(() -> DmConversationCursor.decode(null, token("1|" + owner + "||" + conversation)))
                .isInstanceOf(SoundConnectException.class);
    }
}

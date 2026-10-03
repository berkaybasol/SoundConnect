package com.berkayb.soundconnect.modules.message.dm.service;

import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.UUID;

/**
 * Versioned, owner-bound position in the conversation order, including empty
 * conversations whose last-message time sorts last. This is not an authorization
 * token: every page query must still filter conversations by its authenticated owner.
 */
public final class DmConversationCursor {
    private static final int MAX_LENGTH = 512;

    private DmConversationCursor() { }

    public record Cursor(LocalDateTime lastMessageAt, UUID conversationId) {
        public Cursor {
            if (conversationId == null) throw badRequest();
        }
    }

    public static String encode(UUID owner, Cursor cursor) {
        if (cursor == null) return null;
        if (owner == null) throw badRequest();
        String timestamp = cursor.lastMessageAt() == null ? "" : cursor.lastMessageAt().toString();
        String payload = "1|" + owner + "|" + timestamp + "|" + cursor.conversationId();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }

    public static Cursor decode(UUID owner, String value) {
        if (value == null) return null;
        if (owner == null || value.isBlank() || value.length() > MAX_LENGTH
                || !value.matches("[A-Za-z0-9_-]+")) throw badRequest();
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(value);
            // Reject alternate encodings with unused trailing bits or padding.
            if (!Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(value)) throw badRequest();
            String[] parts = new String(bytes, StandardCharsets.UTF_8).split("\\|", -1);
            if (parts.length != 4 || !"1".equals(parts[0]) || !canonicalUuid(parts[1]).equals(owner))
                throw badRequest();
            LocalDateTime timestamp = parts[2].isEmpty() ? null : LocalDateTime.parse(parts[2]);
            if (timestamp != null && (timestamp.getYear() < 1 || timestamp.getYear() > 9999
                    || !timestamp.toString().equals(parts[2]))) throw badRequest();
            return new Cursor(timestamp, canonicalUuid(parts[3]));
        } catch (RuntimeException invalid) {
            // Do not echo cursor contents, parser messages or source exceptions.
            throw badRequest();
        }
    }

    private static UUID canonicalUuid(String value) {
        UUID parsed = UUID.fromString(value);
        if (!parsed.toString().equals(value)) throw badRequest();
        return parsed;
    }

    private static SoundConnectException badRequest() {
        return new SoundConnectException(ErrorType.BAD_REQUEST);
    }
}

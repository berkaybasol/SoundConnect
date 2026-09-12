package com.berkayb.soundconnect.modules.like.service;

import com.berkayb.soundconnect.modules.engagement.enums.EngagementTargetType;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.UUID;

/** A bounded seek position, not an authorization token. Every page rechecks its target. */
public record LikeUsersCursor(LocalDateTime createdAt, UUID likeId) {
    private static final int MAX_LENGTH = 512;

    public String encode(EngagementTargetType type, UUID targetId) {
        String value = "1|" + type.name() + "|" + targetId + "|"
                + (createdAt == null ? "~" : createdAt) + "|" + likeId;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    public static LikeUsersCursor decode(String encoded, EngagementTargetType type, UUID targetId) {
        if (encoded == null) return null;
        try {
            if (encoded.isEmpty() || encoded.length() > MAX_LENGTH || !encoded.matches("[A-Za-z0-9_-]+")) {
                throw new IllegalArgumentException();
            }
            String[] parts = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8).split("\\|", -1);
            if (parts.length != 5 || !"1".equals(parts[0]) || !type.name().equals(parts[1])
                    || !targetId.toString().equals(parts[2])) throw new IllegalArgumentException();
            LocalDateTime createdAt = "~".equals(parts[3]) ? null : LocalDateTime.parse(parts[3]);
            if (createdAt != null && (createdAt.getYear() < 1 || createdAt.getYear() > 9999)) {
                throw new IllegalArgumentException();
            }
            LikeUsersCursor cursor = new LikeUsersCursor(createdAt, UUID.fromString(parts[4]));
            if (!cursor.encode(type, targetId).equals(encoded)) throw new IllegalArgumentException();
            return cursor;
        } catch (RuntimeException invalid) {
            throw new SoundConnectException(ErrorType.INVALID_PARAMETER);
        }
    }
}

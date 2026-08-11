package com.berkayb.soundconnect.modules.collab.support;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

public final class CollabPayloadHasher {
    private CollabPayloadHasher() {}

    public static String hash(Object... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Object value : values) append(digest, value);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void append(MessageDigest digest, Object value) {
        if (value instanceof Collection<?> collection) {
            appendBytes(digest, Integer.toString(collection.size()));
            collection.forEach(item -> append(digest, item));
            return;
        }
        appendBytes(digest, value == null ? null : value.toString());
    }

    private static void appendBytes(MessageDigest digest, String value) {
        byte[] bytes = value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }
}

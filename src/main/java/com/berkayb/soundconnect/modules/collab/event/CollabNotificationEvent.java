package com.berkayb.soundconnect.modules.collab.event;

import com.berkayb.soundconnect.modules.notification.enums.NotificationType;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable Collab notification snapshot captured inside the domain transaction.
 * The stable event id and payload are persisted to the outbox before commit;
 * broker delivery is attempted only after that transaction commits.
 */
public record CollabNotificationEvent(
        UUID eventId,
        UUID recipientId,
        NotificationType type,
        String title,
        String message,
        Map<String, Object> payload,
        Instant occurredAt
) {
    public static final String MODULE = "COLLAB";
    public static final String MODULE_KEY = "module";
    public static final String ACTION_KEY = "action";

    public CollabNotificationEvent {
        eventId = Objects.requireNonNull(eventId, "eventId is required");
        recipientId = Objects.requireNonNull(recipientId, "recipientId is required");
        type = requireCollabType(type);
        title = requireText(title, "title");
        message = requireText(message, "message");
        payload = immutablePayload(payload);
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt is required");

        if (!MODULE.equals(payload.get(MODULE_KEY))) {
            throw new IllegalArgumentException("payload.module must be COLLAB");
        }
        requireTextValue(payload.get(ACTION_KEY), "payload.action");
    }

    /**
     * Builds the canonical Collab payload while preventing callers from
     * accidentally overriding its routing keys.
     */
    public static CollabNotificationEvent create(
            UUID recipientId,
            NotificationType type,
            String title,
            String message,
            String action,
            Map<String, ?> attributes,
            Instant occurredAt
    ) {
        String normalizedAction = requireText(action, ACTION_KEY);
        Objects.requireNonNull(attributes, "attributes is required");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(MODULE_KEY, MODULE);
        payload.put(ACTION_KEY, normalizedAction);
        attributes.forEach((key, value) -> {
            String normalizedKey = requireText(key, "payload attribute key");
            if (MODULE_KEY.equals(normalizedKey) || ACTION_KEY.equals(normalizedKey)) {
                throw new IllegalArgumentException("payload routing keys cannot be overridden");
            }
            payload.put(normalizedKey, value);
        });

        return new CollabNotificationEvent(
                UUID.randomUUID(),
                recipientId,
                type,
                title,
                message,
                payload,
                occurredAt
        );
    }

    public static CollabNotificationEvent create(
            UUID recipientId,
            NotificationType type,
            String title,
            String message,
            String action,
            Instant occurredAt
    ) {
        return create(recipientId, type, title, message, action, Map.of(), occurredAt);
    }

    private static NotificationType requireCollabType(NotificationType type) {
        Objects.requireNonNull(type, "type is required");
        if (!MODULE.equals(type.getCategory())) {
            throw new IllegalArgumentException("type must belong to the COLLAB notification category");
        }
        return type;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static String requireTextValue(Object value, String field) {
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException(field + " must be a string");
        }
        return requireText(text, field);
    }

    private static Map<String, Object> immutablePayload(Map<String, Object> source) {
        Objects.requireNonNull(source, "payload is required");
        Map<String, Object> snapshot = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            String normalizedKey = requireText(key, "payload key");
            snapshot.put(normalizedKey, immutableValue(value, "payload." + normalizedKey));
        });
        return Collections.unmodifiableMap(snapshot);
    }

    private static Object immutableValue(Object value, String path) {
        if (value == null) {
            throw new IllegalArgumentException(path + " must not be null");
        }
        if (value instanceof String
                || value instanceof Boolean
                || value instanceof Byte
                || value instanceof Short
                || value instanceof Integer
                || value instanceof Long
                || value instanceof Float
                || value instanceof Double
                || value instanceof BigInteger
                || value instanceof BigDecimal
                || value instanceof UUID
                || value instanceof Instant) {
            return value;
        }
        if (value instanceof Enum<?> enumValue) {
            return enumValue.name();
        }
        if (value instanceof Map<?, ?> mapValue) {
            Map<String, Object> snapshot = new LinkedHashMap<>();
            mapValue.forEach((key, nestedValue) -> {
                if (!(key instanceof String stringKey) || stringKey.isBlank()) {
                    throw new IllegalArgumentException(path + " map keys must be non-blank strings");
                }
                snapshot.put(stringKey, immutableValue(nestedValue, path + "." + stringKey));
            });
            return Collections.unmodifiableMap(snapshot);
        }
        if (value instanceof Collection<?> collectionValue) {
            List<Object> snapshot = new ArrayList<>(collectionValue.size());
            int index = 0;
            for (Object nestedValue : collectionValue) {
                snapshot.add(immutableValue(nestedValue, path + "[" + index++ + "]"));
            }
            return Collections.unmodifiableList(snapshot);
        }
        if (value.getClass().isArray()) {
            if (!(value instanceof Object[] arrayValue)) {
                throw new IllegalArgumentException(path + " contains an unsupported primitive array");
            }
            return immutableValue(List.of(arrayValue), path);
        }

        throw new IllegalArgumentException(
                path + " contains unsupported value type " + value.getClass().getSimpleName()
        );
    }
}

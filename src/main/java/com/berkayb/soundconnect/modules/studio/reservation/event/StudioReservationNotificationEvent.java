package com.berkayb.soundconnect.modules.studio.reservation.event;

import com.berkayb.soundconnect.modules.notification.enums.NotificationType;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Immutable snapshot captured and durably enqueued in the reservation transaction. */
public record StudioReservationNotificationEvent(
        UUID recipientId,
        NotificationType type,
        String title,
        String message,
        Map<String, Object> payload,
        Instant occurredAt
) {
    private static final Set<String> PAYLOAD_KEYS = Set.of(
            "module", "action", "reservationId", "roomId", "roomName", "studioProfileId",
            "studioName", "zoneId", "localDate", "requesterId", "status", "startsAt", "endsAt");

    public StudioReservationNotificationEvent {
        Objects.requireNonNull(recipientId, "recipientId is required");
        Objects.requireNonNull(type, "type is required");
        requireText(title, "title", 160);
        requireText(message, "message", 1000);
        Objects.requireNonNull(occurredAt, "occurredAt is required");
        payload = immutablePayload(payload);
        if (!"STUDIO".equals(payload.get("module"))) {
            throw new IllegalArgumentException("payload.module must be STUDIO");
        }
        String action = (String) payload.get("action");
        boolean validAction = switch (type) {
            case STUDIO_RESERVATION_CREATED -> "CREATED".equals(action);
            case STUDIO_RESERVATION_CONFLICTING_REQUESTS -> "CONFLICTING_REQUESTS".equals(action);
            case STUDIO_RESERVATION_APPROVED -> "APPROVED".equals(action);
            case STUDIO_RESERVATION_REJECTED -> "REJECTED".equals(action) || "AUTO_REJECTED_CONFLICT".equals(action);
            case STUDIO_RESERVATION_CANCELLED_BY_STUDIO -> "CANCELLED_BY_STUDIO".equals(action)
                    || "CANCELLED_BY_STUDIO_ROOM_ARCHIVED".equals(action);
            case STUDIO_RESERVATION_CANCELLED_BY_CUSTOMER -> "CANCELLED_BY_CUSTOMER".equals(action);
            default -> false;
        };
        if (!validAction) throw new IllegalArgumentException("Unsupported studio reservation type/action");
        reservationId(payload);
        for (String key : Set.of("roomId", "studioProfileId", "requesterId")) {
            if (payload.containsKey(key)) requireUuid((String) payload.get(key));
        }
    }

    /** Each current reservation transition is emitted at most once per recipient. */
    public UUID eventId() {
        String identity = "studio-reservation-notification:v1:" + reservationId(payload) + ":"
                + recipientId + ":" + type.name() + ":" + payload.get("action");
        return UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
    }

    // Record's generated toString would expose the body and reservation details.
    @Override public String toString() {
        return "StudioReservationNotificationEvent[eventId=" + eventId() + ", type=" + type + "]";
    }

    private static UUID reservationId(Map<String, Object> payload) {
        Object value = payload.get("reservationId");
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("payload.reservationId is required");
        }
        try {
            UUID id = UUID.fromString(text);
            if (!id.toString().equalsIgnoreCase(text)) throw new IllegalArgumentException();
            return id;
        } catch (IllegalArgumentException exception) {
            // UUID parser messages can contain user-supplied values.
            throw new IllegalArgumentException("payload.reservationId must be a UUID");
        }
    }

    private static void requireUuid(String value) {
        try {
            if (!UUID.fromString(value).toString().equalsIgnoreCase(value)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Studio reservation payload identity must be a UUID");
        }
    }

    private static Map<String, Object> immutablePayload(Map<String, Object> source) {
        Objects.requireNonNull(source, "payload is required");
        Map<String, Object> snapshot = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key == null || !PAYLOAD_KEYS.contains(key)) {
                throw new IllegalArgumentException("Unsupported studio reservation payload field");
            }
            // Existing domain payload is flat text. Reject nested mutable data and
            // additional private fields (including customer contact information).
            if (!(value instanceof String text) || text.isBlank()) {
                throw new IllegalArgumentException("Studio reservation payload values must be non-blank text");
            }
            snapshot.put(key, text);
        });
        return Collections.unmodifiableMap(snapshot);
    }

    private static void requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(field + " must be non-blank and within its length limit");
        }
    }
}
package com.berkayb.soundconnect.modules.studio.reservation.event;

import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class StudioReservationNotificationEventTest {
    private static final UUID RECIPIENT = UUID.randomUUID();
    private static final UUID RESERVATION = UUID.randomUUID();
    private static final Instant OCCURRED = Instant.parse("2026-09-24T09:00:00Z");

    @Test void replayIdentitySurvivesContentTimeAndMapOrderChanges() {
        var first = event(RECIPIENT, NotificationType.STUDIO_RESERVATION_CREATED, payload(), "original", OCCURRED);
        var reordered = new LinkedHashMap<String,Object>();
        reordered.put("reservationId", RESERVATION.toString());
        reordered.put("action", "CREATED");
        reordered.put("module", "STUDIO");
        var replay = event(RECIPIENT, NotificationType.STUDIO_RESERVATION_CREATED, reordered, "renamed", OCCURRED.plusSeconds(60));
        assertThat(replay.eventId()).isEqualTo(first.eventId());
        assertThat(event(UUID.randomUUID(), NotificationType.STUDIO_RESERVATION_CREATED, payload(), "original", OCCURRED).eventId())
                .isNotEqualTo(first.eventId());
        var approved = new LinkedHashMap<>(payload());
        approved.put("action", "APPROVED");
        assertThat(event(RECIPIENT, NotificationType.STUDIO_RESERVATION_APPROVED, approved, "original", OCCURRED).eventId())
                .isNotEqualTo(first.eventId());
    }

    @Test void callerCannotMutateTheDurableSnapshotAfterPublishing() {
        var mutable = new LinkedHashMap<>(payload());
        mutable.put("roomName", "private-room");
        var event = event(RECIPIENT, NotificationType.STUDIO_RESERVATION_CREATED, mutable, "private-title", OCCURRED);
        mutable.put("roomName", "changed");
        assertThat(event.payload()).containsEntry("roomName", "private-room");
        assertThatThrownBy(() -> event.payload().put("roomName", "changed")).isInstanceOf(UnsupportedOperationException.class);
        assertThat(event.toString()).contains(event.eventId().toString())
                .doesNotContain("private-room", "private-title", "private-body", RECIPIENT.toString());
    }

    @Test void extraContactFieldsNestedValuesAndTypeActionMismatchAreRejectedWithoutPrivateValues() {
        var extra = new LinkedHashMap<>(payload());
        extra.put("phone", "private-contact");
        assertThatThrownBy(() -> event(RECIPIENT, NotificationType.STUDIO_RESERVATION_CREATED, extra, "title", OCCURRED))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("private-contact");
        var nested = new LinkedHashMap<>(payload());
        nested.put("roomName", Map.of("secret", "private-contact"));
        assertThatThrownBy(() -> event(RECIPIENT, NotificationType.STUDIO_RESERVATION_CREATED, nested, "title", OCCURRED))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("private-contact");
        assertThatThrownBy(() -> event(RECIPIENT, NotificationType.STUDIO_RESERVATION_APPROVED, payload(), "title", OCCURRED))
                .isInstanceOf(IllegalArgumentException.class);
        var invalid = new LinkedHashMap<>(payload());
        invalid.put("reservationId", "private-malformed-reservation");
        assertThatThrownBy(() -> event(RECIPIENT, NotificationType.STUDIO_RESERVATION_CREATED, invalid, "title", OCCURRED))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("private-malformed-reservation");
    }

    private static Map<String,Object> payload() {
        return Map.of("module", "STUDIO", "action", "CREATED", "reservationId", RESERVATION.toString());
    }
    private static StudioReservationNotificationEvent event(UUID recipient, NotificationType type,
            Map<String,Object> payload, String title, Instant occurred) {
        return new StudioReservationNotificationEvent(recipient, type, title, "private-body", payload, occurred);
    }
}

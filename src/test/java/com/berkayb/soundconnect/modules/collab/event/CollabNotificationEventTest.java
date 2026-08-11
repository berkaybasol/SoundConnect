package com.berkayb.soundconnect.modules.collab.event;

import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CollabNotificationEventTest {

    @Test
    void createCapturesADeeplyImmutablePayloadSnapshot() {
        UUID listingId = UUID.randomUUID();
        List<String> actorLabels = new ArrayList<>(List.of("Muzisyen"));
        Map<String, Object> actor = new HashMap<>();
        actor.put("labels", actorLabels);
        actor.put("displayName", "Sahne Ekibi");
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("listingId", listingId);
        attributes.put("actor", actor);

        CollabNotificationEvent event = CollabNotificationEvent.create(
                UUID.randomUUID(),
                NotificationType.COLLAB_APPLICATION_RECEIVED,
                "Yeni basvuru",
                "Ilanina yeni bir basvuru geldi.",
                "APPLICATION_RECEIVED",
                attributes,
                Instant.parse("2026-08-11T00:00:00Z")
        );

        attributes.put("listingId", UUID.randomUUID());
        actor.put("displayName", "Degistirildi");
        actorLabels.add("Yeni etiket");

        assertThat(event.payload())
                .containsEntry("module", "COLLAB")
                .containsEntry("action", "APPLICATION_RECEIVED")
                .containsEntry("listingId", listingId);
        assertThat(event.payload().get("actor"))
                .isEqualTo(Map.of("displayName", "Sahne Ekibi", "labels", List.of("Muzisyen")));
        assertThatThrownBy(() -> event.payload().put("jobId", UUID.randomUUID()))
                .isInstanceOf(UnsupportedOperationException.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> frozenActor = (Map<String, Object>) event.payload().get("actor");
        assertThatThrownBy(() -> frozenActor.put("displayName", "Degistirilemez"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void invalidRoutingPayloadFailsFast() {
        assertThatThrownBy(() -> new CollabNotificationEvent(
                UUID.randomUUID(),
                NotificationType.COLLAB_APPLICATION_ACCEPTED,
                "Basvuru kabul edildi",
                "Basvurun kabul edildi.",
                Map.of("module", "COLLAB"),
                Instant.now()
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("payload.action");

        assertThatThrownBy(() -> CollabNotificationEvent.create(
                UUID.randomUUID(),
                NotificationType.DM_NEW_MESSAGE,
                "Yeni mesaj",
                "Yeni mesaj aldin.",
                "APPLICATION_RECEIVED",
                Instant.now()
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("COLLAB notification category");
    }
}

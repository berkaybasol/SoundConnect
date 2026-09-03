package com.berkayb.soundconnect.modules.studio.reservation.dto.response;

import com.berkayb.soundconnect.modules.profile.ListenerProfile.enums.ListenerVisibilityMode;
import com.berkayb.soundconnect.modules.studio.reservation.enums.StudioReservationStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class StudioReservationOwnerResponseContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void requesterVisibilityMarkerIsSerializedOnlyForGhostListeners() throws Exception {
        StudioReservationOwnerResponse standard = response(ListenerVisibilityMode.STANDARD);
        StudioReservationOwnerResponse ghost = response(ListenerVisibilityMode.GHOST);

        assertThat(standard.requesterVisibilityMode()).isNull();
        assertThat(objectMapper.writeValueAsString(standard))
                .doesNotContain("requesterVisibilityMode");

        JsonNode ghostJson = objectMapper.readTree(objectMapper.writeValueAsBytes(ghost));
        assertThat(ghostJson.path("requesterVisibilityMode").asText()).isEqualTo("GHOST");
    }

    private StudioReservationOwnerResponse response(ListenerVisibilityMode visibilityMode) {
        return new StudioReservationOwnerResponse(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "public-code",
                "05551112233",
                "listener",
                "https://cdn.example/listener.jpg",
                null,
                null,
                "Europe/Istanbul",
                null,
                null,
                null,
                StudioReservationStatus.PENDING_APPROVAL,
                false,
                true,
                1_000L,
                2_000L,
                "TRY",
                0L,
                visibilityMode
        );
    }
}

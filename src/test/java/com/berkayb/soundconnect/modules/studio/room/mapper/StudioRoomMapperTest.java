package com.berkayb.soundconnect.modules.studio.room.mapper;

import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.profile.StudioProfile.entity.StudioProfile;
import com.berkayb.soundconnect.modules.studio.room.entity.StudioRoom;
import com.berkayb.soundconnect.modules.studio.room.enums.StudioRoomAvailabilityStatus;
import com.berkayb.soundconnect.modules.studio.room.service.StudioRoomDailyMetrics;
import com.berkayb.soundconnect.modules.studio.reservation.support.StudioReservationTimeProvider;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StudioRoomMapperTest {

    private final StudioReservationTimeProvider timeProvider =
            mock(StudioReservationTimeProvider.class);
    private final StudioRoomMapper mapper =
            new StudioRoomMapper(mock(MediaAssetService.class), timeProvider);

    @Test
    void ownerResponseCarriesAuthoritativeDailyAvailabilityMetrics() {
        when(timeProvider.now()).thenReturn(Instant.parse("2026-07-21T09:00:00Z"));
        UUID profileId = UUID.randomUUID();
        StudioRoom room = StudioRoom.builder()
                .id(UUID.randomUUID())
                .studioProfile(StudioProfile.builder().id(profileId).build())
                .clientRequestId(UUID.randomUUID())
                .creationPayloadHash("a".repeat(64))
                .name("Prova Odasi")
                .capacity(4)
                .currency("TRY")
                .build();
        StudioRoomDailyMetrics metrics = new StudioRoomDailyMetrics(
                LocalDate.of(2026, 7, 21),
                2,
                6,
                8,
                StudioRoomAvailabilityStatus.PARTIALLY_AVAILABLE
        );

        var response = mapper.toOwner(room, metrics);

        assertThat(response.todayReservationCount()).isEqualTo(2);
        assertThat(response.todayOccupiedHours()).isEqualTo(6);
        assertThat(response.todayAvailableHours()).isEqualTo(8);
        assertThat(response.todayAvailabilityStatus())
                .isEqualTo(StudioRoomAvailabilityStatus.PARTIALLY_AVAILABLE);
    }
}

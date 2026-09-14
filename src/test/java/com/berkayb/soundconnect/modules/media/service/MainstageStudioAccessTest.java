package com.berkayb.soundconnect.modules.media.service;

import com.berkayb.soundconnect.modules.profile.StudioProfile.service.StudioProfileServiceImpl;
import com.berkayb.soundconnect.modules.profile.shared.media.enums.ProfileType;
import com.berkayb.soundconnect.modules.profile.shared.media.service.ProfileMediaUiServiceImpl;
import com.berkayb.soundconnect.modules.studio.room.service.StudioRoomService;
import com.berkayb.soundconnect.modules.studio.equipment.service.StudioEquipmentService;
import com.berkayb.soundconnect.modules.studio.reservation.service.StudioReservationService;
import com.berkayb.soundconnect.modules.track.enums.TrackOwnerType;
import com.berkayb.soundconnect.modules.track.service.TrackServiceImpl;
import com.berkayb.soundconnect.modules.promotion.enums.PromotionPlacement;
import com.berkayb.soundconnect.modules.promotion.service.PromotionServiceImpl;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MainstageStudioAccessTest {
    final UUID studio = UUID.randomUUID(), resource = UUID.randomUUID();
    final LocalDate day = LocalDate.of(2026, 9, 14);

    @BeforeEach void listener() { MediaContentAudienceServiceTest.viewer("ROLE_LISTENER"); }
    @AfterEach void clearViewer() { SecurityContextHolder.clearContext(); }

    @Test void studioProfileAndProfileMediaDenyListenerBeforeReadingData() {
        var profiles = mock(StudioProfileServiceImpl.class, CALLS_REAL_METHODS);
        var media = mock(ProfileMediaUiServiceImpl.class, CALLS_REAL_METHODS);
        assertThatThrownBy(() -> profiles.getProfileByProfileId(studio)).isInstanceOf(SoundConnectException.class);
        assertThatThrownBy(() -> media.getProfileMedia(ProfileType.STUDIO, studio)).isInstanceOf(SoundConnectException.class);
    }

    @Test void studioRoomDetailsAndAvailabilityDenyListenerBeforeReadingData() {
        var rooms = mock(StudioRoomService.class, CALLS_REAL_METHODS);
        var reservations = mock(StudioReservationService.class, CALLS_REAL_METHODS);
        assertThatThrownBy(() -> rooms.listPublic(studio, 0, 20)).isInstanceOf(SoundConnectException.class);
        assertThatThrownBy(() -> rooms.getPublic(studio, resource)).isInstanceOf(SoundConnectException.class);
        assertThatThrownBy(() -> reservations.publicAvailability(studio, resource, day, day.plusDays(1)))
                .isInstanceOf(SoundConnectException.class);
    }

    @Test void equipmentDetailsAndAvailabilityDenyListenerBeforeReadingData() {
        var equipment = mock(StudioEquipmentService.class, CALLS_REAL_METHODS);
        assertThatThrownBy(() -> equipment.listPublic(studio, null, null, null, 0, 20))
                .isInstanceOf(SoundConnectException.class);
        assertThatThrownBy(() -> equipment.getPublic(studio, resource)).isInstanceOf(SoundConnectException.class);
        assertThatThrownBy(() -> equipment.getPublicAvailability(studio, resource, day, day.plusDays(1)))
                .isInstanceOf(SoundConnectException.class);
    }

    @Test void studioTrackListsDenyListenerBeforeReadingData() {
        var tracks = mock(TrackServiceImpl.class, CALLS_REAL_METHODS);
        assertThatThrownBy(() -> tracks.getTracksByOwner(studio, TrackOwnerType.STUDIO_PROFILE))
                .isInstanceOf(SoundConnectException.class);
        assertThatThrownBy(() -> tracks.listTracks(studio, TrackOwnerType.STUDIO_PROFILE, PageRequest.of(0, 20)))
                .isInstanceOf(SoundConnectException.class);
    }

    @Test void listenerNeverReceivesLegacyManagementPromotionsOrTheirUnclassifiedRedirects() {
        var promotions = mock(PromotionServiceImpl.class, CALLS_REAL_METHODS);
        assertThat(promotions.getDisplayableByPlacement(PromotionPlacement.VENUE_MANAGEMENT_PANEL)).isEmpty();
    }
}

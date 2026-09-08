package com.berkayb.soundconnect.modules.event.discovery;

import com.berkayb.soundconnect.modules.event.entity.Event;
import com.berkayb.soundconnect.modules.event.mapper.EventMapper;
import com.berkayb.soundconnect.modules.event.enums.EventPerformerApprovalStatus;
import com.berkayb.soundconnect.modules.event.enums.PerformerType;
import com.berkayb.soundconnect.modules.event.support.EventScheduleClock;
import com.berkayb.soundconnect.modules.event.support.EventShareUrlBuilder;
import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.location.entity.District;
import com.berkayb.soundconnect.modules.location.entity.Neighborhood;
import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.venue.entity.Venue;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class EventDiscoveryServiceTest {
    EventDiscoveryRepository repository;
    MediaAssetService media;
    EventDiscoveryService service;
    EventShareUrlBuilder shareUrls = new EventShareUrlBuilder("https://soundconnect.test");
    LocalDate today = LocalDate.of(2026, 9, 8);
    UUID city = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repository = mock(EventDiscoveryRepository.class);
        media = mock(MediaAssetService.class);
        // Still September 7 in UTC, already September 8 in Turkey.
        var clock = new EventScheduleClock() {
            @Override public Instant instant() { return Instant.parse("2026-09-07T22:30:00Z"); }
        };
        service = new EventDiscoveryService(repository, clock, media, shareUrls);
    }

    @Test
    void omittedDateUsesIstanbulDayAndEmptyPageSkipsDecorationQueries() {
        var pageable = PageRequest.of(0, 20);
        when(repository.findEvents(today, city, null, null, pageable)).thenReturn(new PageImpl<>(List.of(), pageable, 0));
        var result = service.discover(null, city, null, null, 0, 20);
        assertThat(result.number()).isZero();
        assertThat(result.totalElements()).isZero();
        assertThat(result.totalPages()).isZero();
        assertThat(result.last()).isTrue();
        verify(repository).findEvents(today, city, null, null, pageable);
        verifyNoMoreInteractions(repository);
        verifyNoInteractions(media);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 7, 365})
    void datesOutsideSevenCalendarDaysAreRejected(int offset) {
        assertThatThrownBy(() -> service.discover(today.plusDays(offset), city, null, null, 0, 20))
                .isInstanceOf(SoundConnectException.class);
        verifyNoInteractions(repository, media);
    }

    @ParameterizedTest
    @CsvSource({"-1,20", "1001,20", "0,0", "0,51", "2147483647,2147483647"})
    void invalidPagingIsRejectedBeforeRepository(int page, int size) {
        assertThatThrownBy(() -> service.discover(today, city, null, null, page, size))
                .isInstanceOf(SoundConnectException.class);
        verifyNoInteractions(repository, media);
    }

    @Test
    void cityAndNeighborhoodAncestorAreRequired() {
        assertThatThrownBy(() -> service.discover(today, null, null, null, 0, 20))
                .isInstanceOf(SoundConnectException.class);
        assertThatThrownBy(() -> service.discover(today, city, null, UUID.randomUUID(), 0, 20))
                .isInstanceOf(SoundConnectException.class);
        verifyNoInteractions(repository, media);
    }

    @Test
    void lastAllowedDayAndBoundsAreAcceptedWithAllLocationFilters() {
        UUID district = UUID.randomUUID(), neighborhood = UUID.randomUUID();
        var pageable = PageRequest.of(1000, 50);
        when(repository.findEvents(today.plusDays(6), city, district, neighborhood, pageable))
                .thenReturn(new PageImpl<>(List.of(), pageable, 17));
        var result = service.discover(today.plusDays(6), city, district, neighborhood, 1000, 50);
        assertThat(result.number()).isEqualTo(1000);
        assertThat(result.size()).isEqualTo(50);
        assertThat(result.totalElements()).isEqualTo(17);
        assertThat(result.last()).isTrue();
    }

    @Test
    void postersAreBatchedBandMembersAreNotExpandedAndConsentSafeLinksRemainIntact() {
        UUID bandId = UUID.randomUUID(), assetId = UUID.randomUUID(), missingAsset = UUID.randomUUID();
        var pending = row(null, null, "Şahbaz", assetId.toString());
        var band = row(null, bandId, null, assetId.toString());
        var unavailablePoster = row(null, null, null, missingAsset.toString());
        var legacyPoster = row(null, null, null, "https://cdn.test/legacy.png");
        var pageable = PageRequest.of(0, 4);
        when(repository.findEvents(today, city, null, null, pageable))
                .thenReturn(new PageImpl<>(List.of(pending, band, unavailablePoster, legacyPoster), pageable, 9));
        when(media.getDisplayUrlMap(List.of(assetId, missingAsset))).thenReturn(Map.of(assetId, "https://cdn.test/poster.png"));
        var result = service.discover(today, city, null, null, 0, 4);
        assertThat(result.last()).isFalse();
        assertThat(result.totalPages()).isEqualTo(3);
        assertThat(result.content().getFirst().performerName()).isEqualTo("Şahbaz");
        assertThat(result.content().getFirst().performerType()).isEqualTo(PerformerType.MANUAL);
        assertThat(result.content().getFirst().musicianProfileId()).isNull();
        assertThat(result.content().getFirst().bandId()).isNull();
        assertThat(result.content().getFirst().posterImage()).isEqualTo("https://cdn.test/poster.png");
        assertThat(result.content().get(1).bandMembers()).isEmpty();
        assertThat(result.content().get(1).bandId()).isEqualTo(bandId);
        assertThat(result.content().get(1).performerName()).isEqualTo("Şahbaz");
        assertThat(result.content().get(1).performerType()).isEqualTo(PerformerType.BAND);
        assertThat(result.content().get(2).posterImage()).isNull();
        assertThat(result.content().get(2).performerName()).isEqualTo("Belirtilmemiş");
        assertThat(result.content().get(3).posterImage()).isEqualTo("https://cdn.test/legacy.png");
        verify(media).getDisplayUrlMap(List.of(assetId, missingAsset));
        verifyNoMoreInteractions(media);
        verify(repository).findEvents(today, city, null, null, pageable);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void approvedMusicianKeepsItsPublicIdentityWithoutBandDecoration() {
        UUID musicianId = UUID.randomUUID();
        var row = row(musicianId, null, null, null);
        var pageable = PageRequest.of(0, 20);
        when(repository.findEvents(today, city, null, null, pageable))
                .thenReturn(new PageImpl<>(List.of(row), pageable, 1));
        var result = service.discover(today, city, null, null, 0, 20).content().getFirst();
        assertThat(result.performerName()).isEqualTo("bugrasahin");
        assertThat(result.performerType()).isEqualTo(PerformerType.MUSICIAN);
        assertThat(result.musicianProfileId()).isEqualTo(musicianId);
        assertThat(result.bandId()).isNull();
        assertThat(result.bandMembers()).isEmpty();
        verify(repository).findEvents(today, city, null, null, pageable);
        verifyNoMoreInteractions(repository);
        verifyNoInteractions(media);
    }

    @Test
    void manualReadModelMatchesExistingPublicEventMapperContract() {
        var row = row(null, null, "Bekleyen sanatçı", null);
        var pageable = PageRequest.of(0, 20);
        when(repository.findEvents(today, city, null, null, pageable))
                .thenReturn(new PageImpl<>(List.of(row), pageable, 1));
        var venue = Venue.builder().name(row.venueName())
                .city(City.builder().name(row.venueCity()).build())
                .district(District.builder().name(row.venueDistrict()).build())
                .neighborhood(Neighborhood.builder().name(row.venueNeighborhood()).build()).build();
        venue.setId(row.venueId());
        var event = Event.builder().title(row.title()).venue(venue).eventDate(row.eventDate())
                .startTime(row.startTime()).endTime(row.endTime()).description(row.description())
                .performerApprovalStatus(EventPerformerApprovalStatus.PENDING)
                .manualPerformerName(row.manualPerformerName()).build();
        event.setId(row.id());
        assertThat(service.discover(today, city, null, null, 0, 20).content().getFirst())
                .isEqualTo(new EventMapper(media, shareUrls).toDto(event));
    }

    private EventDiscoveryRow row(UUID musicianId, UUID bandId, String manual, String poster) {
        return new EventDiscoveryRow(UUID.randomUUID(), "Test", poster, musicianId,
                musicianId == null ? null : "bugrasahin", null, bandId,
                bandId == null ? null : "Şahbaz", manual, UUID.randomUUID(), "soundconnectankara",
                "Ankara", "Çankaya", "Çayyolu", today, LocalTime.of(20, 0), LocalTime.of(22, 0), "Açıklama");
    }
}

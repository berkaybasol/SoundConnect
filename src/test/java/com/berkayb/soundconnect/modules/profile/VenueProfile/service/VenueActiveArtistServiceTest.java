package com.berkayb.soundconnect.modules.profile.VenueProfile.service;

import com.berkayb.soundconnect.modules.profile.VenueProfile.enums.VenueActiveArtistType;
import com.berkayb.soundconnect.modules.profile.VenueProfile.repository.VenueActiveArtistRepository;
import com.berkayb.soundconnect.modules.profile.VenueProfile.repository.VenueActiveArtistRow;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class VenueActiveArtistServiceTest {
    VenueActiveArtistRepository repository;
    VenueActiveArtistService service;
    UUID venueId;

    @BeforeEach
    void setUp() {
        repository = mock(VenueActiveArtistRepository.class);
        service = new VenueActiveArtistService(repository);
        venueId = UUID.randomUUID();
    }

    @ParameterizedTest
    @EnumSource(VenueActiveArtistType.class)
    void queriesOnlySelectedTypeAndPreservesBoundedPageMetadata(VenueActiveArtistType type) {
        when(repository.existsVenueProfile(venueId)).thenReturn(true);
        var pageable = PageRequest.of(1, 2);
        var row = new VenueActiveArtistRow(UUID.randomUUID(), " Şahbaz ", "https://cdn.test/avatar");
        var page = new PageImpl<>(List.of(row), pageable, 3);
        if (type == VenueActiveArtistType.BAND) {
            when(repository.findBands(venueId, "şah", pageable)).thenReturn(page);
        } else {
            when(repository.findMusicians(venueId, "şah", pageable)).thenReturn(page);
        }
        var result = service.list(venueId, type, "  şah  ", 1, 2);
        assertThat(result.page()).isEqualTo(1);
        assertThat(result.size()).isEqualTo(2);
        assertThat(result.totalElements()).isEqualTo(3);
        assertThat(result.totalPages()).isEqualTo(2);
        assertThat(result.last()).isTrue();
        assertThat(result.content()).singleElement().satisfies(item -> {
            assertThat(item.id()).isEqualTo(row.id());
            assertThat(item.name()).isEqualTo("Şahbaz");
            assertThat(item.type()).isEqualTo(type);
            assertThat(item.profilePictureUrl()).isEqualTo(row.profilePictureUrl());
        });
        if (type == VenueActiveArtistType.BAND) {
            verify(repository, never()).findMusicians(any(), any(), any());
        } else {
            verify(repository, never()).findBands(any(), any(), any());
        }
    }

    @ParameterizedTest
    @CsvSource({"-1,20", "10001,20", "0,0", "0,51", "0,-1"})
    void invalidBoundsNeverReachDatabase(int page, int size) {
        assertInvalid(() -> service.list(venueId, VenueActiveArtistType.MUSICIAN, "", page, size));
        verifyNoInteractions(repository);
    }

    @Test
    void eachHttpReadUsesAConsistentReadOnlySnapshotForRowsAndCount() {
        var transaction = VenueActiveArtistService.class.getAnnotation(Transactional.class);
        assertThat(transaction).isNotNull();
        assertThat(transaction.readOnly()).isTrue();
        assertThat(transaction.isolation()).isEqualTo(Isolation.REPEATABLE_READ);
    }

    @Test
    void nullIdentityOrTypeAndOverlongQueryNeverReachDatabase() {
        assertInvalid(() -> service.list(null, VenueActiveArtistType.MUSICIAN, "", 0, 20));
        assertInvalid(() -> service.list(venueId, null, "", 0, 20));
        assertInvalid(() -> service.list(venueId, VenueActiveArtistType.BAND, "x".repeat(101), 0, 20));
        verifyNoInteractions(repository);
    }

    @Test
    void missingVenueIsNotReportedAsAnEmptyDirectory() {
        assertThatThrownBy(() -> service.list(venueId, VenueActiveArtistType.MUSICIAN, "", 0, 20))
                .isInstanceOfSatisfying(SoundConnectException.class,
                        error -> assertThat(error.getErrorType()).isEqualTo(ErrorType.VENUE_NOT_FOUND));
        verify(repository).existsVenueProfile(venueId);
        verifyNoMoreInteractions(repository);
    }

    @ParameterizedTest
    @EnumSource(VenueActiveArtistType.class)
    void legacyBlankNamesAndNullAvatarsHaveSafePublicFallbacks(VenueActiveArtistType type) {
        when(repository.existsVenueProfile(venueId)).thenReturn(true);
        var pageable = PageRequest.of(0, 20);
        var page = new PageImpl<>(List.of(new VenueActiveArtistRow(UUID.randomUUID(), " ", null)), pageable, 1);
        if (type == VenueActiveArtistType.BAND) {
            when(repository.findBands(venueId, "", pageable)).thenReturn(page);
        } else {
            when(repository.findMusicians(venueId, "", pageable)).thenReturn(page);
        }
        assertThat(service.list(venueId, type, null, 0, 20).content()).singleElement().satisfies(item -> {
            assertThat(item.name()).isEqualTo(type == VenueActiveArtistType.BAND ? "Grup" : "Sanatçı");
            assertThat(item.profilePictureUrl()).isNull();
        });
    }

    private void assertInvalid(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(SoundConnectException.class,
                error -> assertThat(error.getErrorType()).isEqualTo(ErrorType.VALIDATION_ERROR));
    }
}

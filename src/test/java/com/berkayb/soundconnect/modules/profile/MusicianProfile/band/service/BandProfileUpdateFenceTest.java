package com.berkayb.soundconnect.modules.profile.MusicianProfile.band.service;

import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.dto.request.BandCreateRequestDto;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity.Band;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity.BandMember;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.enums.BandMemberShipStatus;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.enums.BandRole;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.mapper.BandMapper;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.repository.BandMemberRepository;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.repository.BandRepository;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.support.BandEntityFinder;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BandProfileUpdateFenceTest {
    @Mock BandRepository bands;
    @Mock BandMemberRepository members;
    @Mock BandEntityFinder finder;
    @Spy BandMapper mapper = Mappers.getMapper(BandMapper.class);
    @InjectMocks BandServiceImpl service;

    UUID bandId = UUID.randomUUID(), founderId = UUID.randomUUID();
    BandCreateRequestDto edit = new BandCreateRequestDto("Updated band", "Bio", null,
            null, null, null, null, null, List.of());

    @Test
    void profileUpdateLoadsMembershipAndChangesTheBandOnlyAfterTheSharedWriteFence() {
        Band band = Band.builder().id(bandId).name("Old band").build();
        BandMember founder = BandMember.builder().band(band).bandRole(BandRole.FOUNDER)
                .status(BandMemberShipStatus.ACTIVE).build();
        when(bands.findByIdForUpdate(bandId)).thenReturn(Optional.of(band));
        when(members.findByBandIdAndUserId(bandId, founderId)).thenReturn(Optional.of(founder));
        when(bands.save(band)).thenReturn(band);

        var updated = service.updateBand(bandId, founderId, edit);

        assertThat(updated.name()).isEqualTo("Updated band");
        var order = inOrder(bands, members);
        order.verify(bands).findByIdForUpdate(bandId);
        order.verify(members).findByBandIdAndUserId(bandId, founderId);
        order.verify(bands).findByName("Updated band");
        order.verify(bands).save(band);
        verifyNoInteractions(finder);
    }

    @Test
    void alreadyDeletedBandFailsBeforeMembershipReadOrAnyProfileWrite() {
        assertThatThrownBy(() -> service.updateBand(bandId, founderId, edit))
                .isInstanceOfSatisfying(SoundConnectException.class, exception ->
                        assertThat(exception.getErrorType()).isEqualTo(ErrorType.BAND_NOT_FOUND));

        verify(bands).findByIdForUpdate(bandId);
        verifyNoMoreInteractions(bands);
        verifyNoInteractions(members, finder, mapper);
    }

    @Test
    void caseOnlyRenameIsPersistedAndOptionalFieldsCanBeCleared() {
        Band band = Band.builder().id(bandId).name("the band").description("Old bio")
                .instagramUrl("https://instagram.com/band").build();
        BandMember founder = BandMember.builder().band(band).bandRole(BandRole.FOUNDER)
                .status(BandMemberShipStatus.ACTIVE).build();
        when(bands.findByIdForUpdate(bandId)).thenReturn(Optional.of(band));
        when(members.findByBandIdAndUserId(bandId, founderId)).thenReturn(Optional.of(founder));
        when(bands.save(band)).thenReturn(band);

        var result = service.updateBand(bandId, founderId, new BandCreateRequestDto(
                "THE BAND", "", null, "", null, null, null, null, null));

        assertThat(result.name()).isEqualTo("THE BAND");
        assertThat(result.description()).isEmpty();
        assertThat(result.instagramUrl()).isEmpty();
        verify(bands).findByName("THE BAND");
    }

    @Test
    void invalidNamesAndUnsafeLinksFailBeforeAccessingTheAggregate() {
        for (String name : List.of("", "   ", "x".repeat(101))) {
            var invalid = new BandCreateRequestDto(name, null, null, null, null, null, null, null, null);
            assertThatThrownBy(() -> service.updateBand(bandId, founderId, invalid))
                    .isInstanceOfSatisfying(SoundConnectException.class, exception ->
                            assertThat(exception.getErrorType()).isEqualTo(ErrorType.VALIDATION_ERROR));
        }
        var unsafe = new BandCreateRequestDto("Band", null, null, "javascript:alert(1)",
                null, null, null, null, null);
        assertThatThrownBy(() -> service.createBand(founderId, unsafe)).isInstanceOf(SoundConnectException.class);
        verifyNoInteractions(bands, members, finder);
    }
}

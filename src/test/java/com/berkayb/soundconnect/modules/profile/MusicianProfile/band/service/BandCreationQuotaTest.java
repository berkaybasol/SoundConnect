package com.berkayb.soundconnect.modules.profile.MusicianProfile.band.service;

import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.dto.request.BandCreateRequestDto;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity.Band;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity.BandMember;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.enums.BandMemberShipStatus;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.enums.BandRole;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.mapper.BandMapper;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.repository.BandMemberRepository;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.repository.BandRepository;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.entity.MusicianProfile;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.repository.MusicianProfileRepository;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mapstruct.factory.Mappers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.jpa.repository.Lock;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BandCreationQuotaTest {
    @Mock UserRepository users;
    @Mock BandRepository bands;
    @Mock BandMemberRepository members;
    @Mock MusicianProfileRepository musicians;
    @Spy BandMapper mapper = Mappers.getMapper(BandMapper.class);
    @InjectMocks BandServiceImpl service;
    final UUID actor = UUID.randomUUID();
    final User user = User.builder().id(actor).username("musician").build();

    @ParameterizedTest @ValueSource(longs = {0, 1, 2})
    void unrelatedActiveMembershipsDoNotConsumeCreationSlots(long foundedCount) throws Exception {
        when(users.findByIdForUpdate(actor)).thenReturn(Optional.of(user));
        when(musicians.findByUserId(actor)).thenReturn(Optional.of(new MusicianProfile()));
        when(members.countByUserIdAndStatusAndBandRole(actor,BandMemberShipStatus.ACTIVE,BandRole.FOUNDER))
                .thenReturn(foundedCount);
        when(bands.save(any())).thenAnswer(call -> {
            Band band=call.getArgument(0); band.setId(UUID.randomUUID()); return band;
        });

        var result=service.createBand(actor,request());

        assertThat(result.countsTowardCreationLimit()).isTrue();
        assertThat(result.members()).singleElement().satisfies(member -> {
            assertThat(member.userId()).isEqualTo(actor);
            assertThat(member.role()).isEqualTo("FOUNDER");
            assertThat(member.status()).isEqualTo("ACTIVE");
        });
        var order=inOrder(users,musicians,members,bands);
        order.verify(users).findByIdForUpdate(actor);
        order.verify(musicians).findByUserId(actor);
        order.verify(members).countByUserIdAndStatusAndBandRole(actor,BandMemberShipStatus.ACTIVE,BandRole.FOUNDER);
        order.verify(bands).findByName(request().name());
        order.verify(bands).save(any());
        verify(members,never()).countByUserIdAndStatus(any(),any());
        assertThat(UserRepository.class.getMethod("findByIdForUpdate",UUID.class).getAnnotation(Lock.class).value())
                .isEqualTo(LockModeType.PESSIMISTIC_WRITE);
    }

    @ParameterizedTest @ValueSource(longs = {3, 4})
    void threeOrMoreActiveFounderMembershipsRejectCreationBeforeAnyWrite(long foundedCount) {
        when(users.findByIdForUpdate(actor)).thenReturn(Optional.of(user));
        when(musicians.findByUserId(actor)).thenReturn(Optional.of(new MusicianProfile()));
        when(members.countByUserIdAndStatusAndBandRole(actor,BandMemberShipStatus.ACTIVE,BandRole.FOUNDER))
                .thenReturn(foundedCount);

        assertThatThrownBy(() -> service.createBand(actor,request()))
                .isInstanceOfSatisfying(SoundConnectException.class,
                        failure -> assertThat(failure.getErrorType()).isEqualTo(ErrorType.BAND_CREATE_LIMIT_EXCEEDED));
        verifyNoInteractions(bands,mapper);
    }

    @Test
    void failedAccountLockCannotReadQuotaOrInsertAFounder() {
        when(users.findByIdForUpdate(actor)).thenThrow(new IllegalStateException("Concurrent account lock unavailable"));
        assertThatThrownBy(() -> service.createBand(actor,request())).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(musicians,members,bands,mapper);
    }

    @ParameterizedTest @EnumSource(BandRole.class)
    void myBandsKeepsAllMembershipsAndMarksOnlyActiveFounderForTheCreationCounter(BandRole role) {
        Band band=Band.builder().id(UUID.randomUUID()).name("Şahbaz").build();
        BandMember membership=BandMember.builder().band(band).user(user).bandRole(role)
                .status(BandMemberShipStatus.ACTIVE).build();
        when(members.findByUserIdAndStatus(actor,BandMemberShipStatus.ACTIVE)).thenReturn(List.of(membership));

        var result=service.getBandsByUser(actor);

        assertThat(result).singleElement().satisfies(item -> {
            assertThat(item.id()).isEqualTo(band.getId());
            assertThat(item.countsTowardCreationLimit()).isEqualTo(role==BandRole.FOUNDER);
        });
    }

    private BandCreateRequestDto request() {
        return new BandCreateRequestDto("Yeni Grup",null,null,null,null,null,null,null,List.of());
    }
}

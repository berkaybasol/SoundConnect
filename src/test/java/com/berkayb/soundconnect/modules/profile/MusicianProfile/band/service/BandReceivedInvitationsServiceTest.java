package com.berkayb.soundconnect.modules.profile.MusicianProfile.band.service;

import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.repository.*;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.enums.BandMemberShipStatus;
import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.modules.user.support.UserEntityFinder;
import com.berkayb.soundconnect.modules.role.entity.Role;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BandReceivedInvitationsServiceTest {
    @Mock BandMemberRepository members;
    @Mock UserEntityFinder users;
    @Mock MediaAssetService media;
    @InjectMocks BandServiceImpl service;
    UUID actor = UUID.randomUUID();
    User user;
    @BeforeEach void setup() {
        user = User.builder().id(actor).status(UserStatus.ACTIVE).emailVerified(true)
            .roles(Set.of(Role.builder().name("ROLE_MUSICIAN").build())).build();
        lenient().when(users.getUser(actor)).thenReturn(user);
    }
    @Test void receivedPageIsActorScopedBatchedAndDoesNotRequireFounder() {
        UUID image = UUID.randomUUID();
        var first = new BandReceivedInvitationRow(UUID.randomUUID(), "Şahbaz", image, UUID.randomUUID());
        var second = new BandReceivedInvitationRow(UUID.randomUUID(), "Diğer", image, UUID.randomUUID());
        var noPhoto = new BandReceivedInvitationRow(UUID.randomUUID(), "Fotoğrafsız", null, UUID.randomUUID());
        when(members.findReceivedInvitationSummaries(actor, BandMemberShipStatus.PENDING, PageRequest.of(0,20)))
            .thenReturn(new PageImpl<>(List.of(first,second,noPhoto), PageRequest.of(0,20),3));
        when(media.getDisplayUrlMap(List.of(image))).thenReturn(Map.of(image,"https://cdn.test/band.jpg"));
        var result = service.getReceivedInvitations(actor,0,20);
        assertThat(result.content()).hasSize(3);
        assertThat(result.content().getFirst().bandId()).isEqualTo(first.bandId());
        assertThat(result.content().getFirst().invitationId()).isEqualTo(first.invitationId());
        assertThat(result.content().getFirst().profilePicture()).isEqualTo("https://cdn.test/band.jpg");
        assertThat(result.content().get(2).profilePicture()).isNull();
        assertThat(result.totalElements()).isEqualTo(3);
        var order = inOrder(users,members,media);
        order.verify(users).getUser(actor);
        order.verify(members).findReceivedInvitationSummaries(actor,BandMemberShipStatus.PENDING,PageRequest.of(0,20));
        order.verify(media).getDisplayUrlMap(List.of(image));
        verifyNoMoreInteractions(members,media);
    }
    @ParameterizedTest @CsvSource({"-1,20","10001,1","0,0","0,51","2001,50","2147483647,50"})
    void invalidPageNeverReadsAccountOrInvitations(int page,int size) {
        assertThatThrownBy(() -> service.getReceivedInvitations(actor,page,size)).isInstanceOf(SoundConnectException.class);
        verifyNoInteractions(users,members,media);
    }
    @ParameterizedTest @EnumSource(value=UserStatus.class,names="ACTIVE",mode=EnumSource.Mode.EXCLUDE)
    void inactiveAccountCannotRead(UserStatus status) { user.setStatus(status); forbidden(); }
    @Test void unverifiedCannotRead() { user.setEmailVerified(false); forbidden(); }
    @Test void missingRolesCannotRead() { user.setRoles(null); forbidden(); }
    @Test void revokedMusicianRoleCannotRead() { user.setRoles(Set.of(Role.builder().name("ROLE_LISTENER").build())); forbidden(); }
    void forbidden() {
        assertThatThrownBy(() -> service.getReceivedInvitations(actor,0,20)).isInstanceOf(SoundConnectException.class);
        verifyNoInteractions(members,media);
    }
    @Test void emptyBeyondEndPreservesMetadataAndSkipsMedia() {
        when(members.findReceivedInvitationSummaries(actor,BandMemberShipStatus.PENDING,PageRequest.of(2,20)))
            .thenReturn(new PageImpl<>(List.of(),PageRequest.of(2,20),0));
        var result = service.getReceivedInvitations(actor,2,20);
        assertThat(result.content()).isEmpty(); assertThat(result.page()).isEqualTo(2);
        verifyNoInteractions(media);
    }

    @Test void currentInvitationReadIsActorScopedAndReturnsItsImmutableIdentity() {
        UUID bandId = UUID.randomUUID(), invitationId = UUID.randomUUID();
        when(members.findCurrentReceivedInvitation(bandId,actor,BandMemberShipStatus.PENDING))
            .thenReturn(Optional.of(new BandReceivedInvitationRow(bandId,"Şahbaz",null,invitationId)));
        var result = service.getCurrentReceivedInvitation(bandId,actor);
        assertThat(result.invitationId()).isEqualTo(invitationId);
        assertThat(result.status()).isEqualTo("PENDING");
        verifyNoInteractions(media);
    }

    @Test void absentCurrentInvitationDoesNotReturnAnotherUsersOrHistoricalInvitation() {
        assertThatThrownBy(() -> service.getCurrentReceivedInvitation(UUID.randomUUID(),actor))
            .isInstanceOf(SoundConnectException.class);
        verifyNoInteractions(media);
    }
    @Test void hiddenOrDeletedPhotoStaysPlaceholder() {
        var row = new BandReceivedInvitationRow(UUID.randomUUID(),"Şahbaz",UUID.randomUUID(),UUID.randomUUID());
        when(members.findReceivedInvitationSummaries(actor,BandMemberShipStatus.PENDING,PageRequest.of(0,20)))
            .thenReturn(new PageImpl<>(List.of(row),PageRequest.of(0,20),1));
        when(media.getDisplayUrlMap(anyList())).thenReturn(Map.of());
        assertThat(service.getReceivedInvitations(actor,0,20).content().getFirst().profilePicture()).isNull();
    }
}

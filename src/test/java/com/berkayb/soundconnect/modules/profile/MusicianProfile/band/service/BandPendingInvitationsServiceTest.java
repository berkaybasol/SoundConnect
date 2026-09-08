package com.berkayb.soundconnect.modules.profile.MusicianProfile.band.service;

import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.dto.response.BandPendingInvitationResponseDto;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.repository.BandPendingInvitationRow;
import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity.BandMember;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.enums.BandMemberShipStatus;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.enums.BandRole;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.repository.BandMemberRepository;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.repository.BandRepository;
import com.berkayb.soundconnect.modules.role.entity.Role;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BandPendingInvitationsServiceTest {
    @Mock BandMemberRepository members;
    @Mock BandRepository bands;
    @Mock MediaAssetService media;
    @InjectMocks BandServiceImpl service;
    final UUID bandId = UUID.randomUUID(), actorId = UUID.randomUUID();
    BandMember founder;

    @BeforeEach void founder() {
        var user = User.builder().id(actorId).status(UserStatus.ACTIVE).emailVerified(true)
                .roles(Set.of(Role.builder().name("ROLE_MUSICIAN").build())).build();
        founder = BandMember.builder().user(user).status(BandMemberShipStatus.ACTIVE).bandRole(BandRole.FOUNDER).build();
        lenient().when(members.findByBandIdAndUserId(bandId, actorId)).thenReturn(Optional.of(founder));
    }

    @Test void activeVerifiedFounderGetsBoundedPrivatePageWithoutLoadingRosterOrWriting() {
        var pageable = PageRequest.of(1, 20);
        UUID image = UUID.randomUUID();
        var row = new BandPendingInvitationRow(UUID.randomUUID(), "aedrum", image);
        when(media.getDisplayUrlMap(List.of(image))).thenReturn(Map.of(image, "https://cdn.test/aedrum.jpg"));
        when(members.findPendingInvitationSummaries(bandId, BandMemberShipStatus.PENDING, pageable))
                .thenReturn(new PageImpl<>(List.of(row), pageable, 21));
        var result = service.getPendingInvitations(bandId, actorId, 1, 20);
        assertThat(result.content()).containsExactly(new BandPendingInvitationResponseDto(
                row.userId(), "aedrum", "https://cdn.test/aedrum.jpg", "PENDING"));
        assertThat(result.page()).isEqualTo(1);
        assertThat(result.size()).isEqualTo(20);
        assertThat(result.totalElements()).isEqualTo(21);
        assertThat(result.totalPages()).isEqualTo(2);
        assertThat(result.last()).isTrue();
        var ordered = inOrder(members, media);
        ordered.verify(members).findByBandIdAndUserId(bandId, actorId);
        ordered.verify(members).findPendingInvitationSummaries(bandId, BandMemberShipStatus.PENDING, pageable);
        ordered.verify(media).getDisplayUrlMap(List.of(image));
        verifyNoMoreInteractions(members);
        verifyNoMoreInteractions(media);
        verifyNoInteractions(bands);
    }

    @ParameterizedTest @CsvSource({"-1,20", "10001,1", "0,0", "0,-1", "0,51", "2001,50", "2147483647,50", "1,2147483647"})
    void rejectsInvalidAndOverflowingPagesBeforeQuery(int page, int size) {
        assertError(() -> service.getPendingInvitations(bandId, actorId, page, size), ErrorType.BAND_PENDING_INVITATIONS_PAGE_INVALID);
        verifyNoInteractions(members, bands, media);
    }

    @ParameterizedTest @CsvSource({"0,1", "0,50", "10000,10", "2000,50"})
    void acceptsDocumentedBoundaries(int page, int size) {
        var pageable = PageRequest.of(page, size);
        when(members.findPendingInvitationSummaries(bandId, BandMemberShipStatus.PENDING, pageable))
                .thenReturn(new PageImpl<>(List.of(), pageable, 0));
        assertThat(service.getPendingInvitations(bandId, actorId, page, size).content()).isEmpty();
        verifyNoInteractions(media);
    }

    @Test void pageWithoutCanonicalMusicianImageKeepsInviteesAndSkipsMediaLookup() {
        var row = new BandPendingInvitationRow(UUID.randomUUID(), "aedrum", null);
        when(members.findPendingInvitationSummaries(bandId, BandMemberShipStatus.PENDING, PageRequest.of(0, 20)))
                .thenReturn(new PageImpl<>(List.of(row), PageRequest.of(0, 20), 1));
        var result = service.getPendingInvitations(bandId, actorId, 0, 20);
        assertThat(result.content()).containsExactly(new BandPendingInvitationResponseDto(row.userId(), "aedrum", null, "PENDING"));
        verifyNoInteractions(media);
    }

    @Test void missingPrivateOrUnreadyImageLeavesPlaceholderWithoutRemovingInvite() {
        var row = new BandPendingInvitationRow(UUID.randomUUID(), "aedrum", UUID.randomUUID());
        when(members.findPendingInvitationSummaries(bandId, BandMemberShipStatus.PENDING, PageRequest.of(0, 20)))
                .thenReturn(new PageImpl<>(List.of(row), PageRequest.of(0, 20), 1));
        when(media.getDisplayUrlMap(List.of(row.profilePictureMediaId()))).thenReturn(Map.of());
        var result = service.getPendingInvitations(bandId, actorId, 0, 20);
        assertThat(result.content()).containsExactly(new BandPendingInvitationResponseDto(row.userId(), "aedrum", null, "PENDING"));
        assertThat(result.totalElements()).isEqualTo(1);
        assertThat(result.last()).isTrue();
    }

    @Test void mixedPageExcludesNullIdsAndDeduplicatesBeforeBatchLookup() {
        UUID image = UUID.randomUUID();
        var rows = List.of(new BandPendingInvitationRow(UUID.randomUUID(), "noimage", null),
                new BandPendingInvitationRow(UUID.randomUUID(), "first", image),
                new BandPendingInvitationRow(UUID.randomUUID(), "second", image));
        when(members.findPendingInvitationSummaries(bandId, BandMemberShipStatus.PENDING, PageRequest.of(0, 20)))
                .thenReturn(new PageImpl<>(rows, PageRequest.of(0, 20), 3));
        when(media.getDisplayUrlMap(List.of(image))).thenReturn(Map.of(image, "https://cdn.test/canonical.jpg"));
        var result = service.getPendingInvitations(bandId, actorId, 0, 20);
        assertThat(result.content()).extracting(BandPendingInvitationResponseDto::profilePicture)
                .containsExactly(null, "https://cdn.test/canonical.jpg", "https://cdn.test/canonical.jpg");
        verify(media).getDisplayUrlMap(List.of(image));
        verifyNoMoreInteractions(media);
    }

    @Test void mediaDatabaseFailureIsNotDisguisedAsSuccessfulPlaceholderPage() {
        UUID image = UUID.randomUUID();
        var row = new BandPendingInvitationRow(UUID.randomUUID(), "aedrum", image);
        when(members.findPendingInvitationSummaries(bandId, BandMemberShipStatus.PENDING, PageRequest.of(0, 20)))
                .thenReturn(new PageImpl<>(List.of(row), PageRequest.of(0, 20), 1));
        var failure = new org.springframework.dao.DataAccessResourceFailureException("isolated test failure");
        when(media.getDisplayUrlMap(List.of(image))).thenThrow(failure);
        assertThatThrownBy(() -> service.getPendingInvitations(bandId, actorId, 0, 20)).isSameAs(failure);
    }

    @ParameterizedTest @ValueSource(ints = {25, 50})
    void maximumPageUsesOneDeduplicatedBatchAndPreservesOrderAndPagination(int uniqueImages) {
        var ids = new ArrayList<UUID>();
        var urls = new java.util.HashMap<UUID, String>();
        for (int index = 0; index < uniqueImages; index++) {
            UUID image = UUID.randomUUID(); ids.add(image); urls.put(image, "https://cdn.test/image-" + index);
        }
        var rows = new ArrayList<BandPendingInvitationRow>();
        for (int index = 0; index < 50; index++) {
            rows.add(new BandPendingInvitationRow(UUID.randomUUID(), "member" + index, ids.get(index % uniqueImages)));
        }
        when(members.findPendingInvitationSummaries(bandId, BandMemberShipStatus.PENDING, PageRequest.of(0, 50)))
                .thenReturn(new PageImpl<>(rows, PageRequest.of(0, 50), 75));
        when(media.getDisplayUrlMap(ids)).thenReturn(urls);
        var result = service.getPendingInvitations(bandId, actorId, 0, 50);
        assertThat(result.content()).hasSize(50);
        for (int index = 0; index < 50; index++) {
            var row = rows.get(index);
            assertThat(result.content().get(index)).isEqualTo(new BandPendingInvitationResponseDto(
                    row.userId(), row.username(), urls.get(row.profilePictureMediaId()), "PENDING"));
        }
        assertThat(result.page()).isZero();
        assertThat(result.size()).isEqualTo(50);
        assertThat(result.totalElements()).isEqualTo(75);
        assertThat(result.totalPages()).isEqualTo(2);
        assertThat(result.first()).isTrue();
        assertThat(result.last()).isFalse();
        verify(media, times(1)).getDisplayUrlMap(ids);
        verifyNoMoreInteractions(media);
    }

    @ParameterizedTest @EnumSource(value = BandRole.class, names = "FOUNDER", mode = EnumSource.Mode.EXCLUDE)
    void ordinaryMembersCannotReadPendingInvitees(BandRole role) {
        founder.setBandRole(role); assertForbidden();
    }

    @ParameterizedTest @EnumSource(value = BandMemberShipStatus.class, names = "ACTIVE", mode = EnumSource.Mode.EXCLUDE)
    void staleFounderMembershipCannotRead(BandMemberShipStatus status) {
        founder.setStatus(status); assertForbidden();
    }

    @ParameterizedTest @EnumSource(value = UserStatus.class, names = "ACTIVE", mode = EnumSource.Mode.EXCLUDE)
    void inactiveAccountCannotReadDespiteValidJwt(UserStatus status) {
        founder.getUser().setStatus(status); assertForbidden();
    }

    @ParameterizedTest @ValueSource(strings = {"ROLE_VENUE", "ROLE_LISTENER", "ROLE_STUDIO", "MUSICIAN", ""})
    void currentDatabaseMusicianRoleIsRequired(String role) {
        founder.getUser().setRoles(Set.of(Role.builder().name(role).build())); assertForbidden();
    }

    @Test void revokedVerificationCannotRead() { founder.getUser().setEmailVerified(false); assertForbidden(); }
    @Test void missingUserFailsClosed() { founder.setUser(null); assertForbidden(); }
    @Test void missingRolesFailClosed() { founder.getUser().setRoles(null); assertForbidden(); }

    @Test void outsiderAndUnknownBandReturnSamePrivacyErrorWithoutListing() {
        when(members.findByBandIdAndUserId(bandId, actorId)).thenReturn(Optional.empty());
        assertForbidden();
        assertError(() -> service.getPendingInvitations(UUID.randomUUID(), actorId, 0, 20), ErrorType.BAND_PENDING_INVITATIONS_FORBIDDEN);
    }

    @Test void permissionAndContentUseOneReadOnlySnapshot() throws Exception {
        var annotation = BandServiceImpl.class.getMethod("getPendingInvitations", UUID.class, UUID.class, int.class, int.class)
                .getAnnotation(Transactional.class);
        assertThat(annotation.readOnly()).isTrue();
        assertThat(annotation.isolation()).isEqualTo(Isolation.REPEATABLE_READ);
    }

    private void assertForbidden() {
        assertError(() -> service.getPendingInvitations(bandId, actorId, 0, 20), ErrorType.BAND_PENDING_INVITATIONS_FORBIDDEN);
        verify(members, never()).findPendingInvitationSummaries(any(), any(), any());
        verify(members, never()).save(any());
        verifyNoInteractions(media);
    }

    private void assertError(Runnable call, ErrorType expected) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(SoundConnectException.class,
                exception -> assertThat(exception.getErrorType()).isEqualTo(expected));
    }
}

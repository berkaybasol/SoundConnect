package com.berkayb.soundconnect.modules.profile.MusicianProfile.band.service;

import com.berkayb.soundconnect.modules.event.performer.service.EventPerformerRequestService;
import com.berkayb.soundconnect.modules.event.publication.EventMemberPublicationRepository;
import com.berkayb.soundconnect.modules.event.repository.EventRepository;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.dto.request.BandMemberTitleUpdateDto;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity.Band;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity.BandMember;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.enums.BandMemberShipStatus;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.enums.BandRole;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.mapper.BandMapper;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.repository.BandMemberRepository;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.repository.BandRepository;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.support.BandEntityFinder;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.modules.user.support.UserEntityFinder;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.berkayb.soundconnect.modules.notification.service.TransactionalNotificationService;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.entity.MusicianProfile;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.repository.MusicianProfileRepository;
import com.berkayb.soundconnect.modules.role.entity.Role;
import org.junit.jupiter.api.BeforeEach;
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

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BandMemberTitleServiceTest {
    @Mock BandRepository bands;
    @Mock BandMemberRepository members;
    @Mock UserEntityFinder users;
    @Mock BandEntityFinder finder;
    @Mock EventRepository events;
    @Mock EventPerformerRequestService consent;
    @Mock EventMemberPublicationRepository publications;
    @Mock TransactionalNotificationService notifications;
    @Mock MusicianProfileRepository musicians;
    @Spy BandMapper mapper = Mappers.getMapper(BandMapper.class);
    @InjectMocks BandServiceImpl service;

    final UUID bandId = UUID.randomUUID(), founderId = UUID.randomUUID(), memberId = UUID.randomUUID();
    Band band;
    BandMember founder;
    BandMember member;

    @BeforeEach void setup() {
        band = Band.builder().id(bandId).name("Şahbaz").build();
        founder = membership(founderId, BandRole.FOUNDER);
        member = membership(memberId, BandRole.MEMBER);
        lenient().when(bands.findByIdForUpdate(bandId)).thenReturn(Optional.of(band));
        lenient().when(members.findByBandIdAndUserId(bandId, founderId)).thenReturn(Optional.of(founder));
        lenient().when(members.findByBandIdAndUserId(bandId, memberId)).thenReturn(Optional.of(member));
    }

    @Test void founderUpdatesAnotherActiveMemberUnderAggregateLock() {
        var result = update(memberId, "　 Vokal / Bag\u0306lama ", 0);
        assertThat(result.memberTitle()).isEqualTo("Vokal / Bağlama");
        assertThat(result.titleVersion()).isEqualTo(1);
        assertThat(result.role()).isEqualTo("MEMBER");
        assertThat(result.status()).isEqualTo("ACTIVE");
        var ordered = inOrder(bands, members);
        ordered.verify(bands).findByIdForUpdate(bandId);
        ordered.verify(members).findByBandIdAndUserId(bandId, founderId);
        ordered.verify(members).findByBandIdAndUserId(bandId, memberId);
        ordered.verify(members).save(member);
        verifyNoInteractions(events, consent, publications, notifications);
    }

    @Test void founderCanSetOwnTitleWithoutLosingFounderRole() {
        var result = update(founderId, "Davul", 0);
        assertThat(result.memberTitle()).isEqualTo("Davul");
        assertThat(result.role()).isEqualTo("FOUNDER");
        assertThat(founder.getBandRole()).isEqualTo(BandRole.FOUNDER);
        verify(members).findByBandIdAndUserId(bandId, founderId);
    }

    @Test void titleTextCannotGrantAuthority() {
        update(memberId, "Kurucu", 0);
        assertThat(member.getBandRole()).isEqualTo(BandRole.MEMBER);
        assertThat(member.getStatus()).isEqualTo(BandMemberShipStatus.ACTIVE);
        verifyNoInteractions(events, consent, publications);
    }

    @ParameterizedTest @ValueSource(strings = {"", " ", "　 "})
    void blankClearsExistingTitleAndBumpsOnlyOnce(String blank) {
        member.setMemberTitle("Davul");
        member.setTitleVersion(4);
        assertThat(update(memberId, blank, 4).memberTitle()).isNull();
        assertThat(member.getTitleVersion()).isEqualTo(5);
        update(memberId, null, 5);
        verify(members, times(1)).save(member);
    }

    @Test void identicalNormalizedValueDoesNotWriteOrBump() {
        member.setMemberTitle("Bağlama");
        member.setTitleVersion(4);
        assertThat(update(memberId, " Bag\u0306lama ", 4).titleVersion()).isEqualTo(4);
        verify(members, never()).save(any());
    }

    @Test void staleVersionConflictsEvenWhenItsTitleMatches() {
        member.setMemberTitle("Davul"); member.setTitleVersion(3);
        assertError(() -> update(memberId, "Davul", 2), ErrorType.BAND_MEMBER_TITLE_VERSION_CONFLICT);
        verify(members, never()).save(any());
    }

    @Test void staleEditorCannotOverwriteLaterFounderChange() {
        update(memberId, "Davul", 0);
        assertError(() -> update(memberId, "Vokal", 0), ErrorType.BAND_MEMBER_TITLE_VERSION_CONFLICT);
        assertThat(member.getMemberTitle()).isEqualTo("Davul");
        assertThat(member.getTitleVersion()).isEqualTo(1);
    }

    @Test void overflowFailsBeforeChangingTheTitle() {
        member.setTitleVersion(Long.MAX_VALUE); member.setMemberTitle("Davul");
        assertError(() -> update(memberId, "Vokal", Long.MAX_VALUE), ErrorType.BAND_MEMBER_TITLE_VERSION_CONFLICT);
        assertThat(member.getMemberTitle()).isEqualTo("Davul");
        assertThat(member.getTitleVersion()).isEqualTo(Long.MAX_VALUE);
        verify(members, never()).save(any());
    }

    @ParameterizedTest @EnumSource(value = BandRole.class, names = "FOUNDER", mode = EnumSource.Mode.EXCLUDE)
    void nonFounderCannotEditTitles(BandRole role) {
        founder.setBandRole(role);
        assertError(() -> update(memberId, "Davul", 0), ErrorType.BAND_MEMBER_TITLE_UNAUTHORIZED);
        verify(members, never()).findByBandIdAndUserId(bandId, memberId);
        verify(members, never()).save(any());
    }

    @ParameterizedTest @EnumSource(value = BandMemberShipStatus.class, names = "ACTIVE", mode = EnumSource.Mode.EXCLUDE)
    void inactiveRequesterCannotEdit(BandMemberShipStatus status) {
        founder.setStatus(status);
        assertError(() -> update(memberId, "Davul", 0), ErrorType.BAND_MEMBER_NOT_ACTIVE);
        verify(members, never()).save(any());
    }

    @ParameterizedTest @EnumSource(value = BandMemberShipStatus.class, names = "ACTIVE", mode = EnumSource.Mode.EXCLUDE)
    void inactiveTargetCannotBeEdited(BandMemberShipStatus status) {
        member.setStatus(status);
        assertError(() -> update(memberId, "Davul", 0), ErrorType.BAND_MEMBER_NOT_ACTIVE);
        verify(members, never()).save(any());
    }

    @Test void disabledAccountCannotEditWithStaleFounderMembership() {
        founder.getUser().setStatus(UserStatus.INACTIVE);
        assertError(() -> update(memberId, "Davul", 0), ErrorType.BAND_MEMBER_TITLE_UNAUTHORIZED);
    }

    @Test void memberFromAnotherBandIsNotAnEditableTarget() {
        UUID outsider = UUID.randomUUID();
        assertError(() -> update(outsider, "Davul", 0), ErrorType.BAND_MEMBER_NOT_FOUND);
        verify(members, never()).save(any());
    }

    @Test void invalidInputNeverAcquiresBandLock() {
        assertError(() -> update(memberId, "a\nb", 0), ErrorType.BAND_MEMBER_TITLE_INVALID);
        assertError(() -> update(memberId, "Davul", -1), ErrorType.BAND_MEMBER_TITLE_INVALID);
        verifyNoInteractions(bands, members, mapper);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void reinviteThenAcceptClearsTitleAndFencesOldEditorsEvenWhenTitleWasNull(boolean hadTitle) {
        member.setMemberTitle(hadTitle ? "Davul" : null);
        member.setTitleVersion(5);
        member.setStatus(BandMemberShipStatus.LEFT);
        when(users.getUser(founderId)).thenReturn(founder.getUser());
        when(users.getUser(memberId)).thenReturn(member.getUser());
        member.getUser().setRoles(java.util.Set.of(Role.builder().name("ROLE_MUSICIAN").build()));
        when(musicians.findByUserId(memberId)).thenReturn(Optional.of(new MusicianProfile()));
        when(finder.getBandMember(bandId, memberId)).thenReturn(member);
        service.inviteMember(bandId, founderId, memberId, null);
        assertThat(member.getMemberTitle()).isNull();
        assertThat(member.getTitleVersion()).isEqualTo(6);
        service.acceptInvite(bandId, memberId, member.getInvitationId());
        assertThat(member.getTitleVersion()).isEqualTo(7);
        assertError(() -> update(memberId, "Eski başlık", 5), ErrorType.BAND_MEMBER_TITLE_VERSION_CONFLICT);
        assertThat(member.getMemberTitle()).isNull();
    }

    @Test void legacyPendingTitleClearsButDuplicateActiveAcceptancePreservesCurrentTitle() {
        member.setStatus(BandMemberShipStatus.PENDING);
        member.setMemberTitle("Eski başlık"); member.setTitleVersion(4);
        when(finder.getBandMember(bandId, memberId)).thenReturn(member);
        service.acceptInvite(bandId, memberId, member.getInvitationId());
        assertThat(member.getMemberTitle()).isNull();
        assertThat(member.getTitleVersion()).isEqualTo(5);
        update(memberId, "Davul", 5);
        assertError(() -> service.acceptInvite(bandId, memberId, member.getInvitationId()), ErrorType.BAND_INVITE_STATUS_INVALID);
        assertThat(member.getMemberTitle()).isEqualTo("Davul");
        assertThat(member.getTitleVersion()).isEqualTo(6);
    }

    private com.berkayb.soundconnect.modules.profile.MusicianProfile.band.dto.response.BandMemberResponseDto update(UUID target, String title, long version) {
        return service.updateMemberTitle(bandId, founderId, target, new BandMemberTitleUpdateDto(title, version));
    }

    private BandMember membership(UUID id, BandRole role) {
        User user = User.builder().id(id).username("member").emailVerified(true).status(UserStatus.ACTIVE).build();
        return BandMember.builder().band(band).user(user).bandRole(role).status(BandMemberShipStatus.ACTIVE).invitationId(UUID.randomUUID()).build();
    }

    private void assertError(Runnable call, ErrorType expected) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(SoundConnectException.class,
                exception -> assertThat(exception.getErrorType()).isEqualTo(expected));
    }
}

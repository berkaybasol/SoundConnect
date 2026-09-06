package com.berkayb.soundconnect.modules.profile.MusicianProfile.band.service;

import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.repository.ArtistVenueConnectionRequestRepository;
import com.berkayb.soundconnect.modules.event.performer.service.EventPerformerRequestService;
import com.berkayb.soundconnect.modules.event.publication.EventMemberPublicationRepository;
import com.berkayb.soundconnect.modules.event.repository.EventRepository;
import com.berkayb.soundconnect.modules.follow.band.repository.BandFollowRepository;
import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity.Band;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity.BandMember;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.enums.BandMemberShipStatus;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.enums.BandRole;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.mapper.BandMapper;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.repository.BandMemberRepository;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.repository.BandRepository;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.support.BandEntityFinder;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.repository.MusicianProfileRepository;
import com.berkayb.soundconnect.modules.setlistcreator.repository.SetlistRepository;
import com.berkayb.soundconnect.modules.track.repository.TrackRepository;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.support.UserEntityFinder;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.berkayb.soundconnect.shared.messaging.events.notification.NotificationProducer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BandServiceImplPublicationLifecycleTest {
    @Mock BandRepository bandRepository;
    @Mock BandMemberRepository bandMemberRepository;
    @Mock UserEntityFinder userEntityFinder;
    @Mock BandEntityFinder bandEntityFinder;
    @Mock BandMapper bandMapper;
    @Mock MusicianProfileRepository musicianProfileRepository;
    @Mock MediaAssetService mediaAssetService;
    @Mock NotificationProducer notificationProducer;
    @Mock BandFollowRepository bandFollowRepository;
    @Mock ArtistVenueConnectionRequestRepository artistVenueConnectionRequestRepository;
    @Mock SetlistRepository setlistRepository;
    @Mock EventRepository eventRepository;
    @Mock TrackRepository trackRepository;
    @Mock EventPerformerRequestService eventPerformerRequestService;
    @Mock EventMemberPublicationRepository eventMemberPublicationRepository;
    @InjectMocks BandServiceImpl service;

    final UUID bandId = UUID.randomUUID(), founderId = UUID.randomUUID(), memberId = UUID.randomUUID();

    @Test
    void leavingLocksBeforeMembershipReadAndRevokesBeforeLeaving() {
        Band band = lockedBand();
        BandMember member = member(band, memberId, BandRole.MEMBER, BandMemberShipStatus.ACTIVE);
        when(bandEntityFinder.getBandMember(bandId, memberId)).thenReturn(member);
        when(eventMemberPublicationRepository.hideForBandMember(bandId, memberId)).thenAnswer(invocation -> {
            assertThat(member.getStatus()).isEqualTo(BandMemberShipStatus.ACTIVE);
            return 2;
        });

        service.leaveBand(bandId, memberId);

        var order = inOrder(bandRepository, bandEntityFinder, eventMemberPublicationRepository, bandMemberRepository);
        order.verify(bandRepository).findByIdForUpdate(bandId);
        order.verify(bandEntityFinder).getBandMember(bandId, memberId);
        order.verify(eventMemberPublicationRepository).hideForBandMember(bandId, memberId);
        order.verify(bandMemberRepository).save(member);
        assertThat(member.getStatus()).isEqualTo(BandMemberShipStatus.LEFT);
        verifyNoInteractions(eventRepository, eventPerformerRequestService);
    }

    @Test
    void founderRemovalRevokesOnlyTheRemovedMemberUnderTheBandFence() {
        Band band = lockedBand();
        BandMember founder = member(band, founderId, BandRole.FOUNDER, BandMemberShipStatus.ACTIVE);
        BandMember target = member(band, memberId, BandRole.MEMBER, BandMemberShipStatus.ACTIVE);
        when(bandMemberRepository.findByBandIdAndUserId(bandId, founderId)).thenReturn(Optional.of(founder));
        when(bandMemberRepository.findByBandIdAndUserId(bandId, memberId)).thenReturn(Optional.of(target));

        service.removeMember(bandId, founderId, memberId);

        var order = inOrder(bandRepository, bandMemberRepository, eventMemberPublicationRepository);
        order.verify(bandRepository).findByIdForUpdate(bandId);
        order.verify(bandMemberRepository).findByBandIdAndUserId(bandId, founderId);
        order.verify(bandMemberRepository).findByBandIdAndUserId(bandId, memberId);
        order.verify(eventMemberPublicationRepository).hideForBandMember(bandId, memberId);
        order.verify(bandMemberRepository).save(target);
        verifyNoMoreInteractions(eventMemberPublicationRepository);
        assertThat(founder.getStatus()).isEqualTo(BandMemberShipStatus.ACTIVE);
        assertThat(target.getStatus()).isEqualTo(BandMemberShipStatus.LEFT);
    }

    @Test
    void acceptingInvitationResetsOldConsentBeforeReactivatingMembership() {
        Band band = lockedBand();
        BandMember member = member(band, memberId, BandRole.MEMBER, BandMemberShipStatus.PENDING);
        when(bandEntityFinder.getBandMember(bandId, memberId)).thenReturn(member);
        when(eventMemberPublicationRepository.hideForBandMember(bandId, memberId)).thenAnswer(invocation -> {
            assertThat(member.getStatus()).isEqualTo(BandMemberShipStatus.PENDING);
            return 1;
        });

        service.acceptInvite(bandId, memberId);

        var order = inOrder(bandRepository, bandEntityFinder, eventMemberPublicationRepository, bandMemberRepository);
        order.verify(bandRepository).findByIdForUpdate(bandId);
        order.verify(bandEntityFinder).getBandMember(bandId, memberId);
        order.verify(eventMemberPublicationRepository).hideForBandMember(bandId, memberId);
        order.verify(bandMemberRepository).save(member);
        assertThat(member.getStatus()).isEqualTo(BandMemberShipStatus.ACTIVE);
        verifyNoInteractions(eventRepository, eventPerformerRequestService);
    }

    @ParameterizedTest
    @EnumSource(value = BandMemberShipStatus.class, names = {"LEFT", "REJECTED"})
    void reinvitingFormerMemberResetsChoicesInsteadOfInheritingOldPublication(BandMemberShipStatus oldStatus) {
        Band band = lockedBand();
        BandMember founder = member(band, founderId, BandRole.FOUNDER, BandMemberShipStatus.ACTIVE);
        BandMember former = member(band, memberId, BandRole.MEMBER, oldStatus);
        when(userEntityFinder.getUser(founderId)).thenReturn(founder.getUser());
        when(userEntityFinder.getUser(memberId)).thenReturn(former.getUser());
        when(bandMemberRepository.findByBandIdAndUserId(bandId, founderId)).thenReturn(Optional.of(founder));
        when(bandMemberRepository.findByBandIdAndUserId(bandId, memberId)).thenReturn(Optional.of(former));

        service.inviteMember(bandId, founderId, memberId, null);

        var order = inOrder(bandRepository, bandMemberRepository, eventMemberPublicationRepository);
        order.verify(bandRepository).findByIdForUpdate(bandId);
        order.verify(bandMemberRepository).findByBandIdAndUserId(bandId, founderId);
        order.verify(bandMemberRepository).findByBandIdAndUserId(bandId, memberId);
        order.verify(eventMemberPublicationRepository).hideForBandMember(bandId, memberId);
        order.verify(bandMemberRepository).save(former);
        assertThat(former.getStatus()).isEqualTo(BandMemberShipStatus.PENDING);
        assertThat(former.getBandRole()).isEqualTo(BandRole.MEMBER);
    }

    @Test
    void newInvitationHasNoInheritedPublicationAndUsesTheSameAggregateFence() {
        Band band = lockedBand();
        BandMember founder = member(band, founderId, BandRole.FOUNDER, BandMemberShipStatus.ACTIVE);
        User invited = user(memberId);
        when(userEntityFinder.getUser(founderId)).thenReturn(founder.getUser());
        when(userEntityFinder.getUser(memberId)).thenReturn(invited);
        when(bandMemberRepository.findByBandIdAndUserId(bandId, founderId)).thenReturn(Optional.of(founder));

        service.inviteMember(bandId, founderId, memberId, null);

        var order = inOrder(bandRepository, bandMemberRepository);
        order.verify(bandRepository).findByIdForUpdate(bandId);
        order.verify(bandMemberRepository).findByBandIdAndUserId(bandId, founderId);
        order.verify(bandMemberRepository).findByBandIdAndUserId(bandId, memberId);
        order.verify(bandMemberRepository).save(argThat(invitation -> invitation.getStatus() == BandMemberShipStatus.PENDING
                && invitation.getBandRole() == BandRole.MEMBER && invitation.getUser().getId().equals(memberId)));
        verifyNoInteractions(eventMemberPublicationRepository);
    }

    @Test
    void rejectingPendingMembershipDoesNotChangeEventConsentButStillHoldsTheFence() {
        Band band = lockedBand();
        BandMember member = member(band, memberId, BandRole.MEMBER, BandMemberShipStatus.PENDING);
        when(bandEntityFinder.getBandMember(bandId, memberId)).thenReturn(member);

        service.rejectInvite(bandId, memberId);

        var order = inOrder(bandRepository, bandEntityFinder, bandMemberRepository);
        order.verify(bandRepository).findByIdForUpdate(bandId);
        order.verify(bandEntityFinder).getBandMember(bandId, memberId);
        order.verify(bandMemberRepository).save(member);
        assertThat(member.getStatus()).isEqualTo(BandMemberShipStatus.REJECTED);
        verifyNoInteractions(eventMemberPublicationRepository);
    }

    @ParameterizedTest
    @ValueSource(strings = {"invite", "accept", "reject", "remove", "leave"})
    void missingBandFailsBeforeAnyMembershipOrPublicationAccess(String action) {
        assertThatThrownBy(() -> action(action)).isInstanceOf(SoundConnectException.class);
        verify(bandRepository).findByIdForUpdate(bandId);
        verifyNoInteractions(bandMemberRepository, bandEntityFinder, userEntityFinder, eventMemberPublicationRepository);
    }

    @ParameterizedTest
    @EnumSource(value = BandMemberShipStatus.class, names = {"ACTIVE", "LEFT", "REJECTED"})
    void acceptingANonpendingMembershipCannotResetItsEventChoices(BandMemberShipStatus status) {
        Band band = lockedBand();
        BandMember member = member(band, memberId, BandRole.MEMBER, status);
        when(bandEntityFinder.getBandMember(bandId, memberId)).thenReturn(member);
        assertThatThrownBy(() -> service.acceptInvite(bandId, memberId)).isInstanceOf(SoundConnectException.class);
        assertThat(member.getStatus()).isEqualTo(status);
        verifyNoInteractions(eventMemberPublicationRepository, notificationProducer);
        verify(bandMemberRepository, never()).save(any());
    }

    @Test
    void aFounderCannotLeaveAndCannotErasePublicationAsASideEffect() {
        Band band = lockedBand();
        BandMember founder = member(band, founderId, BandRole.FOUNDER, BandMemberShipStatus.ACTIVE);
        when(bandEntityFinder.getBandMember(bandId, founderId)).thenReturn(founder);
        assertThatThrownBy(() -> service.leaveBand(bandId, founderId)).isInstanceOf(SoundConnectException.class);
        assertThat(founder.getStatus()).isEqualTo(BandMemberShipStatus.ACTIVE);
        verifyNoInteractions(eventMemberPublicationRepository, notificationProducer);
    }

    @Test
    void ordinaryMemberCannotRemoveAnotherMemberOrResetTheirPublication() {
        Band band = lockedBand();
        BandMember requester = member(band, founderId, BandRole.MEMBER, BandMemberShipStatus.ACTIVE);
        when(bandMemberRepository.findByBandIdAndUserId(bandId, founderId)).thenReturn(Optional.of(requester));
        assertThatThrownBy(() -> service.removeMember(bandId, founderId, memberId)).isInstanceOf(SoundConnectException.class);
        verify(bandMemberRepository, never()).findByBandIdAndUserId(bandId, memberId);
        verifyNoInteractions(eventMemberPublicationRepository, notificationProducer);
    }

    @Test
    void anotherFounderCannotBeRemovedOrHavePersonalPublicationReset() {
        Band band = lockedBand();
        BandMember requester = member(band, founderId, BandRole.FOUNDER, BandMemberShipStatus.ACTIVE);
        BandMember target = member(band, memberId, BandRole.FOUNDER, BandMemberShipStatus.ACTIVE);
        when(bandMemberRepository.findByBandIdAndUserId(bandId, founderId)).thenReturn(Optional.of(requester));
        when(bandMemberRepository.findByBandIdAndUserId(bandId, memberId)).thenReturn(Optional.of(target));
        assertThatThrownBy(() -> service.removeMember(bandId, founderId, memberId)).isInstanceOf(SoundConnectException.class);
        verifyNoInteractions(eventMemberPublicationRepository, notificationProducer);
    }

    @ParameterizedTest
    @ValueSource(strings = {"accept", "leave"})
    void publicationResetFailureDoesNotApplyMembershipTransition(String action) {
        Band band = lockedBand();
        BandMemberShipStatus before = action.equals("accept") ? BandMemberShipStatus.PENDING : BandMemberShipStatus.ACTIVE;
        BandMember member = member(band, memberId, BandRole.MEMBER, before);
        when(bandEntityFinder.getBandMember(bandId, memberId)).thenReturn(member);
        when(eventMemberPublicationRepository.hideForBandMember(bandId, memberId)).thenThrow(new IllegalStateException("Write failed"));
        assertThatThrownBy(() -> action(action)).isInstanceOf(IllegalStateException.class);
        assertThat(member.getStatus()).isEqualTo(before);
        verify(bandMemberRepository, never()).save(any());
        verifyNoInteractions(notificationProducer);
    }

    private void action(String action) {
        switch (action) {
            case "invite" -> service.inviteMember(bandId, founderId, memberId, null);
            case "accept" -> service.acceptInvite(bandId, memberId);
            case "reject" -> service.rejectInvite(bandId, memberId);
            case "remove" -> service.removeMember(bandId, founderId, memberId);
            case "leave" -> service.leaveBand(bandId, memberId);
            default -> throw new IllegalArgumentException(action);
        }
    }

    private Band lockedBand() {
        Band band = new Band();
        band.setId(bandId);
        band.setName("Şahbaz");
        when(bandRepository.findByIdForUpdate(bandId)).thenReturn(Optional.of(band));
        return band;
    }

    private BandMember member(Band band, UUID userId, BandRole role, BandMemberShipStatus status) {
        return BandMember.builder().band(band).user(user(userId)).bandRole(role).status(status).build();
    }

    private User user(UUID id) {
        User user = new User();
        user.setId(id);
        user.setUsername("member-" + id);
        return user;
    }
}

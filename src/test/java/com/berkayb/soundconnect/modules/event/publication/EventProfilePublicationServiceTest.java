package com.berkayb.soundconnect.modules.event.publication;

import com.berkayb.soundconnect.modules.event.entity.Event;
import com.berkayb.soundconnect.modules.event.enums.*;
import com.berkayb.soundconnect.modules.event.repository.EventRepository;
import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.calendar.BandCalendarSettingsRepository;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity.Band;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.calendar.repository.MusicianCalendarSettingsRepository;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.entity.MusicianProfile;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.venue.entity.Venue;
import com.berkayb.soundconnect.shared.exception.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EventProfilePublicationServiceTest {
    @Mock EventProfilePublicationRepository publications;
    @Mock EventMemberPublicationRepository memberPublications;
    @Mock EventRepository events;
    @Mock MusicianCalendarSettingsRepository profiles;
    @Mock BandCalendarSettingsRepository bands;
    @Mock MediaAssetService media;
    @Mock com.berkayb.soundconnect.modules.event.support.EventScheduleClock scheduleClock;
    @InjectMocks EventProfilePublicationService service;
    final UUID userId = UUID.randomUUID(), profileId = UUID.randomUUID(), eventId = UUID.randomUUID(), bandId = UUID.randomUUID();

    @Test void pagedMemberChoicesAreFetchedOnceAndHiddenDefaultsDoNotInheritBandPublication() {
        var now = java.time.LocalDateTime.of(2026, 9, 6, 12, 0);
        when(scheduleClock.localNow()).thenReturn(now);
        when(profiles.lockOwnedProfileForRead(userId)).thenReturn(Optional.of(profileId));
        UUID secondId = UUID.randomUUID();
        var pageable = org.springframework.data.domain.PageRequest.of(1, 2);
        when(publications.findForMusicianPeriod(profileId, "CURRENT", now.toLocalDate(), now.toLocalTime(),
                java.time.LocalTime.MIDNIGHT, now.toLocalDate().plusDays(6), pageable))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(eventId, secondId), pageable, 4));
        Band band = new Band(); band.setId(bandId); band.setName("Şahbaz");
        Venue venue = new Venue(); venue.setId(UUID.randomUUID()); venue.setName("Mekan");
        Event first = Event.builder().title("First").venue(venue).band(band).profileCalendarApproved(true).build(); first.setId(eventId);
        Event second = Event.builder().title("Second").venue(venue).band(band).profileCalendarApproved(true).build(); second.setId(secondId);
        when(publications.findMusicianDetails(List.of(eventId, secondId), profileId)).thenReturn(List.of(second, first));
        EventMemberPublication selected = new EventMemberPublication(eventId, profileId); selected.setVisible(true); selected.setVersion(2);
        when(memberPublications.findPageChoices(eq(profileId), any())).thenReturn(List.of(selected));
        var result = service.getMine(userId, PerformerType.MUSICIAN, profileId, 1, 2, EventPublicationPeriod.CURRENT);
        assertThat(result.content()).extracting(EventProfilePublicationDto::eventId).containsExactly(eventId, secondId);
        assertThat(result.content()).extracting(EventProfilePublicationDto::visible).containsExactly(true, false);
        assertThat(result.totalElements()).isEqualTo(4);
        assertThat(result.page()).isEqualTo(1);
        verify(memberPublications).findPageChoices(eq(profileId), any());
        verify(memberPublications, never()).findById(any());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void directPublicationChangesWithoutTouchingParticipation(boolean visible) {
        Event event = direct(!visible, 2);
        var dto = service.update(userId, eventId, update(visible, 2));
        assertThat(dto.visible()).isEqualTo(visible);
        assertThat(dto.version()).isEqualTo(3);
        assertThat(event.getPerformerApprovalStatus()).isEqualTo(EventPerformerApprovalStatus.APPROVED);
        assertThat(event.getMusicianProfile().getId()).isEqualTo(profileId);
        verify(events).saveAndFlush(event);
        verifyNoInteractions(memberPublications);
    }

    @Test void sameValueAndExactRetryNeverWriteAgain() {
        direct(true, 2);
        assertThat(service.update(userId, eventId, update(true, 2)).version()).isEqualTo(2);
        assertThat(service.update(userId, eventId, update(true, 1)).version()).isEqualTo(2);
        verify(events, never()).saveAndFlush(any());
    }

    @ParameterizedTest @CsvSource({"false,0", "true,0", "false,1", "true,3"})
    void staleOrFutureUpdateCannotOverwrite(boolean visible, long version) {
        direct(true, 2);
        assertThatThrownBy(() -> service.update(userId, eventId, update(visible, version)))
                .isInstanceOf(SoundConnectException.class).extracting("errorType").isEqualTo(ErrorType.MUSICIAN_CALENDAR_VERSION_CONFLICT);
        verify(events, never()).saveAndFlush(any());
    }

    @Test void memberMustExplicitlyPublishAndDoesNotChangeBandChoice() {
        Event event = direct(false, 4);
        Band band = new Band(); band.setId(bandId); band.setName("Şahbaz");
        event.setMusicianProfile(null); event.setBand(band);
        when(publications.findBandId(eventId)).thenReturn(Optional.of(bandId));
        when(bands.lockBandForUpdate(bandId)).thenReturn(Optional.of(bandId));
        EventMemberPublication member = new EventMemberPublication(eventId, profileId);
        when(memberPublications.findById(any())).thenReturn(Optional.of(member));
        var dto = service.update(userId, eventId, update(true, 0));
        assertThat(dto.visible()).isTrue(); assertThat(dto.version()).isEqualTo(1);
        assertThat(dto.performerName()).isEqualTo("Şahbaz");
        assertThat(event.isProfileCalendarApproved()).isFalse();
        assertThat(event.getProfilePublicationVersion()).isEqualTo(4);
        verify(events, never()).saveAndFlush(any());
        verify(memberPublications).saveAndFlush(member);
        var order = inOrder(bands, profiles, events);
        order.verify(bands).lockBandForUpdate(bandId);
        order.verify(profiles).lockOwnedProfileForUpdate(userId);
        order.verify(events).findByIdForUpdate(eventId);
    }

    @Test void outsiderCannotLockOrMutateAnEvent() {
        assertThatThrownBy(() -> service.update(userId, eventId, update(true, 0))).isInstanceOf(SoundConnectException.class);
        verifyNoInteractions(publications, events, memberPublications, bands);
    }

    @Test void pendingRejectedParticipationOrInactiveMembershipCannotBeManaged() {
        when(profiles.findOwnedProfileId(userId)).thenReturn(Optional.of(profileId));
        assertThatThrownBy(() -> service.update(userId, eventId, update(true, 0)))
                .isInstanceOf(SoundConnectException.class).extracting("errorType").isEqualTo(ErrorType.EVENT_NOT_FOUND);
        verifyNoInteractions(events, memberPublications, bands);
    }

    @Test void eligibilityIsRecheckedAfterLocks() {
        when(profiles.findOwnedProfileId(userId)).thenReturn(Optional.of(profileId));
        when(profiles.lockOwnedProfileForUpdate(userId)).thenReturn(Optional.of(profileId));
        when(publications.eligibleForMusician(eventId, profileId)).thenReturn(true, false);
        when(events.findByIdForUpdate(eventId)).thenReturn(Optional.of(new Event()));
        assertThatThrownBy(() -> service.update(userId, eventId, update(true, 0))).isInstanceOf(SoundConnectException.class);
        verify(events, never()).saveAndFlush(any());
    }

    @ParameterizedTest @CsvSource({"-1,20", "101,20", "0,0", "0,51"})
    void paginationIsBoundedBeforeLocks(int page, int size) {
        assertThatThrownBy(() -> service.getMine(userId, PerformerType.MUSICIAN, profileId, page, size)).isInstanceOf(SoundConnectException.class);
        verifyNoInteractions(publications, profiles, bands);
    }

    @ParameterizedTest @ValueSource(longs = {-1, Long.MAX_VALUE})
    void invalidVersionNeverTouchesDatabase(long version) {
        assertThatThrownBy(() -> service.update(userId, eventId, update(true, version))).isInstanceOf(SoundConnectException.class);
        verifyNoInteractions(publications, profiles, bands, events);
    }

    private EventProfilePublicationUpdate update(boolean visible, long version) { return new EventProfilePublicationUpdate(PerformerType.MUSICIAN, profileId, visible, version); }
    private Event direct(boolean visible, long version) {
        User user = new User(); user.setId(userId); user.setUsername("bugrasahin");
        MusicianProfile profile = new MusicianProfile(); profile.setId(profileId); profile.setUser(user);
        Venue venue = new Venue(); venue.setId(UUID.randomUUID()); venue.setName("SoundConnect");
        Event event = Event.builder().title("Etkinlik").venue(venue).musicianProfile(profile)
                .performerApprovalStatus(EventPerformerApprovalStatus.APPROVED).profileCalendarApproved(visible).profilePublicationVersion(version).build();
        event.setId(eventId);
        when(profiles.findOwnedProfileId(userId)).thenReturn(Optional.of(profileId));
        when(profiles.lockOwnedProfileForUpdate(userId)).thenReturn(Optional.of(profileId));
        when(publications.eligibleForMusician(eventId, profileId)).thenReturn(true);
        when(events.findByIdForUpdate(eventId)).thenReturn(Optional.of(event));
        return event;
    }
}

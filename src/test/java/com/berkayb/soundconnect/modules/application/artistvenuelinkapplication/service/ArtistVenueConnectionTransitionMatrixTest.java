package com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.service;

import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.dto.response.ArtistVenueConnectionRequestResponseDto;
import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.entity.ArtistVenueConnectionRequest;
import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.enums.*;
import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.mapper.ArtistVenueConnectionRequestMapper;
import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.repository.ArtistVenueConnectionRequestRepository;
import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.notification.service.TransactionalNotificationService;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity.*;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.enums.*;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.repository.*;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.entity.MusicianProfile;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.repository.MusicianProfileRepository;
import com.berkayb.soundconnect.modules.profile.VenueProfile.repository.VenueProfileRepository;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.venue.entity.Venue;
import com.berkayb.soundconnect.modules.venue.repository.VenueRepository;
import com.berkayb.soundconnect.shared.exception.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.*;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ArtistVenueConnectionTransitionMatrixTest {
    @Mock ArtistVenueConnectionRequestRepository requests;
    @Mock MusicianProfileRepository musicians;
    @Mock VenueRepository venues;
    @Mock ArtistVenueConnectionRequestMapper mapper;
    @Mock BandRepository bands;
    @Mock BandMemberRepository members;
    @Mock VenueProfileRepository venueProfiles;
    @Mock MediaAssetService media;
    @Mock TransactionalNotificationService notifications;
    @InjectMocks ArtistVenueConnectionRequestServiceImpl service;

    enum Direction { MUSICIAN_TO_VENUE, BAND_TO_VENUE, VENUE_TO_MUSICIAN, VENUE_TO_BAND }
    enum Action { ACCEPT, REJECT, CANCEL, DISCONNECT }
    enum Side { SENDER, RECIPIENT, OUTSIDER }

    static Stream<Arguments> transitions() {
        return Arrays.stream(Direction.values()).flatMap(direction -> Arrays.stream(RequestStatus.values())
                .flatMap(status -> Arrays.stream(Action.values()).flatMap(action -> Arrays.stream(Side.values())
                        .map(side -> Arguments.of(direction, status, action, side)))));
    }

    @ParameterizedTest(name = "{0}: {1} + {2} by {3}")
    @MethodSource("transitions")
    void transitionRequiresTheCorrectSideAndLeavesUnrelatedIdentityUntouched(
            Direction direction, RequestStatus initial, Action action, Side side) {
        Fixture fixture = fixture(direction, initial);
        UUID actor = switch (side) {
            case SENDER -> fixture.sender();
            case RECIPIENT -> fixture.recipient();
            case OUTSIDER -> UUID.randomUUID();
        };
        boolean authorized = side != Side.OUTSIDER && (action == Action.DISCONNECT
                || (action == Action.CANCEL ? side == Side.SENDER : side == Side.RECIPIENT));
        RequestStatus allowedInitial = action == Action.DISCONNECT ? RequestStatus.ACCEPTED : RequestStatus.PENDING;
        boolean succeeds = authorized && initial == allowedInitial;

        if (succeeds) {
            ArtistVenueConnectionRequestResponseDto result = perform(action, actor, fixture.request().getId());
            RequestStatus finalStatus = action == Action.ACCEPT ? RequestStatus.ACCEPTED : RequestStatus.REJECTED;
            assertThat(result.status()).isEqualTo(finalStatus.name());
            assertThat(fixture.request().getStatus()).isEqualTo(finalStatus);
            verify(requests).save(fixture.request());
        } else {
            ErrorType error = !authorized ? ErrorType.FORBIDDEN_ACCESS : conflict(initial, action);
            assertThatThrownBy(() -> perform(action, actor, fixture.request().getId()))
                    .isInstanceOfSatisfying(SoundConnectException.class, failure -> assertThat(failure.getErrorType()).isEqualTo(error));
            assertThat(fixture.request().getStatus()).isEqualTo(initial);
            verify(requests, never()).save(any());
        }

        boolean connected = succeeds ? action == Action.ACCEPT : initial == RequestStatus.ACCEPTED;
        if (fixture.bandTarget()) {
            assertThat(fixture.venue().getActiveBands().contains(fixture.band())).isEqualTo(connected);
            assertThat(fixture.band().getActiveVenues().contains(fixture.venue())).isEqualTo(connected);
            assertThat(fixture.musician().getActiveVenues()).isEmpty();
            assertThat(fixture.venue().getActiveMusicians()).isEmpty();
            verify(musicians, never()).save(any());
        } else {
            assertThat(fixture.musician().getActiveVenues().contains(fixture.venue())).isEqualTo(connected);
            assertThat(fixture.venue().getActiveMusicians().contains(fixture.musician())).isEqualTo(connected);
            assertThat(fixture.venue().getActiveBands()).isEmpty();
        }
        if (succeeds && (action == Action.ACCEPT || action == Action.REJECT)) {
            verify(notifications).persistInCurrentTransaction(argThat(event -> event.recipientId().equals(fixture.sender())
                    && event.eventId() != null && Boolean.FALSE.equals(event.emailForce())
                    && event.payload().get("requestId").equals(fixture.request().getId().toString())
                    && (fixture.bandTarget() ? event.payload().containsKey("bandId") && !event.payload().containsKey("musicianProfileId")
                    : event.payload().containsKey("musicianProfileId") && !event.payload().containsKey("bandId"))));
        } else {
            verifyNoInteractions(notifications);
        }
    }

    @ParameterizedTest @EnumSource(Action.class)
    void missingRequestCannotMutateAnyRelationship(Action action) {
        assertThatThrownBy(() -> perform(action, UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOfSatisfying(SoundConnectException.class,
                        failure -> assertThat(failure.getErrorType()).isEqualTo(ErrorType.REQUEST_NOT_FOUND));
        verifyNoInteractions(venues, musicians, notifications);
        verify(requests, never()).save(any());
    }

    private ErrorType conflict(RequestStatus status, Action action) {
        if (status == RequestStatus.REJECTED) return ErrorType.REQUEST_ALREADY_REJECTED;
        if (action == Action.CANCEL) return ErrorType.REQUEST_CANCEL_NOT_ALLOWED;
        if (action == Action.DISCONNECT) return ErrorType.REQUEST_DISCONNECT_NOT_ALLOWED;
        return ErrorType.REQUEST_ALREADY_ACCEPTED;
    }

    private ArtistVenueConnectionRequestResponseDto perform(Action action, UUID actor, UUID request) {
        return switch (action) {
            case ACCEPT -> service.acceptRequest(actor, request);
            case REJECT -> service.rejectRequest(actor, request);
            case CANCEL -> service.cancelRequest(actor, request);
            case DISCONNECT -> service.disconnect(actor, request);
        };
    }

    private Fixture fixture(Direction direction, RequestStatus initial) {
        boolean bandTarget = direction == Direction.BAND_TO_VENUE || direction == Direction.VENUE_TO_BAND;
        boolean fromVenue = direction == Direction.VENUE_TO_BAND || direction == Direction.VENUE_TO_MUSICIAN;
        UUID artistId = UUID.randomUUID(), ownerId = UUID.randomUUID(), venueId = UUID.randomUUID(), bandId = UUID.randomUUID();
        User artist = User.builder().id(artistId).username("artist").build();
        User owner = User.builder().id(ownerId).username("owner").build();
        MusicianProfile musician = mock(MusicianProfile.class);
        Venue venue = mock(Venue.class);
        Band band = mock(Band.class);
        when(musician.getId()).thenReturn(UUID.randomUUID());
        when(musician.getUser()).thenReturn(artist);
        when(musician.getActiveVenues()).thenReturn(new HashSet<>());
        when(venue.getId()).thenReturn(venueId);
        when(venue.getOwner()).thenReturn(owner);
        when(venues.existsPubliclyVisibleById(venueId)).thenReturn(true);
        when(venue.getActiveBands()).thenReturn(new HashSet<>());
        when(venue.getActiveMusicians()).thenReturn(new HashSet<>());
        when(band.getId()).thenReturn(bandId);
        when(band.getActiveVenues()).thenReturn(new HashSet<>());
        BandMember founder = BandMember.builder().id(UUID.randomUUID()).band(band).user(artist)
                .status(BandMemberShipStatus.ACTIVE).bandRole(BandRole.FOUNDER).build();
        when(band.getMembers()).thenReturn(Set.of(founder));
        when(members.findByBandId(bandId)).thenReturn(List.of(founder));
        when(bands.findByIdForUpdate(bandId)).thenReturn(Optional.of(band));
        if (initial == RequestStatus.ACCEPTED) {
            if (bandTarget) {
                venue.getActiveBands().add(band);
                band.getActiveVenues().add(venue);
            }
            else {
                venue.getActiveMusicians().add(musician);
                musician.getActiveVenues().add(venue);
            }
        }
        ArtistVenueConnectionRequest request = ArtistVenueConnectionRequest.builder().venue(venue)
                .band(bandTarget ? band : null).musicianProfile(bandTarget ? null : musician)
                .status(initial).requestByType(fromVenue ? RequestByType.VENUE : bandTarget ? RequestByType.BAND : RequestByType.ARTIST)
                .build();
        request.setId(UUID.randomUUID());
        when(requests.findBandIdByRequestId(request.getId())).thenReturn(bandTarget ? Optional.of(bandId) : Optional.empty());
        when(requests.findByIdForUpdate(request.getId())).thenReturn(Optional.of(request));
        when(requests.findVenueByIdForUpdate(venueId)).thenReturn(Optional.of(venue));
        when(requests.lockUsableAccountIds(anyCollection())).thenAnswer(invocation -> new ArrayList<>(invocation.<Collection<UUID>>getArgument(0)));
        when(mapper.toResponseDto(request)).thenAnswer(ignored -> new ArtistVenueConnectionRequestResponseDto(request.getId(),
                bandTarget ? null : musician.getId(), bandTarget ? bandId : null, venueId, "Musician", "Band", null,
                "Venue", null, request.getStatus().name(), request.getRequestByType(), null));
        return new Fixture(request, musician, band, venue, bandTarget, fromVenue ? ownerId : artistId, fromVenue ? artistId : ownerId);
    }

    private record Fixture(ArtistVenueConnectionRequest request, MusicianProfile musician, Band band, Venue venue,
                           boolean bandTarget, UUID sender, UUID recipient) {}
}

package com.berkayb.soundconnect.modules.studio.reservation.service;

import com.berkayb.soundconnect.modules.profile.StudioProfile.entity.StudioProfile;
import com.berkayb.soundconnect.modules.profile.StudioProfile.repository.StudioProfileRepository;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.enums.ListenerVisibilityMode;
import com.berkayb.soundconnect.modules.profile.shared.identity.GhostListenerIdentity;
import com.berkayb.soundconnect.modules.profile.shared.identity.GhostListenerIdentityBatchResolver;
import com.berkayb.soundconnect.modules.studio.reservation.dto.request.StudioReservationCreateRequest;
import com.berkayb.soundconnect.modules.studio.reservation.dto.request.StudioManualBlockCreateRequest;
import com.berkayb.soundconnect.modules.studio.reservation.dto.request.StudioManualBlockReleaseRequest;
import com.berkayb.soundconnect.modules.studio.reservation.dto.request.StudioVersionRequest;
import com.berkayb.soundconnect.modules.studio.reservation.entity.StudioRoomOccupancy;
import com.berkayb.soundconnect.modules.studio.reservation.entity.StudioRoomReservation;
import com.berkayb.soundconnect.modules.studio.reservation.enums.StudioReservationStatus;
import com.berkayb.soundconnect.modules.studio.reservation.enums.StudioOccupancyType;
import com.berkayb.soundconnect.modules.studio.reservation.event.StudioReservationNotificationEvent;
import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.modules.studio.reservation.repository.StudioRoomOccupancyRepository;
import com.berkayb.soundconnect.modules.studio.reservation.repository.StudioRoomReservationRepository;
import com.berkayb.soundconnect.modules.studio.reservation.support.StudioBookingWindow;
import com.berkayb.soundconnect.modules.studio.reservation.support.StudioBookingClock;
import com.berkayb.soundconnect.modules.studio.reservation.support.StudioDateRange;
import com.berkayb.soundconnect.modules.studio.reservation.support.StudioReservationTimeProvider;
import com.berkayb.soundconnect.modules.studio.room.entity.StudioRoom;
import com.berkayb.soundconnect.modules.studio.room.mapper.StudioRoomMapper;
import com.berkayb.soundconnect.modules.studio.room.repository.StudioRoomRepository;
import com.berkayb.soundconnect.modules.studio.room.service.StudioRoomDailyMetricsService;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StudioReservationServiceTest {
    @Mock UserRepository userRepository;
    @Mock StudioProfileRepository studioProfileRepository;
    @Mock StudioRoomRepository roomRepository;
    @Mock StudioRoomReservationRepository reservationRepository;
    @Mock StudioRoomOccupancyRepository occupancyRepository;
    @Mock StudioReservationTimeProvider timeProvider;
    @Mock StudioRoomMapper roomMapper;
    @Mock StudioRoomDailyMetricsService dailyMetricsService;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock GhostListenerIdentityBatchResolver ghostListenerIdentityBatchResolver;
    @InjectMocks StudioReservationService service;

    private UUID requesterId;
    private UUID ownerId;
    private UUID roomId;
    private User requester;
    private StudioProfile profile;
    private StudioRoom room;
    private StudioBookingWindow window;

    @BeforeEach
    void setUp() {
        requesterId = UUID.randomUUID();
        ownerId = UUID.randomUUID();
        roomId = UUID.randomUUID();
        requester = User.builder().id(requesterId).username("guest").build();
        User owner = User.builder().id(ownerId).build();
        profile = StudioProfile.builder()
                .id(UUID.randomUUID())
                .user(owner)
                .timeZone("Europe/Istanbul")
                .build();
        room = StudioRoom.builder()
                .id(roomId)
                .studioProfile(profile)
                .name("Prova odasi")
                .hourlyPriceMinor(1_000L)
                .currency("TRY")
                .build();
        window = new StudioBookingWindow(
                Instant.parse("2026-07-22T10:00:00Z"),
                Instant.parse("2026-07-22T12:00:00Z")
        );
        lenient().when(timeProvider.zoneOf(any())).thenReturn(ZoneId.of("Europe/Istanbul"));
        lenient().when(timeProvider.now()).thenReturn(Instant.parse("2026-07-21T10:00:00Z"));
        lenient().when(ghostListenerIdentityBatchResolver.resolve(any())).thenReturn(Map.of());
    }

    @Test
    void autoConfirmedReservationCreatesExclusiveOccupancyInSameCall() {
        arrangeCreate(false);
        when(occupancyRepository.existsActiveOverlap(roomId, window.startsAt(), window.endsAt()))
                .thenReturn(false);
        when(reservationRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            StudioRoomReservation reservation = invocation.getArgument(0);
            reservation.setId(UUID.randomUUID());
            return reservation;
        });
        when(occupancyRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.create(requesterId, createRequest());

        assertThat(response.status()).isEqualTo(StudioReservationStatus.CONFIRMED);
        assertThat(response.totalPriceMinor()).isEqualTo(2_000L);
        assertThat(response.localDate()).isEqualTo(LocalDate.of(2026, 7, 22));
		assertThat(response.localStartTime()).isEqualTo(LocalTime.of(13, 0));
		assertThat(response.localEndTime()).isEqualTo(LocalTime.of(15, 0));
        ArgumentCaptor<StudioRoomReservation> reservationCaptor =
                ArgumentCaptor.forClass(StudioRoomReservation.class);
        verify(reservationRepository).saveAndFlush(reservationCaptor.capture());
        assertThat(reservationCaptor.getValue().getContactPhoneSnapshot())
                .isEqualTo("05551112233");
        ArgumentCaptor<StudioRoomOccupancy> captor = ArgumentCaptor.forClass(StudioRoomOccupancy.class);
        verify(occupancyRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().isActive()).isTrue();
        assertThat(captor.getValue().getStartsAt()).isEqualTo(window.startsAt());
        assertThat(captor.getValue().getEndsAt()).isEqualTo(window.endsAt());
        ArgumentCaptor<StudioReservationNotificationEvent> eventCaptor =
                ArgumentCaptor.forClass(StudioReservationNotificationEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().recipientId()).isEqualTo(ownerId);
        assertThat(eventCaptor.getValue().type())
                .isEqualTo(NotificationType.STUDIO_RESERVATION_CREATED);
    }

    @Test
    void approvalRequiredRequestDoesNotReserveTheCalendar() {
        arrangeCreate(true);
        when(reservationRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            StudioRoomReservation reservation = invocation.getArgument(0);
            reservation.setId(UUID.randomUUID());
            return reservation;
        });

        var response = service.create(requesterId, createRequest());

        assertThat(response.status()).isEqualTo(StudioReservationStatus.PENDING_APPROVAL);
        verify(occupancyRepository, never()).existsActiveOverlap(any(), any(), any());
        verify(occupancyRepository, never()).saveAndFlush(any());
    }

    @Test
    void secondOverlappingPendingRequestNotifiesOwnerAboutTheConflict() {
        arrangeCreate(true);
        when(reservationRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            StudioRoomReservation reservation = invocation.getArgument(0);
            reservation.setId(UUID.randomUUID());
            return reservation;
        });
        when(reservationRepository.countPendingOverlap(
                roomId,
                StudioReservationStatus.PENDING_APPROVAL,
                Instant.parse("2026-07-21T10:00:00Z"),
                window.startsAt(),
                window.endsAt()
        )).thenReturn(2L);

        service.create(requesterId, createRequest());

        ArgumentCaptor<StudioReservationNotificationEvent> eventCaptor =
                ArgumentCaptor.forClass(StudioReservationNotificationEvent.class);
        verify(eventPublisher, org.mockito.Mockito.times(2))
                .publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getAllValues())
                .extracting(StudioReservationNotificationEvent::type)
                .containsExactly(
                        NotificationType.STUDIO_RESERVATION_CREATED,
                        NotificationType.STUDIO_RESERVATION_CONFLICTING_REQUESTS
                );
        assertThat(eventCaptor.getAllValues().get(1).message())
                .contains("2");
    }

    @Test
    void approvingRequestRejectsEveryOverlappingPendingRequestAndNotifiesItsRequester() {
        UUID approvedReservationId = UUID.randomUUID();
        User competingRequester = User.builder()
                .id(UUID.randomUUID())
                .username("other-guest")
                .build();
        StudioRoomReservation approved = pendingReservation(
                approvedReservationId,
                requester
        );
        StudioRoomReservation competing = pendingReservation(
                UUID.randomUUID(),
                competingRequester
        );
        when(studioProfileRepository.findByUserIdForUpdate(ownerId))
                .thenReturn(Optional.of(profile));
        when(roomRepository.findActiveByIdAndStudioProfileIdForUpdate(roomId, profile.getId()))
                .thenReturn(Optional.of(room));
        when(reservationRepository.findByIdAndRoomIdForUpdate(approvedReservationId, roomId))
                .thenReturn(Optional.of(approved));
        when(occupancyRepository.existsActiveOverlap(
                roomId,
                window.startsAt(),
                window.endsAt()
        )).thenReturn(false);
        when(reservationRepository.findOverlappingPendingForUpdate(
                roomId,
                approvedReservationId,
                StudioReservationStatus.PENDING_APPROVAL,
                Instant.parse("2026-07-21T10:00:00Z"),
                window.startsAt(),
                window.endsAt()
        )).thenReturn(List.of(competing));
        when(reservationRepository.saveAndFlush(approved)).thenReturn(approved);
        when(occupancyRepository.saveAndFlush(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.approve(
                ownerId,
                roomId,
                approvedReservationId,
                new StudioVersionRequest(0L)
        );

        assertThat(response.status()).isEqualTo(StudioReservationStatus.CONFIRMED);
        assertThat(competing.getStatus())
                .isEqualTo(StudioReservationStatus.REJECTED_BY_STUDIO);
        assertThat(competing.getDecidedBy()).isEqualTo(ownerId);
        verify(reservationRepository).saveAllAndFlush(List.of(competing));
        ArgumentCaptor<StudioReservationNotificationEvent> eventCaptor =
                ArgumentCaptor.forClass(StudioReservationNotificationEvent.class);
        verify(eventPublisher, org.mockito.Mockito.times(2))
                .publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getAllValues())
                .extracting(StudioReservationNotificationEvent::recipientId)
                .containsExactly(competingRequester.getId(), requesterId);
        assertThat(eventCaptor.getAllValues())
                .extracting(StudioReservationNotificationEvent::type)
                .containsExactly(
                        NotificationType.STUDIO_RESERVATION_REJECTED,
                        NotificationType.STUDIO_RESERVATION_APPROVED
                );
        assertThat(eventCaptor.getAllValues().get(0).payload())
                .containsEntry("action", "AUTO_REJECTED_CONFLICT");
        assertThat(eventCaptor.getAllValues().get(1).payload())
                .containsEntry("reservationId", approvedReservationId.toString())
                .containsEntry("action", "APPROVED")
                .containsEntry("status", StudioReservationStatus.CONFIRMED.name());
    }

    @Test
    void rejectsSelfBooking() {
        room.getStudioProfile().setUser(User.builder().id(requesterId).build());
        arrangeCreate(false);

        assertThatThrownBy(() -> service.create(requesterId, createRequest()))
                .isInstanceOf(SoundConnectException.class)
                .extracting(exception -> ((SoundConnectException) exception).getErrorType())
                .isEqualTo(ErrorType.STUDIO_RESERVATION_SELF_NOT_ALLOWED);
        verify(reservationRepository, never()).saveAndFlush(any());
    }

    @Test
    void rejectsOverlappingRequestFromTheSameCustomerBeforeCreatingMorePendingSpam() {
        arrangeCreate(true);
        Instant now = Instant.parse("2026-07-21T10:00:00Z");
        when(reservationRepository.existsActiveRequesterOverlap(
                requesterId,
                roomId,
                List.of(
                        StudioReservationStatus.PENDING_APPROVAL,
                        StudioReservationStatus.CONFIRMED
                ),
                StudioReservationStatus.PENDING_APPROVAL,
                window.startsAt(),
                window.endsAt(),
                now
        )).thenReturn(true);

        assertThatThrownBy(() -> service.create(requesterId, createRequest()))
                .isInstanceOf(SoundConnectException.class)
                .extracting(exception -> ((SoundConnectException) exception).getErrorType())
                .isEqualTo(ErrorType.STUDIO_RESERVATION_REQUESTER_OVERLAP);

        verify(reservationRepository).expireStartedPendingRequests(
                requesterId,
                roomId,
                StudioReservationStatus.PENDING_APPROVAL,
                StudioReservationStatus.EXPIRED,
                now
        );
        verify(reservationRepository, never()).saveAndFlush(any());
        verify(occupancyRepository, never()).saveAndFlush(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void rejectsReservationPhoneThatDoesNotStartWithDomesticZero() {
        StudioReservationCreateRequest request = new StudioReservationCreateRequest(
                roomId,
                LocalDate.of(2026, 7, 22),
                LocalTime.of(13, 0),
                2,
                "+90 555 111 22 33",
                UUID.randomUUID()
        );

        assertThatThrownBy(() -> service.create(requesterId, request))
                .isInstanceOf(SoundConnectException.class)
                .extracting(exception -> ((SoundConnectException) exception).getErrorType())
                .isEqualTo(ErrorType.VALIDATION_ERROR);
        verify(userRepository, never()).findByIdForUpdate(any());
    }

    @Test
    void mapsAuthoritativeDatabaseOverlapToDomainConflict() {
        arrangeCreate(false);
        when(occupancyRepository.existsActiveOverlap(roomId, window.startsAt(), window.endsAt()))
                .thenReturn(false);
        when(reservationRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(occupancyRepository.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("exclusion constraint"));

        assertThatThrownBy(() -> service.create(requesterId, createRequest()))
                .isInstanceOf(SoundConnectException.class)
                .extracting(exception -> ((SoundConnectException) exception).getErrorType())
                .isEqualTo(ErrorType.STUDIO_RESERVATION_CONFLICT);
    }

    @Test
    void exactManualBlockRetryReturnsExistingOccupancyAfterRoomArchive() {
        UUID clientRequestId = UUID.randomUUID();
        StudioRoomOccupancy existing = StudioRoomOccupancy.builder()
                .id(UUID.randomUUID())
                .room(room)
                .type(StudioOccupancyType.MANUAL_BLOCK)
                .startsAt(window.startsAt())
                .endsAt(window.endsAt())
                .active(true)
                .createdBy(ownerId)
                .clientRequestId(clientRequestId)
                .build();
        room.setArchivedAt(Instant.parse("2026-07-23T10:00:00Z"));
        when(studioProfileRepository.findByUserIdForUpdate(ownerId)).thenReturn(Optional.of(profile));
        when(occupancyRepository.findByCreatedByAndClientRequestId(ownerId, clientRequestId))
                .thenReturn(Optional.of(existing));
        when(timeProvider.convertManualBlockWithoutFutureValidation(any(), any(), any(), any(Integer.class)))
                .thenReturn(window);

        var response = service.createManualBlock(
                ownerId,
                roomId,
                new StudioManualBlockCreateRequest(
                        LocalDate.of(2026, 7, 22),
                        LocalTime.of(13, 0),
                        2,
                        clientRequestId
                )
        );

        assertThat(response.id()).isEqualTo(existing.getId());
        assertThat(response.clientRequestId()).isEqualTo(clientRequestId);
        verify(occupancyRepository, never()).saveAndFlush(any());
        verify(roomRepository, never()).findActiveByIdForUpdate(any());
        verify(roomRepository, never()).findActiveByIdAndStudioProfileIdForUpdate(any(), any());
        verify(timeProvider, never()).validateManualBlockWindow(any(), any(), any(), any(Integer.class));
    }

    @Test
    void foreignManualBlockReturnsNotFoundWithoutLockingTheForeignRoom() {
        UUID foreignRoomId = UUID.randomUUID();
        when(studioProfileRepository.findByUserIdForUpdate(ownerId))
                .thenReturn(Optional.of(profile));
        when(roomRepository.findActiveByIdAndStudioProfileIdForUpdate(
                foreignRoomId, profile.getId()
        )).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createManualBlock(
                ownerId,
                foreignRoomId,
                new StudioManualBlockCreateRequest(
                        LocalDate.of(2026, 7, 22),
                        LocalTime.of(13, 0),
                        2,
                        UUID.randomUUID()
                )
        ))
                .isInstanceOf(SoundConnectException.class)
                .extracting(exception -> ((SoundConnectException) exception).getErrorType())
                .isEqualTo(ErrorType.STUDIO_ROOM_NOT_FOUND);

        verify(roomRepository).findActiveByIdAndStudioProfileIdForUpdate(
                foreignRoomId, profile.getId()
        );
        verify(roomRepository, never()).findActiveByIdForUpdate(any());
        verify(timeProvider, never()).validateManualBlockWindow(any(), any(), any(), any(Integer.class));
        verify(occupancyRepository, never()).existsActiveOverlap(any(), any(), any());
        verify(occupancyRepository, never()).saveAndFlush(any());
    }

    @Test
    void foreignOwnerApprovalReturnsNotFoundWithoutLockingRoomOrReservation() {
        UUID foreignRoomId = UUID.randomUUID();
        UUID foreignReservationId = UUID.randomUUID();
        when(studioProfileRepository.findByUserIdForUpdate(ownerId))
                .thenReturn(Optional.of(profile));
        when(roomRepository.findActiveByIdAndStudioProfileIdForUpdate(
                foreignRoomId, profile.getId()
        )).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.approve(
                ownerId,
                foreignRoomId,
                foreignReservationId,
                new StudioVersionRequest(0L)
        ))
                .isInstanceOf(SoundConnectException.class)
                .extracting(exception -> ((SoundConnectException) exception).getErrorType())
                .isEqualTo(ErrorType.STUDIO_ROOM_NOT_FOUND);

        verify(roomRepository).findActiveByIdAndStudioProfileIdForUpdate(
                foreignRoomId, profile.getId()
        );
        verify(roomRepository, never()).findActiveByIdForUpdate(any());
        verify(reservationRepository, never()).findById(any());
        verify(reservationRepository, never()).findByIdAndRoomIdForUpdate(any(), any());
        verify(occupancyRepository, never()).existsActiveOverlap(any(), any(), any());
    }

    @Test
    void ownedRoomWithForeignReservationNeverLocksTheForeignReservation() {
        UUID foreignReservationId = UUID.randomUUID();
        when(studioProfileRepository.findByUserIdForUpdate(ownerId))
                .thenReturn(Optional.of(profile));
        when(roomRepository.findActiveByIdAndStudioProfileIdForUpdate(roomId, profile.getId()))
                .thenReturn(Optional.of(room));
        when(reservationRepository.findByIdAndRoomIdForUpdate(foreignReservationId, roomId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.approve(
                ownerId,
                roomId,
                foreignReservationId,
                new StudioVersionRequest(0L)
        ))
                .isInstanceOf(SoundConnectException.class)
                .extracting(exception -> ((SoundConnectException) exception).getErrorType())
                .isEqualTo(ErrorType.STUDIO_RESERVATION_NOT_FOUND);

        verify(reservationRepository).findByIdAndRoomIdForUpdate(
                foreignReservationId, roomId
        );
        verify(occupancyRepository, never()).existsActiveOverlap(any(), any(), any());
        verify(reservationRepository, never()).saveAndFlush(any());
    }

    @Test
    void ownedRoomWithForeignManualBlockNeverLocksTheForeignOccupancy() {
        UUID foreignOccupancyId = UUID.randomUUID();
        when(studioProfileRepository.findByUserIdForUpdate(ownerId))
                .thenReturn(Optional.of(profile));
        when(roomRepository.findActiveByIdAndStudioProfileIdForUpdate(roomId, profile.getId()))
                .thenReturn(Optional.of(room));
        when(occupancyRepository.findByIdAndRoomIdForUpdate(foreignOccupancyId, roomId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.releaseManualBlock(
                ownerId,
                roomId,
                foreignOccupancyId,
                new StudioManualBlockReleaseRequest(0L, "test")
        ))
                .isInstanceOf(SoundConnectException.class)
                .extracting(exception -> ((SoundConnectException) exception).getErrorType())
                .isEqualTo(ErrorType.STUDIO_BLOCK_NOT_FOUND);

        verify(occupancyRepository).findByIdAndRoomIdForUpdate(
                foreignOccupancyId, roomId
        );
        verify(occupancyRepository, never()).saveAndFlush(any());
    }

    @Test
    void foreignOwnerScheduleReturnsNotFoundWithoutReadingTheForeignRoom() {
        UUID foreignRoomId = UUID.randomUUID();
        when(studioProfileRepository.findByUserId(ownerId)).thenReturn(Optional.of(profile));
        when(roomRepository.findActiveByIdAndStudioProfileId(
                foreignRoomId, profile.getId()
        )).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.ownerSchedule(
                ownerId,
                foreignRoomId,
                LocalDate.of(2026, 7, 22),
                LocalDate.of(2026, 7, 22),
                0,
                20
        ))
                .isInstanceOf(SoundConnectException.class)
                .extracting(exception -> ((SoundConnectException) exception).getErrorType())
                .isEqualTo(ErrorType.STUDIO_ROOM_NOT_FOUND);

        verify(roomRepository).findActiveByIdAndStudioProfileId(
                foreignRoomId, profile.getId()
        );
        verify(roomRepository, never()).findByIdAndArchivedAtIsNull(any());
        verify(timeProvider, never()).validateOwnerRange(any(), any(), any());
        verify(reservationRepository, never()).findRoomReservationsInRange(
                any(), any(), any(), any()
        );
    }

    @Test
    void ownerScheduleResolvesGhostRequesterIdentityOnceForTheWholePage() {
        LocalDate scheduleDate = LocalDate.of(2026, 7, 22);
        StudioDateRange range = new StudioDateRange(
                scheduleDate,
                scheduleDate,
                window.startsAt(),
                window.endsAt()
        );
        requester.setUsername("legacy-ghost-name");
        requester.setProfilePicture("https://legacy.example/ghost.jpg");
        User standardRequester = User.builder()
                .id(UUID.randomUUID())
                .username("standard-user")
                .profilePicture("https://cdn.example/standard.jpg")
                .build();
        StudioRoomReservation ghostReservation = pendingReservation(UUID.randomUUID(), requester);
        StudioRoomReservation standardReservation = pendingReservation(UUID.randomUUID(), standardRequester);

        when(studioProfileRepository.findByUserId(ownerId)).thenReturn(Optional.of(profile));
        when(roomRepository.findActiveByIdAndStudioProfileId(roomId, profile.getId()))
                .thenReturn(Optional.of(room));
        when(timeProvider.validateOwnerRange(profile, scheduleDate, scheduleDate)).thenReturn(range);
        when(reservationRepository.findRoomReservationsInRange(
                org.mockito.ArgumentMatchers.eq(roomId),
                org.mockito.ArgumentMatchers.eq(window.startsAt()),
                org.mockito.ArgumentMatchers.eq(window.endsAt()),
                any()
        )).thenReturn(new PageImpl<>(List.of(ghostReservation, standardReservation)));
        when(ghostListenerIdentityBatchResolver.resolve(any())).thenReturn(Map.of(
                requesterId,
                new GhostListenerIdentity(
                        requesterId,
                        "canonical-ghost",
                        "https://cdn.example/listener-avatar.jpg",
                        ListenerVisibilityMode.GHOST
                )
        ));
        when(occupancyRepository.findActiveByRoomInRange(
                roomId, window.startsAt(), window.endsAt()
        )).thenReturn(List.of());
        when(dailyMetricsService.load(profile, List.of(roomId))).thenReturn(Map.of());
        when(timeProvider.bookingClock(profile)).thenReturn(new StudioBookingClock(
                scheduleDate,
                LocalTime.of(12, 0),
                LocalDateTime.of(2026, 8, 5, 23, 0)
        ));

        var response = service.ownerSchedule(
                ownerId,
                roomId,
                scheduleDate,
                scheduleDate,
                0,
                20
        );

        assertThat(response.reservations().content()).hasSize(2);
        assertThat(response.reservations().content().get(0)).satisfies(ghost -> {
            assertThat(ghost.requesterUsername()).isEqualTo("canonical-ghost");
            assertThat(ghost.requesterAvatarUrl()).isEqualTo("https://cdn.example/listener-avatar.jpg");
            assertThat(ghost.requesterVisibilityMode()).isEqualTo(ListenerVisibilityMode.GHOST);
        });
        assertThat(response.reservations().content().get(1)).satisfies(standard -> {
            assertThat(standard.requesterUsername()).isEqualTo("standard-user");
            assertThat(standard.requesterAvatarUrl()).isEqualTo("https://cdn.example/standard.jpg");
            assertThat(standard.requesterVisibilityMode()).isNull();
        });
        verify(ghostListenerIdentityBatchResolver).resolve(
                org.mockito.ArgumentMatchers.argThat(ids ->
                        ids.size() == 2
                                && ids.contains(requesterId)
                                && ids.contains(standardRequester.getId()))
        );
    }

	@Test
    void crossCustomerCancellationReturnsNotFoundWithoutLockingVictimState() {
		UUID foreignReservationId = UUID.randomUUID();
		when(reservationRepository.findByIdAndRequesterId(
				foreignReservationId, requesterId
		)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.cancelCustomer(
				requesterId,
				foreignReservationId,
				new StudioVersionRequest(0L)
		))
				.isInstanceOf(SoundConnectException.class)
				.extracting(exception -> ((SoundConnectException) exception).getErrorType())
				.isEqualTo(ErrorType.STUDIO_RESERVATION_NOT_FOUND);

		verify(reservationRepository).findByIdAndRequesterId(
				foreignReservationId, requesterId
		);
		verify(roomRepository, never()).findByIdForUpdate(any());
		verify(reservationRepository, never())
				.findByIdAndRequesterIdForUpdate(any(), any());
		verify(reservationRepository, never()).findByIdAndRoomIdForUpdate(any(), any());
		verify(occupancyRepository, never()).findActiveByReservationIdForUpdate(any());
		verify(reservationRepository, never()).saveAndFlush(any());
	}

	@Test
	void deepCustomerReservationPageFailsBeforeRepositoryAccess() {
		assertThatThrownBy(() -> service.listCustomer(requesterId, 1001, 20))
				.isInstanceOf(SoundConnectException.class)
				.extracting(exception -> ((SoundConnectException) exception).getErrorType())
				.isEqualTo(ErrorType.VALIDATION_ERROR);

		verify(reservationRepository, never()).findByRequesterId(any(), any());
	}

	@Test
	void customerCannotCancelAReservationThatAlreadyStarted() {
		UUID reservationId = UUID.randomUUID();
		StudioRoomReservation reservation = StudioRoomReservation.builder()
				.id(reservationId)
				.room(room)
				.requester(requester)
				.status(StudioReservationStatus.CONFIRMED)
				.startsAt(Instant.parse("2026-07-21T09:00:00Z"))
				.endsAt(Instant.parse("2026-07-21T11:00:00Z"))
				.build();
		when(reservationRepository.findByIdAndRequesterId(reservationId, requesterId))
				.thenReturn(Optional.of(reservation));
		when(roomRepository.findByIdForUpdate(roomId)).thenReturn(Optional.of(room));
		when(reservationRepository.findByIdAndRequesterIdForUpdate(reservationId, requesterId))
				.thenReturn(Optional.of(reservation));

		assertThatThrownBy(() -> service.cancelCustomer(
				requesterId,
				reservationId,
				new StudioVersionRequest(0L)
		))
				.isInstanceOf(SoundConnectException.class)
				.extracting(exception -> ((SoundConnectException) exception).getErrorType())
				.isEqualTo(ErrorType.STUDIO_RESERVATION_STATUS_INVALID);
		verify(reservationRepository, never()).saveAndFlush(any());
	}

    @Test
    void confirmedCancellationFlushesReleasedOccupancyBeforeReservationState() {
        UUID reservationId = UUID.randomUUID();
        StudioRoomReservation reservation = StudioRoomReservation.builder()
                .id(reservationId)
                .room(room)
                .requester(requester)
                .status(StudioReservationStatus.CONFIRMED)
                .startsAt(window.startsAt())
                .endsAt(window.endsAt())
                .approvalRequiredSnapshot(false)
                .currencySnapshot("TRY")
                .clientRequestId(UUID.randomUUID())
                .contactPhoneSnapshot("+905551112233")
                .build();
        StudioRoomOccupancy occupancy = StudioRoomOccupancy.builder()
                .id(UUID.randomUUID())
                .room(room)
                .reservation(reservation)
                .type(StudioOccupancyType.RESERVATION)
                .startsAt(window.startsAt())
                .endsAt(window.endsAt())
                .active(true)
                .createdBy(requesterId)
                .build();
        when(reservationRepository.findByIdAndRequesterId(reservationId, requesterId))
                .thenReturn(Optional.of(reservation));
        when(roomRepository.findByIdForUpdate(roomId)).thenReturn(Optional.of(room));
        when(reservationRepository.findByIdAndRequesterIdForUpdate(reservationId, requesterId))
                .thenReturn(Optional.of(reservation));
        when(occupancyRepository.findActiveByReservationIdForUpdate(reservationId))
                .thenReturn(Optional.of(occupancy));
        when(occupancyRepository.saveAndFlush(occupancy)).thenReturn(occupancy);
        when(reservationRepository.saveAndFlush(reservation)).thenReturn(reservation);

        var response = service.cancelCustomer(
                requesterId,
                reservationId,
                new StudioVersionRequest(0L)
        );

        assertThat(response.status()).isEqualTo(StudioReservationStatus.CANCELLED_BY_CUSTOMER);
        assertThat(occupancy.isActive()).isFalse();
        assertThat(occupancy.getReleasedAt()).isNotNull();
        assertThat(occupancy.getReleasedBy()).isEqualTo(requesterId);
        InOrder persistenceOrder = inOrder(
                roomRepository,
                occupancyRepository,
                reservationRepository,
                eventPublisher
        );
		persistenceOrder.verify(reservationRepository)
				.findByIdAndRequesterId(reservationId, requesterId);
		persistenceOrder.verify(roomRepository).findByIdForUpdate(roomId);
		persistenceOrder.verify(reservationRepository)
				.findByIdAndRequesterIdForUpdate(reservationId, requesterId);
        persistenceOrder.verify(occupancyRepository).saveAndFlush(occupancy);
        persistenceOrder.verify(reservationRepository).saveAndFlush(reservation);
        ArgumentCaptor<StudioReservationNotificationEvent> eventCaptor =
                ArgumentCaptor.forClass(StudioReservationNotificationEvent.class);
        persistenceOrder.verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().recipientId()).isEqualTo(ownerId);
        assertThat(eventCaptor.getValue().type())
                .isEqualTo(NotificationType.STUDIO_RESERVATION_CANCELLED_BY_CUSTOMER);
        assertThat(eventCaptor.getValue().payload())
                .containsEntry("reservationId", reservationId.toString())
                .containsEntry("action", "CANCELLED_BY_CUSTOMER")
                .containsEntry("status", StudioReservationStatus.CANCELLED_BY_CUSTOMER.name());
    }

    @Test
    void ownerCancellationNotifiesRequesterAfterPersistingTheCancellation() {
        UUID reservationId = UUID.randomUUID();
        StudioRoomReservation reservation = StudioRoomReservation.builder()
                .id(reservationId)
                .room(room)
                .requester(requester)
                .status(StudioReservationStatus.PENDING_APPROVAL)
                .startsAt(window.startsAt())
                .endsAt(window.endsAt())
                .approvalRequiredSnapshot(true)
                .currencySnapshot("TRY")
                .clientRequestId(UUID.randomUUID())
                .contactPhoneSnapshot("05551112233")
                .build();
        when(studioProfileRepository.findByUserIdForUpdate(ownerId)).thenReturn(Optional.of(profile));
        when(roomRepository.findActiveByIdAndStudioProfileIdForUpdate(roomId, profile.getId()))
                .thenReturn(Optional.of(room));
        when(reservationRepository.findByIdAndRoomIdForUpdate(reservationId, roomId))
                .thenReturn(Optional.of(reservation));
        when(reservationRepository.saveAndFlush(reservation)).thenReturn(reservation);

        var response = service.cancelOwner(
                ownerId,
                roomId,
                reservationId,
                new StudioVersionRequest(0L)
        );

        assertThat(response.status()).isEqualTo(StudioReservationStatus.CANCELLED_BY_STUDIO);
        ArgumentCaptor<StudioReservationNotificationEvent> eventCaptor =
                ArgumentCaptor.forClass(StudioReservationNotificationEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().recipientId()).isEqualTo(requesterId);
        assertThat(eventCaptor.getValue().type())
                .isEqualTo(NotificationType.STUDIO_RESERVATION_CANCELLED_BY_STUDIO);
        assertThat(eventCaptor.getValue().payload())
                .containsEntry("reservationId", reservationId.toString())
                .containsEntry("action", "CANCELLED_BY_STUDIO");
    }

    private void arrangeCreate(boolean approvalRequired) {
        room.setReservationApprovalRequired(approvalRequired);
        when(userRepository.findByIdForUpdate(requesterId)).thenReturn(Optional.of(requester));
        when(reservationRepository.findByRequesterIdAndClientRequestId(any(), any()))
                .thenReturn(Optional.empty());
        when(roomRepository.findActiveByIdForUpdate(roomId)).thenReturn(Optional.of(room));
        lenient().when(timeProvider.validateBookingWindow(any(), any(), any(), any(Integer.class)))
                .thenReturn(window);
    }

    private StudioRoomReservation pendingReservation(UUID id, User reservationRequester) {
        return StudioRoomReservation.builder()
                .id(id)
                .room(room)
                .requester(reservationRequester)
                .status(StudioReservationStatus.PENDING_APPROVAL)
                .startsAt(window.startsAt())
                .endsAt(window.endsAt())
                .approvalRequiredSnapshot(true)
                .currencySnapshot("TRY")
                .clientRequestId(UUID.randomUUID())
                .contactPhoneSnapshot("05551112233")
                .build();
    }

    private StudioReservationCreateRequest createRequest() {
        return new StudioReservationCreateRequest(
                roomId,
                LocalDate.of(2026, 7, 22),
                LocalTime.of(13, 0),
                2,
                "0 555 111 22 33",
                UUID.randomUUID()
        );
    }
}

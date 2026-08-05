package com.berkayb.soundconnect.modules.studio.room.service;

import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.profile.StudioProfile.entity.StudioProfile;
import com.berkayb.soundconnect.modules.profile.StudioProfile.repository.StudioProfileRepository;
import com.berkayb.soundconnect.modules.studio.reservation.repository.StudioRoomOccupancyRepository;
import com.berkayb.soundconnect.modules.studio.reservation.repository.StudioRoomReservationRepository;
import com.berkayb.soundconnect.modules.studio.reservation.entity.StudioRoomReservation;
import com.berkayb.soundconnect.modules.studio.reservation.entity.StudioRoomOccupancy;
import com.berkayb.soundconnect.modules.studio.reservation.enums.StudioReservationStatus;
import com.berkayb.soundconnect.modules.studio.reservation.enums.StudioOccupancyType;
import com.berkayb.soundconnect.modules.studio.reservation.event.StudioReservationNotificationEvent;
import com.berkayb.soundconnect.modules.studio.reservation.support.StudioReservationTimeProvider;
import com.berkayb.soundconnect.modules.studio.room.dto.request.StudioRoomArchiveRequest;
import com.berkayb.soundconnect.modules.studio.room.dto.request.StudioRoomCreateRequest;
import com.berkayb.soundconnect.modules.studio.room.dto.request.StudioRoomUpdateRequest;
import com.berkayb.soundconnect.modules.studio.room.dto.response.StudioRoomOwnerResponse;
import com.berkayb.soundconnect.modules.studio.room.entity.StudioRoom;
import com.berkayb.soundconnect.modules.studio.room.enums.StudioRoomAvailabilityStatus;
import com.berkayb.soundconnect.modules.studio.room.mapper.StudioRoomMapper;
import com.berkayb.soundconnect.modules.studio.room.repository.StudioRoomRepository;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.modules.user.entity.User;
import org.springframework.context.ApplicationEventPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.time.LocalDate;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class StudioRoomServiceTest {
    @Mock StudioProfileRepository studioProfileRepository;
    @Mock StudioRoomRepository roomRepository;
    @Mock StudioRoomReservationRepository reservationRepository;
    @Mock StudioRoomOccupancyRepository occupancyRepository;
    @Mock MediaAssetService mediaAssetService;
    @Mock StudioRoomMapper roomMapper;
    @Mock StudioRoomDailyMetricsService dailyMetricsService;
    @Mock StudioReservationTimeProvider timeProvider;
    @Mock ApplicationEventPublisher eventPublisher;
    @InjectMocks StudioRoomService service;

    private StudioRoomDailyMetrics emptyMetrics;

    @BeforeEach
    void setUpMetrics() {
        emptyMetrics = new StudioRoomDailyMetrics(
                LocalDate.of(2026, 7, 21),
                0,
                0,
                14,
                com.berkayb.soundconnect.modules.studio.room.enums.StudioRoomAvailabilityStatus.AVAILABLE
        );
        lenient().when(dailyMetricsService.empty(any())).thenReturn(emptyMetrics);
        lenient().when(dailyMetricsService.load(any(), any())).thenReturn(Map.of());
        lenient().when(timeProvider.now())
                .thenReturn(Instant.parse("2026-07-21T10:00:00Z"));
        lenient().when(timeProvider.zoneOf(any()))
                .thenReturn(ZoneId.of("Europe/Istanbul"));
    }

    @Test
    void rejectsEleventhActiveRoomBeforeWriting() {
        UUID ownerId = UUID.randomUUID();
        StudioProfile profile = StudioProfile.builder().id(UUID.randomUUID()).build();
        when(studioProfileRepository.findByUserIdForUpdate(ownerId)).thenReturn(Optional.of(profile));
        when(roomRepository.findActiveSlotIndexes(profile.getId()))
                .thenReturn(List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9));

        assertThatThrownBy(() -> service.create(ownerId, createRequest(List.of())))
                .isInstanceOf(SoundConnectException.class)
                .extracting(exception -> ((SoundConnectException) exception).getErrorType())
                .isEqualTo(ErrorType.STUDIO_ROOM_LIMIT_REACHED);
        verify(roomRepository, never()).saveAndFlush(any());
    }

    @Test
    void normalizesTextDefaultsCurrencyAndKeepsPhotoOrder() {
        UUID ownerId = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();
        UUID firstPhoto = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
        UUID secondPhoto = UUID.fromString("00000000-0000-0000-0000-000000000001");
        StudioProfile profile = StudioProfile.builder().id(profileId).build();
        StudioRoomOwnerResponse mapped = new StudioRoomOwnerResponse(
                UUID.randomUUID(), profileId, UUID.randomUUID(), 0, "Davul odasi", null, 4,
                null, "TRY", false, List.of(), List.of(), LocalDate.of(2026, 7, 21),
                0, 0, 14, StudioRoomAvailabilityStatus.AVAILABLE, null, 0
        );
        when(studioProfileRepository.findByUserIdForUpdate(ownerId)).thenReturn(Optional.of(profile));
        when(roomRepository.findActiveSlotIndexes(profileId)).thenReturn(List.of());
        when(roomRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(roomMapper.toOwner(any(), any())).thenReturn(mapped);

        StudioRoomOwnerResponse result = service.create(
                ownerId,
                createRequest(List.of(firstPhoto, secondPhoto))
        );

        ArgumentCaptor<StudioRoom> captor = ArgumentCaptor.forClass(StudioRoom.class);
        verify(roomRepository).saveAndFlush(captor.capture());
        StudioRoom saved = captor.getValue();
        assertThat(result).isSameAs(mapped);
        assertThat(saved.getName()).isEqualTo("Davul odasi");
        assertThat(saved.getCurrency()).isEqualTo("TRY");
        assertThat(saved.getCreationPayloadHash()).matches("[0-9a-f]{64}");
        assertThat(saved.getPhotos()).extracting(photo -> photo.getMediaAssetId())
                .containsExactly(firstPhoto, secondPhoto);
        var photoValidationOrder = inOrder(mediaAssetService);
        photoValidationOrder.verify(mediaAssetService).validateAssignableMedia(
                ownerId, secondPhoto,
                com.berkayb.soundconnect.modules.media.enums.MediaOwnerType.STUDIO_PROFILE,
                profileId,
                com.berkayb.soundconnect.modules.media.enums.MediaKind.IMAGE
        );
        photoValidationOrder.verify(mediaAssetService).validateAssignableMedia(
                ownerId, firstPhoto,
                com.berkayb.soundconnect.modules.media.enums.MediaOwnerType.STUDIO_PROFILE,
                profileId,
                com.berkayb.soundconnect.modules.media.enums.MediaKind.IMAGE
        );
    }

    @Test
    void delayedExactCreateRetryReturnsTheCurrentRoomAfterItWasUpdated() {
        UUID ownerId = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        StudioProfile profile = StudioProfile.builder().id(profileId).build();
        String originalPayloadHash = StudioRoomService.creationPayloadHash(
                "Davul odasi", null, 4, null, "TRY", false,
                List.of("Akustik"), List.of()
        );
        StudioRoom existing = StudioRoom.builder()
                .id(UUID.randomUUID())
                .studioProfile(profile)
                .clientRequestId(requestId)
                .creationPayloadHash(originalPayloadHash)
                .slotIndex(2)
                .name("Guncel oda adi")
                .capacity(8)
                .currency("TRY")
                .reservationApprovalRequired(false)
                .build();
        existing.replaceFeatures(List.of("Klima"));
        StudioRoomOwnerResponse mapped = new StudioRoomOwnerResponse(
                existing.getId(), profileId, requestId, 2, "Guncel oda adi", null,
                8, null, "TRY", false, List.of("Klima"), List.of(),
                LocalDate.of(2026, 7, 21), 0, 0, 14,
                StudioRoomAvailabilityStatus.AVAILABLE, null, 0
        );
        when(studioProfileRepository.findByUserIdForUpdate(ownerId)).thenReturn(Optional.of(profile));
        when(roomRepository.findByStudioProfileIdAndClientRequestId(profileId, requestId))
                .thenReturn(Optional.of(existing));
        when(roomMapper.toOwner(existing, emptyMetrics)).thenReturn(mapped);

        StudioRoomCreateRequest request = new StudioRoomCreateRequest(
                "davul odasi", null, 4, null, null, false,
                List.of("akustik"), List.of(), requestId
        );
        assertThat(service.create(ownerId, request)).isSameAs(mapped);
        verify(roomRepository, never()).findActiveSlotIndexes(any());
        verify(roomRepository, never()).saveAndFlush(any());
    }

    @Test
    void createRejectsUnsupportedCurrencyAndUnbookablePrice() {
        UUID ownerId = UUID.randomUUID();
        StudioProfile profile = StudioProfile.builder().id(UUID.randomUUID()).build();
        when(studioProfileRepository.findByUserIdForUpdate(ownerId)).thenReturn(Optional.of(profile));

        StudioRoomCreateRequest invalidCurrency = new StudioRoomCreateRequest(
                "oda", null, 4, 10_000L, "ZZZ", false,
                List.of(), List.of(), UUID.randomUUID()
        );
        StudioRoomCreateRequest invalidPrice = new StudioRoomCreateRequest(
                "oda", null, 4, 100_000_001L, "TRY", false,
                List.of(), List.of(), UUID.randomUUID()
        );

        assertThatThrownBy(() -> service.create(ownerId, invalidCurrency))
                .isInstanceOf(SoundConnectException.class)
                .extracting(exception -> ((SoundConnectException) exception).getErrorType())
                .isEqualTo(ErrorType.BAD_REQUEST);
        assertThatThrownBy(() -> service.create(ownerId, invalidPrice))
                .isInstanceOf(SoundConnectException.class)
                .extracting(exception -> ((SoundConnectException) exception).getErrorType())
                .isEqualTo(ErrorType.BAD_REQUEST);
        verify(roomRepository, never()).saveAndFlush(any());
    }

    @Test
    void rejectsStaleUpdateBeforeMediaMutation() {
        UUID ownerId = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();
        UUID roomId = UUID.randomUUID();
        StudioProfile profile = StudioProfile.builder().id(profileId).build();
        StudioRoom room = StudioRoom.builder()
                .id(roomId)
                .studioProfile(profile)
                .version(4)
                .build();
        when(studioProfileRepository.findByUserIdForUpdate(ownerId)).thenReturn(Optional.of(profile));
        when(roomRepository.findByIdAndStudioProfileIdForUpdate(roomId, profileId))
                .thenReturn(Optional.of(room));

        StudioRoomUpdateRequest request = new StudioRoomUpdateRequest(
                3L, "oda", null, 4, null, null, false, List.of(), List.of(UUID.randomUUID())
        );
        assertThatThrownBy(() -> service.update(ownerId, roomId, request))
                .isInstanceOf(SoundConnectException.class)
                .extracting(exception -> ((SoundConnectException) exception).getErrorType())
                .isEqualTo(ErrorType.STUDIO_STALE_UPDATE);
        verify(mediaAssetService, never()).validateAssignableMedia(any(), any(), any(), any(), any());
    }

    @Test
    void crossTenantUpdateReturnsUniformNotFoundWithoutLockingTheForeignRoom() {
        UUID ownerId = UUID.randomUUID();
        UUID ownerProfileId = UUID.randomUUID();
        UUID foreignRoomId = UUID.randomUUID();
        StudioProfile ownerProfile = StudioProfile.builder().id(ownerProfileId).build();
        when(studioProfileRepository.findByUserIdForUpdate(ownerId))
                .thenReturn(Optional.of(ownerProfile));
        when(roomRepository.findByIdAndStudioProfileIdForUpdate(foreignRoomId, ownerProfileId))
                .thenReturn(Optional.empty());

        StudioRoomUpdateRequest request = new StudioRoomUpdateRequest(
                0L, "oda", null, 4, null, "TRY", false, List.of(), List.of()
        );

        assertThatThrownBy(() -> service.update(ownerId, foreignRoomId, request))
                .isInstanceOf(SoundConnectException.class)
                .extracting(exception -> ((SoundConnectException) exception).getErrorType())
                .isEqualTo(ErrorType.STUDIO_ROOM_NOT_FOUND);

        verify(roomRepository).findByIdAndStudioProfileIdForUpdate(
                foreignRoomId, ownerProfileId
        );
        verify(roomRepository, never()).findByIdForUpdate(any());
        verify(roomRepository, never()).saveAndFlush(any());
        verify(mediaAssetService, never()).validateAssignableMedia(any(), any(), any(), any(), any());
    }

    @Test
    void crossTenantArchiveReturnsUniformNotFoundBeforeFutureActivityLocks() {
        UUID ownerId = UUID.randomUUID();
        UUID ownerProfileId = UUID.randomUUID();
        UUID foreignRoomId = UUID.randomUUID();
        StudioProfile ownerProfile = StudioProfile.builder().id(ownerProfileId).build();
        when(studioProfileRepository.findByUserIdForUpdate(ownerId))
                .thenReturn(Optional.of(ownerProfile));
        when(roomRepository.findByIdAndStudioProfileIdForUpdate(foreignRoomId, ownerProfileId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.archive(
                ownerId, foreignRoomId, new StudioRoomArchiveRequest(0L)
        ))
                .isInstanceOf(SoundConnectException.class)
                .extracting(exception -> ((SoundConnectException) exception).getErrorType())
                .isEqualTo(ErrorType.STUDIO_ROOM_NOT_FOUND);

        verify(roomRepository).findByIdAndStudioProfileIdForUpdate(
                foreignRoomId, ownerProfileId
        );
        verify(roomRepository, never()).findByIdForUpdate(any());
        verify(reservationRepository, never()).findFutureByRoomAndStatusesForUpdate(
                any(), any(), any()
        );
        verify(occupancyRepository, never()).findFutureActiveByRoomForUpdate(any(), any());
        verify(roomRepository, never()).saveAndFlush(any());
    }

    @Test
    void crossTenantOwnerReadReturnsUniformNotFoundWithoutReadingTheForeignRoom() {
        UUID ownerId = UUID.randomUUID();
        UUID ownerProfileId = UUID.randomUUID();
        UUID foreignRoomId = UUID.randomUUID();
        StudioProfile ownerProfile = StudioProfile.builder().id(ownerProfileId).build();
        when(studioProfileRepository.findByUserId(ownerId))
                .thenReturn(Optional.of(ownerProfile));
        when(roomRepository.findActiveByIdAndStudioProfileId(foreignRoomId, ownerProfileId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getOwner(ownerId, foreignRoomId))
                .isInstanceOf(SoundConnectException.class)
                .extracting(exception -> ((SoundConnectException) exception).getErrorType())
                .isEqualTo(ErrorType.STUDIO_ROOM_NOT_FOUND);

        verify(roomRepository).findActiveByIdAndStudioProfileId(
                foreignRoomId, ownerProfileId
        );
        verify(roomRepository, never()).findByIdAndArchivedAtIsNull(any());
        verify(dailyMetricsService, never()).load(any(), any());
    }

    @Test
    void changingApprovalPolicySchedulesItForNextStudioLocalMidnight() {
        UUID ownerId = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();
        UUID roomId = UUID.randomUUID();
        StudioProfile profile = StudioProfile.builder()
                .id(profileId)
                .timeZone("Europe/Istanbul")
                .build();
        StudioRoom room = StudioRoom.builder()
                .id(roomId)
                .studioProfile(profile)
                .name("Prova odası")
                .capacity(4)
                .minimumCapacity(4)
                .currency("TRY")
                .reservationApprovalRequired(false)
                .build();
        StudioRoomOwnerResponse mapped = new StudioRoomOwnerResponse(
                roomId, profileId, UUID.randomUUID(), 0, "Prova odası", null,
                4, null, "TRY", false, List.of(), List.of(),
                LocalDate.of(2026, 7, 21), 0, 0, 14,
                StudioRoomAvailabilityStatus.AVAILABLE, null, 0
        );
        Instant now = Instant.parse("2026-07-21T20:30:00Z");
        when(timeProvider.now()).thenReturn(now);
        when(studioProfileRepository.findByUserIdForUpdate(ownerId))
                .thenReturn(Optional.of(profile));
        when(roomRepository.findByIdAndStudioProfileIdForUpdate(roomId, profileId))
                .thenReturn(Optional.of(room));
        when(roomRepository.saveAndFlush(room)).thenReturn(room);
        when(roomMapper.toOwner(any(), any())).thenReturn(mapped);

        service.update(
                ownerId,
                roomId,
                new StudioRoomUpdateRequest(
                        0L, "Prova odası", null, 4, null, "TRY", true,
                        List.of(), List.of()
                )
        );

        Instant expectedEffectiveAt = Instant.parse("2026-07-21T21:00:00Z");
        assertThat(room.isReservationApprovalRequired()).isFalse();
        assertThat(room.getPendingReservationApprovalRequired()).isTrue();
        assertThat(room.getReservationApprovalPolicyEffectiveAt())
                .isEqualTo(expectedEffectiveAt);
        assertThat(room.effectiveReservationApprovalRequired(now)).isFalse();
        assertThat(room.effectiveReservationApprovalRequired(expectedEffectiveAt))
                .isTrue();
    }

    @Test
    void archiveCancelsOnlyRepositorySelectedFutureActivityAndNotifiesEveryCustomer() {
        UUID ownerId = UUID.randomUUID();
        UUID requesterId = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();
        UUID roomId = UUID.randomUUID();
        Instant now = Instant.parse("2026-07-21T10:00:00Z");
        StudioProfile profile = StudioProfile.builder()
                .id(profileId)
                .name("Ses Stüdyosu")
                .timeZone("Europe/Istanbul")
                .build();
        StudioRoom room = StudioRoom.builder()
                .id(roomId)
                .studioProfile(profile)
                .name("A Odası")
                .version(3L)
                .build();
        StudioRoomReservation futureReservation = StudioRoomReservation.builder()
                .id(UUID.randomUUID())
                .room(room)
                .requester(User.builder().id(requesterId).build())
                .status(StudioReservationStatus.CONFIRMED)
                .startsAt(Instant.parse("2026-07-22T10:00:00Z"))
                .endsAt(Instant.parse("2026-07-22T12:00:00Z"))
                .build();
        StudioRoomOccupancy futureOccupancy = StudioRoomOccupancy.builder()
                .id(UUID.randomUUID())
                .room(room)
                .type(StudioOccupancyType.RESERVATION)
                .startsAt(futureReservation.getStartsAt())
                .endsAt(futureReservation.getEndsAt())
                .active(true)
                .build();

        when(studioProfileRepository.findByUserIdForUpdate(ownerId)).thenReturn(Optional.of(profile));
        when(roomRepository.findByIdAndStudioProfileIdForUpdate(roomId, profileId))
                .thenReturn(Optional.of(room));
        when(reservationRepository.findFutureByRoomAndStatusesForUpdate(
                roomId,
                List.of(StudioReservationStatus.PENDING_APPROVAL, StudioReservationStatus.CONFIRMED),
                now
        )).thenReturn(List.of(futureReservation));
        when(occupancyRepository.findFutureActiveByRoomForUpdate(roomId, now))
                .thenReturn(List.of(futureOccupancy));
        when(roomRepository.saveAndFlush(room)).thenReturn(room);

        service.archive(ownerId, roomId, new StudioRoomArchiveRequest(3L));

        assertThat(room.getArchivedAt()).isEqualTo(now);
        assertThat(futureReservation.getStatus())
                .isEqualTo(StudioReservationStatus.CANCELLED_BY_STUDIO);
        assertThat(futureReservation.getCancelledAt()).isEqualTo(now);
        assertThat(futureReservation.getCancelledBy()).isEqualTo(ownerId);
        assertThat(futureOccupancy.isActive()).isFalse();
        assertThat(futureOccupancy.getReleasedAt()).isEqualTo(now);

        ArgumentCaptor<StudioReservationNotificationEvent> eventCaptor =
                ArgumentCaptor.forClass(StudioReservationNotificationEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().recipientId()).isEqualTo(requesterId);
        assertThat(eventCaptor.getValue().type())
                .isEqualTo(NotificationType.STUDIO_RESERVATION_CANCELLED_BY_STUDIO);
        assertThat(eventCaptor.getValue().payload())
                .containsEntry("reservationId", futureReservation.getId().toString())
                .containsEntry("action", "CANCELLED_BY_STUDIO_ROOM_ARCHIVED")
                .containsEntry("status", StudioReservationStatus.CANCELLED_BY_STUDIO.name());
    }

    @Test
    void deepOwnerRoomPageFailsBeforeAnyRepositoryAccess() {
        assertThatThrownBy(() -> service.listOwner(UUID.randomUUID(), 1001, 20))
                .isInstanceOf(SoundConnectException.class)
                .extracting(exception -> ((SoundConnectException) exception).getErrorType())
                .isEqualTo(ErrorType.VALIDATION_ERROR);

        verify(studioProfileRepository, never()).findByUserId(any());
        verify(roomRepository, never())
                .findByStudioProfileIdAndArchivedAtIsNull(any(), any());
    }

    private StudioRoomCreateRequest createRequest(List<UUID> photos) {
        return new StudioRoomCreateRequest(
                "davul odasi", null, 4, null, null, false, List.of("akustik"), photos,
                UUID.randomUUID()
        );
    }
}

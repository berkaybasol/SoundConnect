package com.berkayb.soundconnect.modules.studio.equipment.service;

import com.berkayb.soundconnect.modules.backline.catalog.entity.BacklineCategory;
import com.berkayb.soundconnect.modules.backline.catalog.repository.BacklineCategoryRepository;
import com.berkayb.soundconnect.modules.media.enums.MediaKind;
import com.berkayb.soundconnect.modules.media.enums.MediaOwnerType;
import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.profile.StudioProfile.entity.StudioProfile;
import com.berkayb.soundconnect.modules.profile.StudioProfile.repository.StudioProfileRepository;
import com.berkayb.soundconnect.modules.studio.equipment.dto.EquipmentAvailabilityMoveRequest;
import com.berkayb.soundconnect.modules.studio.equipment.dto.EquipmentCreateRequest;
import com.berkayb.soundconnect.modules.studio.equipment.dto.EquipmentUpdateRequest;
import com.berkayb.soundconnect.modules.studio.equipment.entity.StudioEquipment;
import com.berkayb.soundconnect.modules.studio.equipment.entity.StudioEquipmentAvailabilityCommand;
import com.berkayb.soundconnect.modules.studio.equipment.entity.StudioEquipmentDay;
import com.berkayb.soundconnect.modules.studio.equipment.model.EquipmentAvailabilityBucket;
import com.berkayb.soundconnect.modules.studio.equipment.model.EquipmentAvailabilityStatus;
import com.berkayb.soundconnect.modules.studio.equipment.repository.StudioEquipmentAvailabilityCommandRepository;
import com.berkayb.soundconnect.modules.studio.equipment.repository.StudioEquipmentDayRepository;
import com.berkayb.soundconnect.modules.studio.equipment.repository.StudioEquipmentRepository;
import com.berkayb.soundconnect.modules.studio.equipment.support.StudioEquipmentTimeProvider;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@Tag("service")
class StudioEquipmentServiceTest {

    @Mock private StudioEquipmentRepository equipmentRepository;
    @Mock private StudioEquipmentDayRepository dayRepository;
    @Mock private StudioEquipmentAvailabilityCommandRepository commandRepository;
    @Mock private StudioProfileRepository studioProfileRepository;
    @Mock private BacklineCategoryRepository categoryRepository;
    @Mock private MediaAssetService mediaAssetService;
    @Mock private StudioEquipmentTimeProvider timeProvider;

    private StudioEquipmentService service;
    private UUID ownerId;
    private StudioProfile studio;
    private BacklineCategory root;
    private BacklineCategory child;

    @BeforeEach
    void setUp() {
        service = new StudioEquipmentService(
                equipmentRepository,
                dayRepository,
                commandRepository,
                studioProfileRepository,
                categoryRepository,
                mediaAssetService,
                timeProvider
        );
        ownerId = UUID.randomUUID();
        User owner = new User();
        owner.setId(ownerId);
        studio = new StudioProfile();
        studio.setId(UUID.randomUUID());
        studio.setUser(owner);
        studio.setTimeZone("Europe/Istanbul");

        root = BacklineCategory.createRoot("pro-audio", "Pro Audio", "pro audio", "pro-audio", 0);
        root.setId(UUID.randomUUID());
        child = BacklineCategory.createChild(root, "microphones", "Mikrofon", "mikrofon", 0);
        child.setId(UUID.randomUUID());
    }

    @Test
    void createValidatesStudioOwnedImagesInStableLockOrder() {
        UUID first = UUID.fromString("10000000-0000-0000-0000-000000000001");
        UUID second = UUID.fromString("20000000-0000-0000-0000-000000000001");
        EquipmentCreateRequest request = new EquipmentCreateRequest(
                UUID.randomUUID(),
                child.getId(),
                "  Shure   SM58  ",
                "Shure",
                "SM58",
                "Dinamik mikrofon",
                6,
                List.of("  Kardioid  "),
                List.of(second, first)
        );

        when(studioProfileRepository.findByUserIdForUpdate(ownerId)).thenReturn(Optional.of(studio));
        when(equipmentRepository.findByStudioProfileIdAndCreationClientRequestId(
                studio.getId(), request.clientRequestId()
        )).thenReturn(Optional.empty());
        when(categoryRepository.findByIdAndActiveTrue(child.getId())).thenReturn(Optional.of(child));
        when(equipmentRepository.saveAndFlush(any(StudioEquipment.class))).thenAnswer(invocation -> {
            StudioEquipment saved = invocation.getArgument(0);
            saved.setId(UUID.randomUUID());
            return saved;
        });
        when(mediaAssetService.getDisplayUrl(first)).thenReturn("https://cdn/first");
        when(mediaAssetService.getDisplayUrl(second)).thenReturn("https://cdn/second");

        var response = service.create(ownerId, request);

        assertThat(response.name()).isEqualTo("Shure SM58");
        assertThat(response.features()).containsExactly("Kardioid");
        assertThat(response.photos()).extracting(photo -> photo.mediaAssetId())
                .containsExactly(second, first);
        InOrder orderedMediaLocks = inOrder(mediaAssetService);
        orderedMediaLocks.verify(mediaAssetService).validateAssignableMedia(
                ownerId, first, MediaOwnerType.STUDIO_PROFILE, studio.getId(), MediaKind.IMAGE
        );
        orderedMediaLocks.verify(mediaAssetService).validateAssignableMedia(
                ownerId, second, MediaOwnerType.STUDIO_PROFILE, studio.getId(), MediaKind.IMAGE
        );
    }

    @Test
    void equipmentCreateKeyReuseWithDifferentPayloadIsRejectedBeforeMediaWork() {
        UUID clientRequestId = UUID.randomUUID();
        StudioEquipment existing = StudioEquipment.create(
                studio,
                child,
                clientRequestId,
                "0".repeat(64),
                "Existing",
                null,
                null,
                null,
                1,
                List.of(),
                List.of()
        );
        existing.setId(UUID.randomUUID());
        EquipmentCreateRequest conflicting = new EquipmentCreateRequest(
                clientRequestId,
                child.getId(),
                "Different",
                null,
                null,
                null,
                1,
                List.of(),
                List.of()
        );
        when(studioProfileRepository.findByUserIdForUpdate(ownerId)).thenReturn(Optional.of(studio));
        when(equipmentRepository.findByStudioProfileIdAndCreationClientRequestId(
                studio.getId(), clientRequestId
        )).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.create(ownerId, conflicting))
                .isInstanceOf(SoundConnectException.class)
                .satisfies(error -> assertThat(((SoundConnectException) error).getErrorType())
                        .isEqualTo(ErrorType.DATA_INTEGRITY_CONFLICT));

        verify(categoryRepository, never()).findByIdAndActiveTrue(any());
        verify(mediaAssetService, never()).validateAssignableMedia(any(), any(), any(), any(), any());
    }

    @Test
    void updateRejectsTotalBelowCurrentOrFutureMaximumAllocation() {
        StudioEquipment equipment = equipment(4);
        LocalDate today = LocalDate.of(2026, 8, 1);
        EquipmentUpdateRequest request = new EquipmentUpdateRequest(
                0L,
                child.getId(),
                "Shure SM58",
                "Shure",
                "SM58",
                null,
                2,
                List.of(),
                List.of()
        );
        when(equipmentRepository.findByIdForUpdate(equipment.getId())).thenReturn(Optional.of(equipment));
        when(timeProvider.today(studio.getTimeZone())).thenReturn(today);
        when(dayRepository.findMaximumAllocatedQuantityFromDate(equipment.getId(), today)).thenReturn(3);

        assertThatThrownBy(() -> service.update(ownerId, equipment.getId(), request))
                .isInstanceOf(SoundConnectException.class)
                .satisfies(error -> assertThat(((SoundConnectException) error).getErrorType())
                        .isEqualTo(ErrorType.STUDIO_EQUIPMENT_ALLOCATION_INVALID));

        verify(equipmentRepository, never()).saveAndFlush(any());
        verify(dayRepository, never()).deleteBeforeDate(any(), any());
        verify(mediaAssetService, never()).validateAssignableMedia(any(), any(), any(), any(), any());
    }

    @Test
    void updateUsesStudioLocalTodayAndPurgesOnlyExpiredProjectionState() {
        StudioEquipment equipment = equipment(4);
        LocalDate today = LocalDate.of(2026, 8, 1);
        EquipmentUpdateRequest request = new EquipmentUpdateRequest(
                0L,
                child.getId(),
                "Shure SM58",
                "Shure",
                "SM58",
                null,
                3,
                List.of(),
                List.of()
        );
        when(equipmentRepository.findByIdForUpdate(equipment.getId())).thenReturn(Optional.of(equipment));
        when(timeProvider.today(studio.getTimeZone())).thenReturn(today);
        when(dayRepository.findMaximumAllocatedQuantityFromDate(equipment.getId(), today)).thenReturn(2);
        when(categoryRepository.findByIdAndActiveTrue(child.getId())).thenReturn(Optional.of(child));
        when(equipmentRepository.saveAndFlush(equipment)).thenReturn(equipment);

        var response = service.update(ownerId, equipment.getId(), request);

        assertThat(response.totalQuantity()).isEqualTo(3);
        verify(dayRepository).findMaximumAllocatedQuantityFromDate(equipment.getId(), today);
        verify(dayRepository).deleteBeforeDate(equipment.getId(), today);
    }

    @Test
    void updateFlushesOrphanDeletesBeforeReusingAttachmentPositions() {
        StudioEquipment equipment = StudioEquipment.create(
                studio,
                child,
                UUID.randomUUID(),
                "0".repeat(64),
                "Shure SM58",
                "Shure",
                "SM58",
                null,
                2,
                List.of("Old feature"),
                List.of()
        );
        equipment.setId(UUID.randomUUID());
        EquipmentUpdateRequest request = new EquipmentUpdateRequest(
                0L,
                child.getId(),
                "Shure SM58",
                "Shure",
                "SM58",
                null,
                2,
                List.of("New feature"),
                List.of()
        );
        LocalDate today = LocalDate.of(2026, 8, 1);
        when(equipmentRepository.findByIdForUpdate(equipment.getId())).thenReturn(Optional.of(equipment));
        when(timeProvider.today(studio.getTimeZone())).thenReturn(today);
        when(categoryRepository.findByIdAndActiveTrue(child.getId())).thenReturn(Optional.of(child));
        when(equipmentRepository.saveAndFlush(equipment)).thenReturn(equipment);

        var response = service.update(ownerId, equipment.getId(), request);

        assertThat(response.features()).containsExactly("New feature");
        InOrder repositoryOrder = inOrder(equipmentRepository);
        repositoryOrder.verify(equipmentRepository).findByIdForUpdate(equipment.getId());
        repositoryOrder.verify(equipmentRepository).flush();
        repositoryOrder.verify(equipmentRepository).saveAndFlush(equipment);
    }

    @Test
    void ownerListForwardsFiltersAndLoadsTodayAvailabilityInOneBatch() {
        UUID photoId = UUID.randomUUID();
        StudioEquipment unavailableMixed = equipment(2, List.of(photoId));
        StudioEquipment available = equipment(4);
        LocalDate today = LocalDate.of(2026, 8, 10);
        StudioEquipmentDay mixedDay = new StudioEquipmentDay(unavailableMixed, today);
        mixedDay.move(EquipmentAvailabilityBucket.AVAILABLE, EquipmentAvailabilityBucket.BUSY, 1, 2);
        mixedDay.move(EquipmentAvailabilityBucket.AVAILABLE, EquipmentAvailabilityBucket.MAINTENANCE, 1, 2);

        when(studioProfileRepository.findByUserId(ownerId)).thenReturn(Optional.of(studio));
        when(timeProvider.today(studio.getTimeZone())).thenReturn(today);
        when(equipmentRepository.findActiveByStudio(
                eq(studio.getId()),
                eq("mikrofon"),
                eq(root.getId()),
                eq(EquipmentAvailabilityBucket.BUSY.name()),
                eq(today),
                any(Pageable.class)
        )).thenReturn(new PageImpl<>(List.of(unavailableMixed, available)));
        when(dayRepository.findForEquipmentIdsOnDate(
                List.of(unavailableMixed.getId(), available.getId()), today
        )).thenReturn(List.of(mixedDay));
        when(mediaAssetService.getDisplayUrlMap(List.of(photoId)))
                .thenReturn(Map.of(photoId, "https://cdn/equipment-photo"));

        var result = service.listOwner(
                ownerId,
                "  mikrofon  ",
                root.getId(),
                EquipmentAvailabilityBucket.BUSY,
                0,
                20
        );

        assertThat(result.getContent()).hasSize(2);
        assertThat(result.getContent().get(0).todayAvailability().busyQuantity()).isEqualTo(1);
        assertThat(result.getContent().get(0).todayAvailability().maintenanceQuantity()).isEqualTo(1);
        assertThat(result.getContent().get(0).todayAvailability().status())
                .isEqualTo(EquipmentAvailabilityStatus.MIXED_UNAVAILABLE);
        assertThat(result.getContent().get(0).photos().getFirst().url())
                .isEqualTo("https://cdn/equipment-photo");
        assertThat(result.getContent().get(1).todayAvailability().availableQuantity()).isEqualTo(4);
        assertThat(result.getContent().get(1).todayAvailability().status())
                .isEqualTo(EquipmentAvailabilityStatus.AVAILABLE);
        verify(dayRepository).findForEquipmentIdsOnDate(
                List.of(unavailableMixed.getId(), available.getId()), today
        );
        verify(mediaAssetService).getDisplayUrlMap(List.of(photoId));
        verify(mediaAssetService, never()).getDisplayUrl(photoId);
    }

    @Test
    void ownerListUsesTypedSentinelsWhenOptionalTextFiltersAreAbsent() {
        LocalDate today = LocalDate.of(2026, 8, 10);
        when(studioProfileRepository.findByUserId(ownerId)).thenReturn(Optional.of(studio));
        when(timeProvider.today(studio.getTimeZone())).thenReturn(today);
        when(equipmentRepository.findActiveByStudio(
                eq(studio.getId()),
                eq(""),
                isNull(),
                eq("ALL"),
                eq(today),
                any(Pageable.class)
        )).thenReturn(Page.empty());

        var result = service.listOwner(ownerId, null, null, null, 0, 20);

        assertThat(result).isEmpty();
        verify(equipmentRepository).findActiveByStudio(
                eq(studio.getId()),
                eq(""),
                isNull(),
                eq("ALL"),
                eq(today),
                any(Pageable.class)
        );
    }

    @Test
    void availabilityMoveAppliesInclusiveRangeAndAppendsAuditCommand() {
        StudioEquipment equipment = equipment(6);
        LocalDate start = LocalDate.of(2026, 8, 10);
        LocalDate end = LocalDate.of(2026, 8, 12);
        UUID clientRequestId = UUID.randomUUID();
        EquipmentAvailabilityMoveRequest request = new EquipmentAvailabilityMoveRequest(
                clientRequestId,
                start,
                end,
                EquipmentAvailabilityBucket.AVAILABLE,
                EquipmentAvailabilityBucket.BUSY,
                2
        );

        when(timeProvider.today("Europe/Istanbul")).thenReturn(LocalDate.of(2026, 8, 1));
        when(equipmentRepository.findByIdForUpdate(equipment.getId())).thenReturn(Optional.of(equipment));
        when(commandRepository.findByEquipmentIdAndClientRequestId(equipment.getId(), clientRequestId))
                .thenReturn(Optional.empty());
        when(dayRepository.findRangeForUpdate(equipment.getId(), start, end)).thenReturn(List.of());
        when(commandRepository.saveAndFlush(any(StudioEquipmentAvailabilityCommand.class))).thenAnswer(invocation -> {
            StudioEquipmentAvailabilityCommand saved = invocation.getArgument(0);
            saved.setId(UUID.randomUUID());
            return saved;
        });

        var response = service.moveAvailability(ownerId, equipment.getId(), request);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<StudioEquipmentDay>> daysCaptor = ArgumentCaptor.forClass(Iterable.class);
        verify(dayRepository).saveAll(daysCaptor.capture());
        assertThat(daysCaptor.getValue())
                .hasSize(3)
                .allSatisfy(day -> {
                    assertThat(day.getBusyQuantity()).isEqualTo(2);
                    assertThat(day.getMaintenanceQuantity()).isZero();
                });
        assertThat(response.clientRequestId()).isEqualTo(clientRequestId);
        assertThat(response.replayed()).isFalse();
        InOrder calendarOrder = inOrder(dayRepository);
        calendarOrder.verify(dayRepository).deleteBeforeDate(
                equipment.getId(), LocalDate.of(2026, 8, 1)
        );
        calendarOrder.verify(dayRepository).findRangeForUpdate(equipment.getId(), start, end);
        calendarOrder.verify(dayRepository).saveAll(any());
    }

    @Test
    void exactIdempotencyReplaysMatchingCommandWithoutTouchingCalendar() {
        StudioEquipment equipment = equipment(6);
        LocalDate date = LocalDate.of(2026, 8, 10);
        UUID clientRequestId = UUID.randomUUID();
        EquipmentAvailabilityMoveRequest request = new EquipmentAvailabilityMoveRequest(
                clientRequestId,
                date,
                date,
                EquipmentAvailabilityBucket.AVAILABLE,
                EquipmentAvailabilityBucket.MAINTENANCE,
                1
        );
        StudioEquipmentAvailabilityCommand existing = StudioEquipmentAvailabilityCommand.create(
                equipment,
                ownerId,
                clientRequestId,
                date,
                date,
                EquipmentAvailabilityBucket.AVAILABLE,
                EquipmentAvailabilityBucket.MAINTENANCE,
                1
        );
        existing.setId(UUID.randomUUID());

        when(equipmentRepository.findByIdForUpdate(equipment.getId())).thenReturn(Optional.of(equipment));
        when(commandRepository.findByEquipmentIdAndClientRequestId(equipment.getId(), clientRequestId))
                .thenReturn(Optional.of(existing));

        var response = service.moveAvailability(ownerId, equipment.getId(), request);

        assertThat(response.commandId()).isEqualTo(existing.getId());
        assertThat(response.replayed()).isTrue();
        verify(dayRepository, never()).findRangeForUpdate(any(), any(), any());
        verify(dayRepository, never()).deleteBeforeDate(any(), any());
        verify(timeProvider, never()).today(any());
        verify(commandRepository, never()).saveAndFlush(any());
    }

    @Test
    void exactReplaySucceedsAfterTheOriginalRangeBecomesPast() {
        StudioEquipment equipment = equipment(6);
        LocalDate date = LocalDate.of(2026, 7, 1);
        UUID clientRequestId = UUID.randomUUID();
        EquipmentAvailabilityMoveRequest request = new EquipmentAvailabilityMoveRequest(
                clientRequestId,
                date,
                date,
                EquipmentAvailabilityBucket.AVAILABLE,
                EquipmentAvailabilityBucket.BUSY,
                1
        );
        StudioEquipmentAvailabilityCommand existing = StudioEquipmentAvailabilityCommand.create(
                equipment,
                ownerId,
                clientRequestId,
                date,
                date,
                EquipmentAvailabilityBucket.AVAILABLE,
                EquipmentAvailabilityBucket.BUSY,
                1
        );
        existing.setId(UUID.randomUUID());
        when(equipmentRepository.findByIdForUpdate(equipment.getId())).thenReturn(Optional.of(equipment));
        when(commandRepository.findByEquipmentIdAndClientRequestId(equipment.getId(), clientRequestId))
                .thenReturn(Optional.of(existing));

        var response = service.moveAvailability(ownerId, equipment.getId(), request);

        assertThat(response.commandId()).isEqualTo(existing.getId());
        assertThat(response.replayed()).isTrue();
        verify(timeProvider, never()).today(any());
        verify(dayRepository, never()).findRangeForUpdate(any(), any(), any());
        verify(dayRepository, never()).deleteBeforeDate(any(), any());
    }

    @Test
    void exactReplaySucceedsAfterEquipmentIsArchived() {
        StudioEquipment equipment = equipment(6);
        equipment.archive(Instant.parse("2026-08-02T10:00:00Z"));
        LocalDate date = LocalDate.of(2026, 8, 1);
        UUID clientRequestId = UUID.randomUUID();
        EquipmentAvailabilityMoveRequest request = new EquipmentAvailabilityMoveRequest(
                clientRequestId,
                date,
                date,
                EquipmentAvailabilityBucket.AVAILABLE,
                EquipmentAvailabilityBucket.MAINTENANCE,
                1
        );
        StudioEquipmentAvailabilityCommand existing = StudioEquipmentAvailabilityCommand.create(
                equipment,
                ownerId,
                clientRequestId,
                date,
                date,
                EquipmentAvailabilityBucket.AVAILABLE,
                EquipmentAvailabilityBucket.MAINTENANCE,
                1
        );
        existing.setId(UUID.randomUUID());
        when(equipmentRepository.findByIdForUpdate(equipment.getId())).thenReturn(Optional.of(equipment));
        when(commandRepository.findByEquipmentIdAndClientRequestId(equipment.getId(), clientRequestId))
                .thenReturn(Optional.of(existing));

        var response = service.moveAvailability(ownerId, equipment.getId(), request);

        assertThat(response.commandId()).isEqualTo(existing.getId());
        assertThat(response.replayed()).isTrue();
        verify(timeProvider, never()).today(any());
        verify(dayRepository, never()).findRangeForUpdate(any(), any(), any());
        verify(dayRepository, never()).deleteBeforeDate(any(), any());
    }

    @Test
    void idempotencyKeyReuseWithDifferentPayloadIsRejected() {
        StudioEquipment equipment = equipment(6);
        LocalDate date = LocalDate.of(2026, 8, 10);
        UUID clientRequestId = UUID.randomUUID();
        StudioEquipmentAvailabilityCommand existing = StudioEquipmentAvailabilityCommand.create(
                equipment,
                ownerId,
                clientRequestId,
                date,
                date,
                EquipmentAvailabilityBucket.AVAILABLE,
                EquipmentAvailabilityBucket.BUSY,
                1
        );
        existing.setId(UUID.randomUUID());
        EquipmentAvailabilityMoveRequest conflicting = new EquipmentAvailabilityMoveRequest(
                clientRequestId,
                date,
                date,
                EquipmentAvailabilityBucket.AVAILABLE,
                EquipmentAvailabilityBucket.BUSY,
                2
        );

        when(equipmentRepository.findByIdForUpdate(equipment.getId())).thenReturn(Optional.of(equipment));
        when(commandRepository.findByEquipmentIdAndClientRequestId(equipment.getId(), clientRequestId))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.moveAvailability(ownerId, equipment.getId(), conflicting))
                .isInstanceOf(SoundConnectException.class)
                .satisfies(error -> assertThat(((SoundConnectException) error).getErrorType())
                        .isEqualTo(ErrorType.DATA_INTEGRITY_CONFLICT));
    }

    private StudioEquipment equipment(int totalQuantity) {
        return equipment(totalQuantity, List.of());
    }

    private StudioEquipment equipment(int totalQuantity, List<UUID> photoIds) {
        StudioEquipment equipment = StudioEquipment.create(
                studio,
                child,
                UUID.randomUUID(),
                "0".repeat(64),
                "Shure SM58",
                "Shure",
                "SM58",
                null,
                totalQuantity,
                List.of(),
                photoIds
        );
        equipment.setId(UUID.randomUUID());
        return equipment;
    }
}

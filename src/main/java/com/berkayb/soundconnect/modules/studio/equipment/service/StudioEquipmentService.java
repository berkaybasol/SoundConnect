package com.berkayb.soundconnect.modules.studio.equipment.service;

import com.berkayb.soundconnect.modules.backline.catalog.entity.BacklineCategory;
import com.berkayb.soundconnect.modules.backline.catalog.repository.BacklineCategoryRepository;
import com.berkayb.soundconnect.modules.media.enums.MediaKind;
import com.berkayb.soundconnect.modules.media.enums.MediaOwnerType;
import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.profile.StudioProfile.entity.StudioProfile;
import com.berkayb.soundconnect.modules.profile.StudioProfile.repository.StudioProfileRepository;
import com.berkayb.soundconnect.modules.studio.equipment.dto.EquipmentAvailabilityCommandResponse;
import com.berkayb.soundconnect.modules.studio.equipment.dto.EquipmentAvailabilityDayResponse;
import com.berkayb.soundconnect.modules.studio.equipment.dto.EquipmentAvailabilityMoveRequest;
import com.berkayb.soundconnect.modules.studio.equipment.dto.EquipmentAvailabilityRangeResponse;
import com.berkayb.soundconnect.modules.studio.equipment.dto.EquipmentCreateRequest;
import com.berkayb.soundconnect.modules.studio.equipment.dto.EquipmentOwnerResponse;
import com.berkayb.soundconnect.modules.studio.equipment.dto.EquipmentPhotoResponse;
import com.berkayb.soundconnect.modules.studio.equipment.dto.EquipmentPublicResponse;
import com.berkayb.soundconnect.modules.studio.equipment.dto.EquipmentUpdateRequest;
import com.berkayb.soundconnect.modules.studio.equipment.dto.PublicEquipmentPhotoResponse;
import com.berkayb.soundconnect.modules.studio.equipment.entity.StudioEquipment;
import com.berkayb.soundconnect.modules.studio.equipment.entity.StudioEquipmentAvailabilityCommand;
import com.berkayb.soundconnect.modules.studio.equipment.entity.StudioEquipmentDay;
import com.berkayb.soundconnect.modules.studio.equipment.entity.StudioEquipmentFeature;
import com.berkayb.soundconnect.modules.studio.equipment.model.EquipmentAvailabilityBucket;
import com.berkayb.soundconnect.modules.studio.equipment.model.EquipmentAvailabilityStatus;
import com.berkayb.soundconnect.modules.studio.equipment.repository.StudioEquipmentAvailabilityCommandRepository;
import com.berkayb.soundconnect.modules.studio.equipment.repository.StudioEquipmentDayRepository;
import com.berkayb.soundconnect.modules.studio.equipment.repository.StudioEquipmentRepository;
import com.berkayb.soundconnect.modules.studio.equipment.support.StudioEquipmentTimeProvider;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.text.Normalizer;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class StudioEquipmentService {
    private static final int MAX_PAGE_SIZE = 50;
    private static final int MAX_RANGE_DAYS = 730;

    private final StudioEquipmentRepository equipmentRepository;
    private final StudioEquipmentDayRepository dayRepository;
    private final StudioEquipmentAvailabilityCommandRepository commandRepository;
    private final StudioProfileRepository studioProfileRepository;
    private final BacklineCategoryRepository categoryRepository;
    private final MediaAssetService mediaAssetService;
    private final StudioEquipmentTimeProvider timeProvider;

    @Transactional
    public EquipmentOwnerResponse create(UUID actingUserId, EquipmentCreateRequest request) {
        validateCreateRequest(request);
        StudioProfile studio = studioProfileRepository.findByUserIdForUpdate(actingUserId)
                .orElseThrow(() -> new SoundConnectException(ErrorType.PROFILE_NOT_FOUND));
        String name = cleanRequired(request.name());
        String brand = cleanOptional(request.brand());
        String model = cleanOptional(request.model());
        String description = cleanOptional(request.description());
        List<String> features = normalizeFeatures(request.features());
        List<UUID> photoIds = normalizePhotoIds(request.photoMediaIds());
        String payloadHash = equipmentCreatePayloadHash(
                request.leafCategoryId(),
                name,
                brand,
                model,
                description,
                request.totalQuantity(),
                features,
                photoIds
        );

        StudioEquipment replay = equipmentRepository
                .findByStudioProfileIdAndCreationClientRequestId(studio.getId(), request.clientRequestId())
                .orElse(null);
        if (replay != null) {
            if (!payloadHash.equals(replay.getCreationPayloadHash())) {
                throw new SoundConnectException(
                        ErrorType.DATA_INTEGRITY_CONFLICT,
                        "clientRequestId was already used for a different equipment create payload"
                );
            }
            return toOwnerResponseWithToday(replay);
        }

        BacklineCategory leafCategory = resolveActiveLeafCategory(request.leafCategoryId());
        validatePhotos(actingUserId, studio.getId(), photoIds);

        StudioEquipment equipment = StudioEquipment.create(
                studio,
                leafCategory,
                request.clientRequestId(),
                payloadHash,
                name,
                brand,
                model,
                description,
                request.totalQuantity(),
                features,
                photoIds
        );
        return toOwnerResponseWithToday(equipmentRepository.saveAndFlush(equipment));
    }

    @Transactional
    public EquipmentOwnerResponse update(UUID actingUserId, UUID equipmentId, EquipmentUpdateRequest request) {
        validateUpdateRequest(request);
        StudioEquipment equipment = findOwnedEquipmentForUpdate(actingUserId, equipmentId);
        ensureActive(equipment);
        if (equipment.getVersion() != request.expectedVersion()) {
            throw new SoundConnectException(ErrorType.STUDIO_STALE_UPDATE);
        }

        LocalDate today = timeProvider.today(equipment.getStudioProfile().getTimeZone());
        int maximumAllocation = dayRepository.findMaximumAllocatedQuantityFromDate(equipmentId, today);
        if (request.totalQuantity() < maximumAllocation) {
            throw new SoundConnectException(
                    ErrorType.STUDIO_EQUIPMENT_ALLOCATION_INVALID,
                    "Total quantity cannot be lower than the existing maximum allocation: " + maximumAllocation
            );
        }

        BacklineCategory leafCategory = resolveActiveLeafCategory(request.leafCategoryId());
        List<String> features = normalizeFeatures(request.features());
        List<UUID> photoIds = normalizePhotoIds(request.photoMediaIds());
        validatePhotos(actingUserId, equipment.getStudioProfile().getId(), photoIds);
        purgeExpiredCalendarState(equipmentId, today);

        // Hibernate may schedule replacement inserts before orphan deletes. Flush
        // the removals first so (equipment_id, position) remains collision-free.
        equipment.clearAttachmentsForReplacement();
        equipmentRepository.flush();
        equipment.update(
                leafCategory,
                cleanRequired(request.name()),
                cleanOptional(request.brand()),
                cleanOptional(request.model()),
                cleanOptional(request.description()),
                request.totalQuantity(),
                features,
                photoIds
        );
        return toOwnerResponseWithToday(equipmentRepository.saveAndFlush(equipment));
    }

    @Transactional
    public void archive(UUID actingUserId, UUID equipmentId, long expectedVersion) {
        if (expectedVersion < 0) {
            throw new SoundConnectException(ErrorType.VALIDATION_ERROR, "expectedVersion cannot be negative");
        }
        StudioEquipment equipment = findOwnedEquipmentForUpdate(actingUserId, equipmentId);
        ensureActive(equipment);
        if (equipment.getVersion() != expectedVersion) {
            throw new SoundConnectException(ErrorType.STUDIO_STALE_UPDATE);
        }
        equipment.archive(timeProvider.now());
        equipmentRepository.saveAndFlush(equipment);
    }

    @Transactional(readOnly = true)
    public Page<EquipmentOwnerResponse> listOwner(
            UUID actingUserId,
            String query,
            UUID categoryId,
            EquipmentAvailabilityBucket availabilityBucket,
            int page,
            int size
    ) {
        StudioProfile studio = findOwnedStudio(actingUserId);
        LocalDate today = timeProvider.today(studio.getTimeZone());
        Page<StudioEquipment> resultPage = equipmentRepository.findActiveByStudio(
                studio.getId(),
                normalizeQuery(query),
                categoryId,
                availabilityBucketName(availabilityBucket),
                today,
                equipmentPage(page, size)
        );
        Map<UUID, StudioEquipmentDay> todayRows = loadDayRows(resultPage.getContent(), today);
        Map<UUID, String> photoUrls = loadPhotoUrls(resultPage.getContent());
        return resultPage.map(equipment -> toOwnerResponse(
                equipment, today, todayRows.get(equipment.getId()), photoUrls
        ));
    }

    @Transactional(readOnly = true)
    public EquipmentOwnerResponse getOwner(UUID actingUserId, UUID equipmentId) {
        StudioEquipment equipment = equipmentRepository.findById(equipmentId)
                .orElseThrow(() -> new SoundConnectException(ErrorType.STUDIO_EQUIPMENT_NOT_FOUND));
        assertOwnership(equipment, actingUserId);
        ensureActive(equipment);
        return toOwnerResponseWithToday(equipment);
    }

    @Transactional(readOnly = true)
    public Page<EquipmentPublicResponse> listPublic(
            UUID studioProfileId,
            String query,
            UUID categoryId,
            EquipmentAvailabilityBucket availabilityBucket,
            int page,
            int size
    ) {
        StudioProfile studio = findStudio(studioProfileId);
        LocalDate today = timeProvider.today(studio.getTimeZone());
        Page<StudioEquipment> resultPage = equipmentRepository.findActiveByStudio(
                studioProfileId,
                normalizeQuery(query),
                categoryId,
                availabilityBucketName(availabilityBucket),
                today,
                equipmentPage(page, size)
        );
        Map<UUID, StudioEquipmentDay> todayRows = loadDayRows(resultPage.getContent(), today);
        Map<UUID, String> photoUrls = loadPhotoUrls(resultPage.getContent());
        return resultPage.map(equipment -> toPublicResponse(
                equipment, today, todayRows.get(equipment.getId()), photoUrls
        ));
    }

    @Transactional(readOnly = true)
    public EquipmentPublicResponse getPublic(UUID studioProfileId, UUID equipmentId) {
        StudioProfile studio = findStudio(studioProfileId);
        StudioEquipment equipment = equipmentRepository
                .findByIdAndStudioProfileIdAndArchivedAtIsNull(equipmentId, studioProfileId)
                .orElseThrow(() -> new SoundConnectException(ErrorType.STUDIO_EQUIPMENT_NOT_FOUND));
        return toPublicResponseWithToday(equipment, studio);
    }

    @Transactional(readOnly = true)
    public EquipmentAvailabilityRangeResponse getOwnerAvailability(
            UUID actingUserId,
            UUID equipmentId,
            LocalDate startDate,
            LocalDate endDate
    ) {
        StudioEquipment equipment = equipmentRepository.findById(equipmentId)
                .orElseThrow(() -> new SoundConnectException(ErrorType.STUDIO_EQUIPMENT_NOT_FOUND));
        assertOwnership(equipment, actingUserId);
        ensureActive(equipment);
        return readAvailability(equipment, startDate, endDate);
    }

    @Transactional(readOnly = true)
    public EquipmentAvailabilityRangeResponse getPublicAvailability(
            UUID studioProfileId,
            UUID equipmentId,
            LocalDate startDate,
            LocalDate endDate
    ) {
        StudioEquipment equipment = equipmentRepository
                .findByIdAndStudioProfileIdAndArchivedAtIsNull(equipmentId, studioProfileId)
                .orElseThrow(() -> new SoundConnectException(ErrorType.STUDIO_EQUIPMENT_NOT_FOUND));
        return readAvailability(equipment, startDate, endDate);
    }

    @Transactional
    public EquipmentAvailabilityCommandResponse moveAvailability(
            UUID actingUserId,
            UUID equipmentId,
            EquipmentAvailabilityMoveRequest request
    ) {
        validateMoveRequest(request);
        StudioEquipment equipment = findOwnedEquipmentForUpdate(actingUserId, equipmentId);

        StudioEquipmentAvailabilityCommand existing = commandRepository
                .findByEquipmentIdAndClientRequestId(equipmentId, request.clientRequestId())
                .orElse(null);
        if (existing != null) {
            if (!existing.matches(
                    request.startDate(),
                    request.endDate(),
                    request.sourceBucket(),
                    request.targetBucket(),
                    request.quantity()
            )) {
                throw new SoundConnectException(
                        ErrorType.DATA_INTEGRITY_CONFLICT,
                        "clientRequestId was already used for a different availability command"
                );
            }
            return toCommandResponse(existing, true);
        }

        ensureActive(equipment);
        LocalDate today = validateDateRange(
                request.startDate(),
                request.endDate(),
                equipment.getStudioProfile()
        );
        purgeExpiredCalendarState(equipmentId, today);

        List<StudioEquipmentDay> lockedDays = dayRepository.findRangeForUpdate(
                equipmentId,
                request.startDate(),
                request.endDate()
        );
        Map<LocalDate, StudioEquipmentDay> dayByDate = new HashMap<>();
        lockedDays.forEach(day -> dayByDate.put(day.getLocalDate(), day));

        List<StudioEquipmentDay> toSave = new ArrayList<>();
        List<StudioEquipmentDay> toDelete = new ArrayList<>();
        for (LocalDate date = request.startDate(); !date.isAfter(request.endDate()); date = date.plusDays(1)) {
            StudioEquipmentDay day = dayByDate.get(date);
            if (day == null) {
                day = new StudioEquipmentDay(equipment, date);
            }
            if (day.quantityFor(request.sourceBucket(), equipment.getTotalQuantity()) < request.quantity()) {
                throw new SoundConnectException(
                        ErrorType.STUDIO_EQUIPMENT_ALLOCATION_INVALID,
                        "Insufficient " + request.sourceBucket() + " quantity on " + date
                );
            }
            try {
                day.move(
                        request.sourceBucket(),
                        request.targetBucket(),
                        request.quantity(),
                        equipment.getTotalQuantity()
                );
            } catch (IllegalArgumentException | IllegalStateException exception) {
                throw new SoundConnectException(
                        ErrorType.STUDIO_EQUIPMENT_ALLOCATION_INVALID,
                        exception.getMessage()
                );
            }
            if (day.allocatedQuantity() == 0) {
                if (day.getId() != null) {
                    toDelete.add(day);
                }
            } else {
                toSave.add(day);
            }
        }

        if (!toDelete.isEmpty()) {
            dayRepository.deleteAll(toDelete);
        }
        if (!toSave.isEmpty()) {
            dayRepository.saveAll(toSave);
        }

        StudioEquipmentAvailabilityCommand command = StudioEquipmentAvailabilityCommand.create(
                equipment,
                actingUserId,
                request.clientRequestId(),
                request.startDate(),
                request.endDate(),
                request.sourceBucket(),
                request.targetBucket(),
                request.quantity()
        );
        return toCommandResponse(commandRepository.saveAndFlush(command), false);
    }

    private EquipmentAvailabilityRangeResponse readAvailability(
            StudioEquipment equipment,
            LocalDate startDate,
            LocalDate endDate
    ) {
        validateDateRange(startDate, endDate, equipment.getStudioProfile());
        Map<LocalDate, StudioEquipmentDay> persistedDays = new HashMap<>();
        dayRepository.findRange(equipment.getId(), startDate, endDate)
                .forEach(day -> persistedDays.put(day.getLocalDate(), day));

        List<EquipmentAvailabilityDayResponse> days = new ArrayList<>();
        for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
            StudioEquipmentDay day = persistedDays.get(date);
            int busy = day == null ? 0 : day.getBusyQuantity();
            int maintenance = day == null ? 0 : day.getMaintenanceQuantity();
            days.add(toDayResponse(equipment, date, busy, maintenance));
        }
        return new EquipmentAvailabilityRangeResponse(equipment.getId(), startDate, endDate, List.copyOf(days));
    }

    private EquipmentAvailabilityStatus availabilityStatus(
            int total,
            int available,
            int busy,
            int maintenance
    ) {
        if (available == total) {
            return EquipmentAvailabilityStatus.AVAILABLE;
        }
        if (available > 0) {
            return EquipmentAvailabilityStatus.PARTIALLY_AVAILABLE;
        }
        if (busy > 0 && maintenance > 0) {
            return EquipmentAvailabilityStatus.MIXED_UNAVAILABLE;
        }
        return maintenance > 0 ? EquipmentAvailabilityStatus.MAINTENANCE : EquipmentAvailabilityStatus.BUSY;
    }

    private StudioEquipment findOwnedEquipmentForUpdate(UUID actingUserId, UUID equipmentId) {
        StudioEquipment equipment = equipmentRepository.findByIdForUpdate(equipmentId)
                .orElseThrow(() -> new SoundConnectException(ErrorType.STUDIO_EQUIPMENT_NOT_FOUND));
        assertOwnership(equipment, actingUserId);
        return equipment;
    }

    private void assertOwnership(StudioEquipment equipment, UUID actingUserId) {
        if (!equipment.getStudioProfile().getUser().getId().equals(actingUserId)) {
            throw new SoundConnectException(ErrorType.STUDIO_RESOURCE_FORBIDDEN);
        }
    }

    private StudioProfile findOwnedStudio(UUID actingUserId) {
        return studioProfileRepository.findByUserId(actingUserId)
                .orElseThrow(() -> new SoundConnectException(ErrorType.PROFILE_NOT_FOUND));
    }

    private StudioProfile findStudio(UUID studioProfileId) {
        return studioProfileRepository.findById(studioProfileId)
                .orElseThrow(() -> new SoundConnectException(ErrorType.PROFILE_NOT_FOUND));
    }

    private void ensureActive(StudioEquipment equipment) {
        if (equipment.isArchived()) {
            throw new SoundConnectException(ErrorType.STUDIO_RESOURCE_ARCHIVED);
        }
    }

    private BacklineCategory resolveActiveLeafCategory(UUID leafCategoryId) {
        BacklineCategory leaf = categoryRepository.findByIdAndActiveTrue(leafCategoryId)
                .orElseThrow(() -> new SoundConnectException(ErrorType.BACKLINE_CATEGORY_NOT_FOUND));
        if (!leaf.isLeaf()
                || leaf.getParent() == null
                || !leaf.getParent().isRoot()
                || !leaf.getParent().isActive()) {
            throw new SoundConnectException(ErrorType.BACKLINE_CATEGORY_INVALID);
        }
        return leaf;
    }

    private void validatePhotos(UUID actingUserId, UUID studioProfileId, List<UUID> photoIds) {
        photoIds.stream()
                .sorted(Comparator.comparing(UUID::toString))
                .forEach(mediaAssetId -> mediaAssetService.validateAssignableMedia(
                        actingUserId,
                        mediaAssetId,
                        MediaOwnerType.STUDIO_PROFILE,
                        studioProfileId,
                        MediaKind.IMAGE
                ));
    }

    private List<String> normalizeFeatures(List<String> rawFeatures) {
        if (rawFeatures == null || rawFeatures.size() > 12) {
            throw new SoundConnectException(ErrorType.VALIDATION_ERROR, "At most 12 equipment features are allowed");
        }
        List<String> normalized = new ArrayList<>(rawFeatures.size());
        Set<String> uniquenessKeys = new HashSet<>();
        for (String rawFeature : rawFeatures) {
            String feature = cleanRequired(rawFeature);
            if (feature.length() > 60) {
                throw new SoundConnectException(ErrorType.VALIDATION_ERROR, "Equipment features cannot exceed 60 characters");
            }
            String key = Normalizer.normalize(feature, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
            if (!uniquenessKeys.add(key)) {
                throw new SoundConnectException(ErrorType.VALIDATION_ERROR, "Equipment features must be unique");
            }
            normalized.add(feature);
        }
        return List.copyOf(normalized);
    }

    private List<UUID> normalizePhotoIds(List<UUID> rawPhotoIds) {
        if (rawPhotoIds == null || rawPhotoIds.size() > 5) {
            throw new SoundConnectException(ErrorType.STUDIO_MEDIA_LIMIT_EXCEEDED);
        }
        if (rawPhotoIds.stream().anyMatch(java.util.Objects::isNull)) {
            throw new SoundConnectException(ErrorType.VALIDATION_ERROR, "Photo media IDs cannot be null");
        }
        LinkedHashSet<UUID> uniqueIds = new LinkedHashSet<>(rawPhotoIds);
        if (uniqueIds.size() != rawPhotoIds.size()) {
            throw new SoundConnectException(ErrorType.VALIDATION_ERROR, "Photo media IDs must be unique");
        }
        return List.copyOf(uniqueIds);
    }

    private void validateCreateRequest(EquipmentCreateRequest request) {
        if (request == null || request.clientRequestId() == null || request.leafCategoryId() == null) {
            throw new SoundConnectException(ErrorType.VALIDATION_ERROR);
        }
        validateCoreFields(request.name(), request.brand(), request.model(), request.description(), request.totalQuantity());
    }

    private void validateUpdateRequest(EquipmentUpdateRequest request) {
        if (request == null || request.expectedVersion() == null || request.leafCategoryId() == null) {
            throw new SoundConnectException(ErrorType.VALIDATION_ERROR);
        }
        validateCoreFields(request.name(), request.brand(), request.model(), request.description(), request.totalQuantity());
    }

    private void validateCoreFields(
            String name,
            String brand,
            String model,
            String description,
            int totalQuantity
    ) {
        String cleanName = cleanRequired(name);
        if (cleanName.length() > 100
                || length(cleanOptional(brand)) > 60
                || length(cleanOptional(model)) > 60
                || length(cleanOptional(description)) > 300
                || totalQuantity < 1
                || totalQuantity > 999) {
            throw new SoundConnectException(ErrorType.VALIDATION_ERROR, "Equipment fields exceed their allowed limits");
        }
    }

    private void validateMoveRequest(EquipmentAvailabilityMoveRequest request) {
        if (request == null
                || request.clientRequestId() == null
                || request.startDate() == null
                || request.endDate() == null
                || request.sourceBucket() == null
                || request.targetBucket() == null
                || request.quantity() < 1) {
            throw new SoundConnectException(ErrorType.VALIDATION_ERROR);
        }
        if (request.sourceBucket() == request.targetBucket()) {
            throw new SoundConnectException(ErrorType.BAD_REQUEST, "Source and target buckets must be different");
        }
    }

    private void purgeExpiredCalendarState(UUID equipmentId, LocalDate today) {
        dayRepository.deleteBeforeDate(equipmentId, today);
    }

    private LocalDate validateDateRange(LocalDate startDate, LocalDate endDate, StudioProfile studioProfile) {
        if (startDate == null || endDate == null || endDate.isBefore(startDate)) {
            throw new SoundConnectException(ErrorType.BAD_REQUEST, "Invalid availability date range");
        }
        LocalDate today = timeProvider.today(studioProfile.getTimeZone());
        long inclusiveDays = ChronoUnit.DAYS.between(startDate, endDate) + 1;
        if (startDate.isBefore(today)
                || inclusiveDays > MAX_RANGE_DAYS
                || endDate.isAfter(today.plusDays(MAX_RANGE_DAYS))) {
            throw new SoundConnectException(
                    ErrorType.BAD_REQUEST,
                    "Availability dates must be within the next 730 days"
            );
        }
        return today;
    }

    private EquipmentOwnerResponse toOwnerResponseWithToday(StudioEquipment equipment) {
        StudioProfile studio = equipment.getStudioProfile();
        LocalDate today = timeProvider.today(studio.getTimeZone());
        StudioEquipmentDay todayRow = loadDayRows(List.of(equipment), today).get(equipment.getId());
        return toOwnerResponse(equipment, today, todayRow);
    }

    private EquipmentOwnerResponse toOwnerResponse(
            StudioEquipment equipment,
            LocalDate today,
            StudioEquipmentDay todayRow
    ) {
        return toOwnerResponse(equipment, today, todayRow, null);
    }

    private EquipmentOwnerResponse toOwnerResponse(
            StudioEquipment equipment,
            LocalDate today,
            StudioEquipmentDay todayRow,
            Map<UUID, String> photoUrls
    ) {
        BacklineCategory leaf = equipment.getLeafCategory();
        BacklineCategory root = leaf.getParent();
        List<EquipmentPhotoResponse> photos = equipment.getPhotos().stream()
                .map(photo -> new EquipmentPhotoResponse(
                        photo.getMediaAssetId(),
                        resolvePhotoUrl(photo.getMediaAssetId(), photoUrls),
                        photo.getPosition()
                ))
                .toList();
        return new EquipmentOwnerResponse(
                equipment.getId(),
                root.getId(),
                root.getCode(),
                root.getName(),
                leaf.getId(),
                leaf.getCode(),
                leaf.getName(),
                root.getIconKey(),
                equipment.getName(),
                equipment.getBrand(),
                equipment.getModel(),
                equipment.getDescription(),
                equipment.getTotalQuantity(),
                equipment.getFeatures().stream().map(StudioEquipmentFeature::getLabel).toList(),
                photos,
                toDayResponse(equipment, today, todayRow),
                equipment.getVersion()
        );
    }

    private EquipmentPublicResponse toPublicResponseWithToday(
            StudioEquipment equipment,
            StudioProfile studio
    ) {
        LocalDate today = timeProvider.today(studio.getTimeZone());
        StudioEquipmentDay todayRow = loadDayRows(List.of(equipment), today).get(equipment.getId());
        return toPublicResponse(equipment, today, todayRow);
    }

    private EquipmentPublicResponse toPublicResponse(
            StudioEquipment equipment,
            LocalDate today,
            StudioEquipmentDay todayRow
    ) {
        return toPublicResponse(equipment, today, todayRow, null);
    }

    private EquipmentPublicResponse toPublicResponse(
            StudioEquipment equipment,
            LocalDate today,
            StudioEquipmentDay todayRow,
            Map<UUID, String> photoUrls
    ) {
        BacklineCategory leaf = equipment.getLeafCategory();
        BacklineCategory root = leaf.getParent();
        List<PublicEquipmentPhotoResponse> photos = equipment.getPhotos().stream()
                .map(photo -> new PublicEquipmentPhotoResponse(
                        resolvePhotoUrl(photo.getMediaAssetId(), photoUrls),
                        photo.getPosition()
                ))
                .toList();
        return new EquipmentPublicResponse(
                equipment.getId(),
                root.getId(),
                root.getCode(),
                root.getName(),
                leaf.getId(),
                leaf.getCode(),
                leaf.getName(),
                root.getIconKey(),
                equipment.getName(),
                equipment.getBrand(),
                equipment.getModel(),
                equipment.getDescription(),
                equipment.getTotalQuantity(),
                equipment.getFeatures().stream().map(StudioEquipmentFeature::getLabel).toList(),
                photos,
                toDayResponse(equipment, today, todayRow)
        );
    }

    private Map<UUID, StudioEquipmentDay> loadDayRows(
            List<StudioEquipment> equipment,
            LocalDate availabilityDate
    ) {
        if (equipment.isEmpty()) {
            return Map.of();
        }
        List<UUID> equipmentIds = equipment.stream().map(StudioEquipment::getId).toList();
        Map<UUID, StudioEquipmentDay> rowsByEquipmentId = new HashMap<>();
        dayRepository.findForEquipmentIdsOnDate(equipmentIds, availabilityDate)
                .forEach(day -> rowsByEquipmentId.put(day.getEquipment().getId(), day));
        return rowsByEquipmentId;
    }

    private Map<UUID, String> loadPhotoUrls(List<StudioEquipment> equipment) {
        List<UUID> mediaAssetIds = equipment.stream()
                .flatMap(item -> item.getPhotos().stream())
                .map(photo -> photo.getMediaAssetId())
                .distinct()
                .toList();
        return mediaAssetIds.isEmpty() ? Map.of() : mediaAssetService.getDisplayUrlMap(mediaAssetIds);
    }

    private String resolvePhotoUrl(UUID mediaAssetId, Map<UUID, String> photoUrls) {
        if (photoUrls == null) {
            return mediaAssetService.getDisplayUrl(mediaAssetId);
        }
        String url = photoUrls.get(mediaAssetId);
        if (url == null) {
            throw new SoundConnectException(ErrorType.MEDIA_ASSET_NOT_FOUND);
        }
        return url;
    }

    private EquipmentAvailabilityDayResponse toDayResponse(
            StudioEquipment equipment,
            LocalDate date,
            StudioEquipmentDay day
    ) {
        int busy = day == null ? 0 : day.getBusyQuantity();
        int maintenance = day == null ? 0 : day.getMaintenanceQuantity();
        return toDayResponse(equipment, date, busy, maintenance);
    }

    private EquipmentAvailabilityDayResponse toDayResponse(
            StudioEquipment equipment,
            LocalDate date,
            int busy,
            int maintenance
    ) {
        int available = equipment.getTotalQuantity() - busy - maintenance;
        return new EquipmentAvailabilityDayResponse(
                date,
                equipment.getTotalQuantity(),
                available,
                busy,
                maintenance,
                availabilityStatus(equipment.getTotalQuantity(), available, busy, maintenance)
        );
    }

    private EquipmentAvailabilityCommandResponse toCommandResponse(
            StudioEquipmentAvailabilityCommand command,
            boolean replayed
    ) {
        return new EquipmentAvailabilityCommandResponse(
                command.getId(),
                command.getClientRequestId(),
                command.getEquipment().getId(),
                command.getStartDate(),
                command.getEndDate(),
                command.getSourceBucket(),
                command.getTargetBucket(),
                command.getQuantity(),
                command.getCreatedAt() == null
                        ? null
                        : command.getCreatedAt().toInstant(ZoneOffset.UTC),
                replayed
        );
    }

    private PageRequest equipmentPage(int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        return PageRequest.of(
                safePage,
                safeSize,
                Sort.by(Sort.Order.asc("name").ignoreCase(), Sort.Order.asc("id"))
        );
    }

    private String normalizeQuery(String query) {
        if (!StringUtils.hasText(query)) {
            return "";
        }
        String normalized = collapseWhitespace(query);
        if (normalized.length() > 100) {
            throw new SoundConnectException(ErrorType.VALIDATION_ERROR, "Search query cannot exceed 100 characters");
        }
        return normalized;
    }

    private String availabilityBucketName(EquipmentAvailabilityBucket availabilityBucket) {
        return availabilityBucket == null ? "ALL" : availabilityBucket.name();
    }

    private String cleanRequired(String value) {
        if (!StringUtils.hasText(value)) {
            throw new SoundConnectException(ErrorType.VALIDATION_ERROR, "Required text value is blank");
        }
        return collapseWhitespace(value);
    }

    private String cleanOptional(String value) {
        return StringUtils.hasText(value) ? collapseWhitespace(value) : null;
    }

    private String collapseWhitespace(String value) {
        return value.strip().replaceAll("\\s+", " ");
    }

    private int length(String value) {
        return value == null ? 0 : value.length();
    }

    private String equipmentCreatePayloadHash(
            UUID leafCategoryId,
            String name,
            String brand,
            String model,
            String description,
            int totalQuantity,
            List<String> features,
            List<UUID> photoIds
    ) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            appendHashValue(digest, leafCategoryId.toString());
            appendHashValue(digest, name);
            appendHashValue(digest, brand);
            appendHashValue(digest, model);
            appendHashValue(digest, description);
            appendHashValue(digest, Integer.toString(totalQuantity));
            appendHashValue(digest, Integer.toString(features.size()));
            features.forEach(feature -> appendHashValue(digest, feature));
            appendHashValue(digest, Integer.toString(photoIds.size()));
            photoIds.forEach(photoId -> appendHashValue(digest, photoId.toString()));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private void appendHashValue(MessageDigest digest, String value) {
        byte[] bytes = value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

}

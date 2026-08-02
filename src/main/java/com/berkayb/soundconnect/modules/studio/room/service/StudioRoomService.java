package com.berkayb.soundconnect.modules.studio.room.service;

import com.berkayb.soundconnect.modules.media.enums.MediaKind;
import com.berkayb.soundconnect.modules.media.enums.MediaOwnerType;
import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.profile.StudioProfile.entity.StudioProfile;
import com.berkayb.soundconnect.modules.profile.StudioProfile.repository.StudioProfileRepository;
import com.berkayb.soundconnect.modules.studio.reservation.enums.StudioReservationStatus;
import com.berkayb.soundconnect.modules.studio.reservation.repository.StudioRoomOccupancyRepository;
import com.berkayb.soundconnect.modules.studio.reservation.repository.StudioRoomReservationRepository;
import com.berkayb.soundconnect.modules.studio.reservation.support.StudioReservationTimeProvider;
import com.berkayb.soundconnect.modules.studio.room.dto.request.StudioRoomArchiveRequest;
import com.berkayb.soundconnect.modules.studio.room.dto.request.StudioRoomCreateRequest;
import com.berkayb.soundconnect.modules.studio.room.dto.request.StudioRoomUpdateRequest;
import com.berkayb.soundconnect.modules.studio.room.dto.response.StudioPageResponse;
import com.berkayb.soundconnect.modules.studio.room.dto.response.StudioRoomOwnerResponse;
import com.berkayb.soundconnect.modules.studio.room.dto.response.StudioRoomPublicResponse;
import com.berkayb.soundconnect.modules.studio.room.entity.StudioRoom;
import com.berkayb.soundconnect.modules.studio.room.mapper.StudioRoomMapper;
import com.berkayb.soundconnect.modules.studio.room.repository.StudioRoomRepository;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class StudioRoomService {
    private static final int MAX_ACTIVE_ROOMS = 10;
    private static final int MAX_FEATURES = 8;
    private static final int MAX_PHOTOS = 10;
    private static final int MAX_PAGE_SIZE = 50;
    private static final long MAX_HOURLY_PRICE_MINOR = 100_000_000L;
    private static final Locale TURKISH = Locale.forLanguageTag("tr-TR");

    private final StudioProfileRepository studioProfileRepository;
    private final StudioRoomRepository roomRepository;
    private final StudioRoomReservationRepository reservationRepository;
    private final StudioRoomOccupancyRepository occupancyRepository;
    private final MediaAssetService mediaAssetService;
    private final StudioRoomMapper roomMapper;
    private final StudioRoomDailyMetricsService dailyMetricsService;
    private final StudioReservationTimeProvider timeProvider;

    @Transactional
    public StudioRoomOwnerResponse create(UUID ownerUserId, StudioRoomCreateRequest request) {
        StudioProfile profile = lockOwnedProfile(ownerUserId);
        List<String> features = normalizeFeatures(request.features());
        List<UUID> photos = normalizePhotoIds(request.photoMediaIds());
        String name = normalizeRequired(request.name());
        String shortDescription = normalizeOptional(request.shortDescription());
        String currency = normalizeCurrency(request.currency());
        Long hourlyPriceMinor = validateHourlyPrice(request.hourlyPriceMinor());
        int minimumCapacity = request.minimumCapacity() == null
                ? request.capacity()
                : request.minimumCapacity();
        String payloadHash = creationPayloadHash(
                name,
                shortDescription,
                request.capacity(),
                minimumCapacity,
                hourlyPriceMinor,
                currency,
                request.reservationApprovalRequired(),
                features,
                photos
        );

        StudioRoom existing = roomRepository
                .findByStudioProfileIdAndClientRequestId(profile.getId(), request.clientRequestId())
                .orElse(null);
        if (existing != null) {
            assertIdempotentCreate(existing, payloadHash);
            return roomMapper.toOwner(existing, metricsFor(profile, existing.getId()));
        }

        List<Integer> occupiedSlots = roomRepository.findActiveSlotIndexes(profile.getId());
        if (occupiedSlots.size() >= MAX_ACTIVE_ROOMS) {
            throw new SoundConnectException(ErrorType.STUDIO_ROOM_LIMIT_REACHED);
        }

        int slotIndex = firstFreeSlot(occupiedSlots);
        validatePhotoOwnership(ownerUserId, profile.getId(), photos);
        StudioRoom room = StudioRoom.builder()
                .studioProfile(profile)
                .slotIndex(slotIndex)
                .clientRequestId(request.clientRequestId())
                .creationPayloadHash(payloadHash)
                .name(name)
                .shortDescription(shortDescription)
                .capacity(request.capacity())
                .minimumCapacity(minimumCapacity)
                .hourlyPriceMinor(hourlyPriceMinor)
                .currency(currency)
                .reservationApprovalRequired(request.reservationApprovalRequired())
                .build();
        room.replaceFeatures(features);
        room.replacePhotos(photos);

        try {
            StudioRoom saved = roomRepository.saveAndFlush(room);
            return roomMapper.toOwner(saved, dailyMetricsService.empty(profile));
        } catch (DataIntegrityViolationException exception) {
            throw new SoundConnectException(ErrorType.STUDIO_ROOM_LIMIT_REACHED);
        }
    }

    @Transactional
    public StudioRoomOwnerResponse update(UUID ownerUserId, UUID roomId, StudioRoomUpdateRequest request) {
        StudioProfile profile = lockOwnedProfile(ownerUserId);
        StudioRoom room = lockRoom(roomId);
        assertOwnership(profile, room);
        assertActive(room);
        assertVersion(request.expectedVersion(), room.getVersion());

        List<String> features = normalizeFeatures(request.features());
        List<UUID> photos = normalizePhotoIds(request.photoMediaIds());
        validatePhotoOwnership(ownerUserId, profile.getId(), photos);
        room.setName(normalizeRequired(request.name()));
        room.setShortDescription(normalizeOptional(request.shortDescription()));
        room.setCapacity(request.capacity());
        room.setMinimumCapacity(request.minimumCapacity() == null
                ? request.capacity()
                : request.minimumCapacity());
        room.setHourlyPriceMinor(validateHourlyPrice(request.hourlyPriceMinor()));
        room.setCurrency(normalizeCurrency(request.currency()));
        applyReservationApprovalPolicy(room, request.reservationApprovalRequired());
        // A mappedBy attachment-only change may not dirty the parent on every
        // Hibernate strategy. Touch the audited row so @Version always advances.
        room.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));

        try {
            // Flush orphan deletes before inserting the replacement positions.
            // This avoids transient unique-key collisions when users reorder
            // existing features or photos in databases with immediate checks.
            room.getFeatures().clear();
            room.getPhotos().clear();
            roomRepository.flush();
            room.replaceFeatures(features);
            room.replacePhotos(photos);
            StudioRoom saved = roomRepository.saveAndFlush(room);
            return roomMapper.toOwner(saved, metricsFor(profile, saved.getId()));
        } catch (ObjectOptimisticLockingFailureException exception) {
            throw new SoundConnectException(ErrorType.STUDIO_STALE_UPDATE);
        }
    }

    @Transactional
    public void archive(UUID ownerUserId, UUID roomId, StudioRoomArchiveRequest request) {
        StudioProfile profile = lockOwnedProfile(ownerUserId);
        StudioRoom room = lockRoom(roomId);
        assertOwnership(profile, room);
        assertActive(room);
        assertVersion(request.expectedVersion(), room.getVersion());

        Instant now = Instant.now();
        reservationRepository.findFutureByRoomAndStatusesForUpdate(
                roomId,
                List.of(StudioReservationStatus.PENDING_APPROVAL, StudioReservationStatus.CONFIRMED),
                now
        ).forEach(reservation -> {
            reservation.setStatus(StudioReservationStatus.CANCELLED_BY_STUDIO);
            reservation.setCancelledAt(now);
            reservation.setCancelledBy(ownerUserId);
        });
        occupancyRepository.findFutureActiveByRoomForUpdate(roomId, now).forEach(occupancy -> {
            occupancy.setActive(false);
            occupancy.setReleasedAt(now);
            occupancy.setReleasedBy(ownerUserId);
            occupancy.setReleaseReason("Room archived");
        });
        room.setArchivedAt(now);
        // Archived room history does not need to retain delivery references;
        // detaching them allows the shared media lifecycle to delete old assets.
        room.replacePhotos(List.of());

        try {
            roomRepository.saveAndFlush(room);
        } catch (ObjectOptimisticLockingFailureException exception) {
            throw new SoundConnectException(ErrorType.STUDIO_STALE_UPDATE);
        }
    }

    @Transactional(readOnly = true)
    public StudioPageResponse<StudioRoomOwnerResponse> listOwner(UUID ownerUserId, int page, int size) {
        StudioProfile profile = ownedProfile(ownerUserId);
        Page<StudioRoom> rooms = roomRepository
                .findByStudioProfileIdAndArchivedAtIsNull(profile.getId(), roomPage(page, size));
        Map<UUID, StudioRoomDailyMetrics> metrics = dailyMetricsService.load(
                profile,
                rooms.getContent().stream().map(StudioRoom::getId).toList()
        );
		Map<UUID, String> displayUrls = roomDisplayUrls(rooms.getContent());
        Page<StudioRoomOwnerResponse> result = rooms.map(room -> {
            StudioRoomDailyMetrics roomMetrics = metrics.get(room.getId());
            return roomMapper.toOwner(
                    room,
					roomMetrics == null ? dailyMetricsService.empty(profile) : roomMetrics,
					displayUrls
            );
        });
        return StudioPageResponse.from(result);
    }

    @Transactional(readOnly = true)
    public StudioRoomOwnerResponse getOwner(UUID ownerUserId, UUID roomId) {
        StudioProfile profile = ownedProfile(ownerUserId);
        StudioRoom room = activeRoom(roomId);
        assertOwnership(profile, room);
        return roomMapper.toOwner(room, metricsFor(profile, roomId));
    }

    @Transactional(readOnly = true)
    public StudioPageResponse<StudioRoomPublicResponse> listPublic(UUID profileId, int page, int size) {
        StudioProfile profile = studioProfileRepository.findById(profileId)
                .orElseThrow(() -> new SoundConnectException(ErrorType.PROFILE_NOT_FOUND));
        Page<StudioRoom> rooms = roomRepository
                .findByStudioProfileIdAndArchivedAtIsNull(profileId, roomPage(page, size));
        Map<UUID, StudioRoomDailyMetrics> metrics = dailyMetricsService.load(
                profile,
                rooms.getContent().stream().map(StudioRoom::getId).toList()
        );
		Map<UUID, String> displayUrls = roomDisplayUrls(rooms.getContent());
        Page<StudioRoomPublicResponse> result = rooms.map(room -> {
            StudioRoomDailyMetrics roomMetrics = metrics.get(room.getId());
            return roomMapper.toPublic(
                    room,
					roomMetrics == null ? dailyMetricsService.empty(profile) : roomMetrics,
					displayUrls
            );
        });
        return StudioPageResponse.from(result);
    }

    @Transactional(readOnly = true)
    public StudioRoomPublicResponse getPublic(UUID profileId, UUID roomId) {
        StudioRoom room = activeRoom(roomId);
        if (!room.getStudioProfile().getId().equals(profileId)) {
            throw new SoundConnectException(ErrorType.STUDIO_ROOM_NOT_FOUND);
        }
        return roomMapper.toPublic(room, metricsFor(room.getStudioProfile(), roomId));
    }

    private StudioProfile lockOwnedProfile(UUID ownerUserId) {
        return studioProfileRepository.findByUserIdForUpdate(ownerUserId)
                .orElseThrow(() -> new SoundConnectException(ErrorType.PROFILE_NOT_FOUND));
    }

	private Map<UUID, String> roomDisplayUrls(List<StudioRoom> rooms) {
		return mediaAssetService.getDisplayUrlMap(rooms.stream()
				.flatMap(room -> room.getPhotos().stream())
				.map(photo -> photo.getMediaAssetId())
				.toList());
	}

    private StudioProfile ownedProfile(UUID ownerUserId) {
        return studioProfileRepository.findByUserId(ownerUserId)
                .orElseThrow(() -> new SoundConnectException(ErrorType.PROFILE_NOT_FOUND));
    }

    private StudioRoom lockRoom(UUID roomId) {
        return roomRepository.findByIdForUpdate(roomId)
                .orElseThrow(() -> new SoundConnectException(ErrorType.STUDIO_ROOM_NOT_FOUND));
    }

    private StudioRoom activeRoom(UUID roomId) {
        return roomRepository.findByIdAndArchivedAtIsNull(roomId)
                .orElseThrow(() -> new SoundConnectException(ErrorType.STUDIO_ROOM_NOT_FOUND));
    }

    private void assertOwnership(StudioProfile profile, StudioRoom room) {
        if (!room.getStudioProfile().getId().equals(profile.getId())) {
            throw new SoundConnectException(ErrorType.STUDIO_RESOURCE_FORBIDDEN);
        }
    }

    private void assertActive(StudioRoom room) {
        if (!room.isActive()) {
            throw new SoundConnectException(ErrorType.STUDIO_RESOURCE_ARCHIVED);
        }
    }

    private void assertVersion(long expectedVersion, long actualVersion) {
        if (expectedVersion != actualVersion) {
            throw new SoundConnectException(ErrorType.STUDIO_STALE_UPDATE);
        }
    }

    private int firstFreeSlot(List<Integer> occupiedSlots) {
        Set<Integer> occupied = new HashSet<>(occupiedSlots);
        for (int slot = 0; slot < MAX_ACTIVE_ROOMS; slot++) {
            if (!occupied.contains(slot)) {
                return slot;
            }
        }
        throw new SoundConnectException(ErrorType.STUDIO_ROOM_LIMIT_REACHED);
    }

    private List<String> normalizeFeatures(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        if (values.size() > MAX_FEATURES) {
            throw new SoundConnectException(ErrorType.BAD_REQUEST, "A room can have at most 8 features");
        }
        List<String> normalized = new ArrayList<>(values.size());
        Set<String> uniqueness = new HashSet<>();
        for (String value : values) {
            String label = normalizeRequired(value);
            if (label.length() > 60 || !uniqueness.add(label.toLowerCase(TURKISH))) {
                throw new SoundConnectException(ErrorType.BAD_REQUEST, "Room features must be unique and at most 60 characters");
            }
            normalized.add(label);
        }
        return List.copyOf(normalized);
    }

    private List<UUID> normalizePhotoIds(List<UUID> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        if (values.size() > MAX_PHOTOS || new HashSet<>(values).size() != values.size()) {
            throw new SoundConnectException(ErrorType.STUDIO_MEDIA_LIMIT_EXCEEDED);
        }
        if (values.stream().anyMatch(java.util.Objects::isNull)) {
            throw new SoundConnectException(ErrorType.BAD_REQUEST);
        }
        return List.copyOf(values);
    }

    private void validatePhotoOwnership(UUID ownerUserId, UUID profileId, List<UUID> values) {
        values.forEach(mediaId -> mediaAssetService.validateAssignableMedia(
                ownerUserId,
                mediaId,
                MediaOwnerType.STUDIO_PROFILE,
                profileId,
                MediaKind.IMAGE
        ));
    }

    private void assertIdempotentCreate(StudioRoom existing, String payloadHash) {
        if (!payloadHash.equals(existing.getCreationPayloadHash())) {
            throw new SoundConnectException(
                    ErrorType.DATA_INTEGRITY_CONFLICT,
                    "clientRequestId was already used for a different room payload"
            );
        }
    }

    private void applyReservationApprovalPolicy(StudioRoom room, boolean requestedValue) {
        Instant now = timeProvider.now();
        room.materializeReservationApprovalPolicy(now);
        boolean effectiveValue = room.effectiveReservationApprovalRequired(now);
        Boolean pendingValue = room.futureReservationApprovalRequired(now);

        if (pendingValue != null) {
            if (pendingValue == requestedValue) {
                return;
            }
            // Selecting the currently effective value cancels the scheduled
            // change before it takes effect.
            room.setPendingReservationApprovalRequired(null);
            room.setReservationApprovalPolicyEffectiveAt(null);
            return;
        }
        if (requestedValue == effectiveValue) {
            return;
        }

        ZoneId studioZone = timeProvider.zoneOf(room.getStudioProfile());
        Instant effectiveAt = now.atZone(studioZone)
                .toLocalDate()
                .plusDays(1)
                .atStartOfDay(studioZone)
                .toInstant();
        room.setPendingReservationApprovalRequired(requestedValue);
        room.setReservationApprovalPolicyEffectiveAt(effectiveAt);
    }

    static String creationPayloadHash(
            String name,
            String shortDescription,
            int capacity,
            int minimumCapacity,
            Long hourlyPriceMinor,
            String currency,
            boolean reservationApprovalRequired,
            List<String> features,
            List<UUID> photoIds
    ) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            appendHashValue(digest, name);
            appendHashValue(digest, shortDescription);
            appendHashValue(digest, Integer.toString(capacity));
            // Preserve hashes produced before capacity ranges existed when
            // the room still uses a single capacity value.
            if (minimumCapacity != capacity) {
                appendHashValue(digest, "minimumCapacity");
                appendHashValue(digest, Integer.toString(minimumCapacity));
            }
            appendHashValue(digest, hourlyPriceMinor == null ? null : hourlyPriceMinor.toString());
            appendHashValue(digest, currency);
            appendHashValue(digest, Boolean.toString(reservationApprovalRequired));
            appendHashValue(digest, Integer.toString(features.size()));
            features.forEach(feature -> appendHashValue(digest, feature));
            appendHashValue(digest, Integer.toString(photoIds.size()));
            photoIds.forEach(photoId -> appendHashValue(digest, photoId.toString()));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    static String creationPayloadHash(
            String name,
            String shortDescription,
            int capacity,
            Long hourlyPriceMinor,
            String currency,
            boolean reservationApprovalRequired,
            List<String> features,
            List<UUID> photoIds
    ) {
        return creationPayloadHash(
                name,
                shortDescription,
                capacity,
                capacity,
                hourlyPriceMinor,
                currency,
                reservationApprovalRequired,
                features,
                photoIds
        );
    }

    private static void appendHashValue(MessageDigest digest, String value) {
        byte[] bytes = value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private String normalizeRequired(String value) {
        if (value == null || value.isBlank()) {
            throw new SoundConnectException(ErrorType.BAD_REQUEST);
        }
        return capitalize(value.strip());
    }

    private String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : capitalize(value.strip());
    }

    private String capitalize(String value) {
        int firstLength = Character.charCount(value.codePointAt(0));
        return value.substring(0, firstLength).toUpperCase(TURKISH) + value.substring(firstLength);
    }

    private String normalizeCurrency(String value) {
        String currency = value == null || value.isBlank() ? "TRY" : value.strip().toUpperCase(Locale.ROOT);
        try {
            Currency.getInstance(currency);
        } catch (IllegalArgumentException exception) {
            throw new SoundConnectException(ErrorType.BAD_REQUEST, "Currency must be an ISO 4217 code");
        }
        return currency;
    }

    private Long validateHourlyPrice(Long value) {
        if (value != null && (value < 1 || value > MAX_HOURLY_PRICE_MINOR)) {
            throw new SoundConnectException(
                    ErrorType.BAD_REQUEST,
                    "Hourly price must be between 1 and 100000000 minor currency units"
            );
        }
        return value;
    }

    private PageRequest roomPage(int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return PageRequest.of(safePage, safeSize, Sort.by("slotIndex").ascending().and(Sort.by("id").ascending()));
    }

    private StudioRoomDailyMetrics metricsFor(StudioProfile profile, UUID roomId) {
        StudioRoomDailyMetrics metrics = dailyMetricsService.load(profile, List.of(roomId)).get(roomId);
        return metrics == null ? dailyMetricsService.empty(profile) : metrics;
    }
}

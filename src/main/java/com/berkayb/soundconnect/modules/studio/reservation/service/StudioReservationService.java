package com.berkayb.soundconnect.modules.studio.reservation.service;

import com.berkayb.soundconnect.modules.profile.StudioProfile.entity.StudioProfile;
import com.berkayb.soundconnect.modules.profile.StudioProfile.repository.StudioProfileRepository;
import com.berkayb.soundconnect.modules.studio.reservation.dto.request.StudioManualBlockCreateRequest;
import com.berkayb.soundconnect.modules.studio.reservation.dto.request.StudioManualBlockReleaseRequest;
import com.berkayb.soundconnect.modules.studio.reservation.dto.request.StudioReservationCreateRequest;
import com.berkayb.soundconnect.modules.studio.reservation.dto.request.StudioVersionRequest;
import com.berkayb.soundconnect.modules.studio.reservation.dto.response.StudioOccupancyOwnerResponse;
import com.berkayb.soundconnect.modules.studio.reservation.dto.response.StudioReservationOwnerResponse;
import com.berkayb.soundconnect.modules.studio.reservation.dto.response.StudioReservationResponse;
import com.berkayb.soundconnect.modules.studio.reservation.dto.response.StudioRoomAvailabilityResponse;
import com.berkayb.soundconnect.modules.studio.reservation.dto.response.StudioRoomScheduleResponse;
import com.berkayb.soundconnect.modules.studio.reservation.dto.response.StudioUnavailableIntervalResponse;
import com.berkayb.soundconnect.modules.studio.reservation.entity.StudioRoomOccupancy;
import com.berkayb.soundconnect.modules.studio.reservation.entity.StudioRoomReservation;
import com.berkayb.soundconnect.modules.studio.reservation.enums.StudioOccupancyType;
import com.berkayb.soundconnect.modules.studio.reservation.enums.StudioReservationStatus;
import com.berkayb.soundconnect.modules.studio.reservation.event.StudioReservationNotificationEvent;
import com.berkayb.soundconnect.modules.studio.reservation.repository.StudioRoomOccupancyRepository;
import com.berkayb.soundconnect.modules.studio.reservation.repository.StudioRoomReservationRepository;
import com.berkayb.soundconnect.modules.studio.reservation.support.StudioBookingWindow;
import com.berkayb.soundconnect.modules.studio.reservation.support.StudioBookingClock;
import com.berkayb.soundconnect.modules.studio.reservation.support.StudioDateRange;
import com.berkayb.soundconnect.modules.studio.reservation.support.StudioReservationTimeProvider;
import com.berkayb.soundconnect.modules.studio.room.dto.response.StudioPageResponse;
import com.berkayb.soundconnect.modules.studio.room.entity.StudioRoom;
import com.berkayb.soundconnect.modules.studio.room.mapper.StudioRoomMapper;
import com.berkayb.soundconnect.modules.studio.room.repository.StudioRoomRepository;
import com.berkayb.soundconnect.modules.studio.room.service.StudioRoomDailyMetrics;
import com.berkayb.soundconnect.modules.studio.room.service.StudioRoomDailyMetricsService;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.text.Normalizer;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class StudioReservationService {
    private static final int MAX_PAGE = 1000;
    private static final int MAX_PAGE_SIZE = 100;
    private static final List<StudioReservationStatus> ACTIVE_REQUEST_STATUSES = List.of(
            StudioReservationStatus.PENDING_APPROVAL,
            StudioReservationStatus.CONFIRMED
    );
    private static final Pattern NON_PHONE_DIGITS = Pattern.compile("\\D");
    private static final Pattern PHONE_CHARACTERS = Pattern.compile("^0[0-9() .-]+$");

    private final UserRepository userRepository;
    private final StudioProfileRepository studioProfileRepository;
    private final StudioRoomRepository roomRepository;
    private final StudioRoomReservationRepository reservationRepository;
    private final StudioRoomOccupancyRepository occupancyRepository;
    private final StudioReservationTimeProvider timeProvider;
    private final StudioRoomMapper roomMapper;
    private final StudioRoomDailyMetricsService dailyMetricsService;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public StudioReservationResponse create(UUID requesterId, StudioReservationCreateRequest request) {
        String contactPhone = normalizeContactPhone(request.contactPhone());
        User requester = userRepository.findByIdForUpdate(requesterId)
                .orElseThrow(() -> new SoundConnectException(ErrorType.USER_NOT_FOUND));

        StudioRoomReservation existing = reservationRepository
                .findByRequesterIdAndClientRequestId(requesterId, request.clientRequestId())
                .orElse(null);
        if (existing != null) {
            assertIdempotentPayload(existing, request);
            return toCustomer(existing);
        }

        StudioRoom room = lockActiveRoom(request.roomId());
        if (room.getStudioProfile().getUser().getId().equals(requesterId)) {
            throw new SoundConnectException(ErrorType.STUDIO_RESERVATION_SELF_NOT_ALLOWED);
        }
        StudioBookingWindow window = timeProvider.validateBookingWindow(
                room.getStudioProfile(),
                request.date(),
                request.startTime(),
                request.durationHours()
        );
        Instant now = timeProvider.now();
        reservationRepository.expireStartedPendingRequests(
                requesterId,
                room.getId(),
                StudioReservationStatus.PENDING_APPROVAL,
                StudioReservationStatus.EXPIRED,
                now
        );
        if (reservationRepository.existsActiveRequesterOverlap(
                requesterId,
                room.getId(),
                ACTIVE_REQUEST_STATUSES,
                StudioReservationStatus.PENDING_APPROVAL,
                window.startsAt(),
                window.endsAt(),
                now
        )) {
            throw new SoundConnectException(ErrorType.STUDIO_RESERVATION_REQUESTER_OVERLAP);
        }
        boolean approvalRequired = room.effectiveReservationApprovalRequired(now);
        if (!approvalRequired) {
            assertNoOccupancyConflict(room.getId(), window);
        }

        StudioRoomReservation reservation = StudioRoomReservation.builder()
                .room(room)
                .requester(requester)
                .startsAt(window.startsAt())
                .endsAt(window.endsAt())
                .status(approvalRequired
                        ? StudioReservationStatus.PENDING_APPROVAL
                        : StudioReservationStatus.CONFIRMED)
                .approvalRequiredSnapshot(approvalRequired)
                .hourlyPriceMinorSnapshot(room.getHourlyPriceMinor())
                .totalPriceMinorSnapshot(calculateTotalPrice(
                        room.getHourlyPriceMinor(),
                        request.durationHours()
                ))
                .currencySnapshot(room.getCurrency())
                .contactPhoneSnapshot(contactPhone)
                .clientRequestId(request.clientRequestId())
                .build();

        try {
            reservationRepository.saveAndFlush(reservation);
            if (!approvalRequired) {
                createReservationOccupancy(room, reservation, requesterId);
            }
            publishCreatedNotification(reservation);
            if (approvalRequired) {
                publishPendingConflictNotificationIfNeeded(reservation);
            }
            return toCustomer(reservation);
        } catch (DataIntegrityViolationException exception) {
            throw conflict();
        }
    }

    @Transactional
    public StudioReservationOwnerResponse approve(
            UUID ownerUserId,
            UUID roomId,
            UUID reservationId,
            StudioVersionRequest request
    ) {
        StudioRoom room = lockOwnedRoom(ownerUserId, roomId);
        StudioRoomReservation reservation = lockRoomReservation(roomId, reservationId);
        assertVersion(request.expectedVersion(), reservation.getVersion());
        Instant now = timeProvider.now();
        if (reservation.getStatus() != StudioReservationStatus.PENDING_APPROVAL
                || !reservation.getStartsAt().isAfter(now)) {
            throw invalidStatus();
        }

        StudioBookingWindow window = new StudioBookingWindow(reservation.getStartsAt(), reservation.getEndsAt());
        assertNoOccupancyConflict(roomId, window);
        List<StudioRoomReservation> competingRequests = reservationRepository
                .findOverlappingPendingForUpdate(
                        roomId,
                        reservationId,
                        StudioReservationStatus.PENDING_APPROVAL,
                        now,
                        reservation.getStartsAt(),
                        reservation.getEndsAt()
                );
        reservation.setStatus(StudioReservationStatus.CONFIRMED);
        reservation.setDecidedAt(now);
        reservation.setDecidedBy(ownerUserId);
        try {
            reservationRepository.saveAndFlush(reservation);
            createReservationOccupancy(room, reservation, ownerUserId);
            rejectCompetingRequests(competingRequests, ownerUserId, now);
            publishApprovedNotification(reservation);
            return toOwner(reservation);
        } catch (DataIntegrityViolationException exception) {
            throw conflict();
        }
    }

    @Transactional
    public StudioReservationOwnerResponse reject(
            UUID ownerUserId,
            UUID roomId,
            UUID reservationId,
            StudioVersionRequest request
    ) {
        lockOwnedRoom(ownerUserId, roomId);
        StudioRoomReservation reservation = lockRoomReservation(roomId, reservationId);
        assertVersion(request.expectedVersion(), reservation.getVersion());
		if (reservation.getStatus() != StudioReservationStatus.PENDING_APPROVAL
				|| !reservation.getStartsAt().isAfter(timeProvider.now())) {
            throw invalidStatus();
        }
        reservation.setStatus(StudioReservationStatus.REJECTED_BY_STUDIO);
        reservation.setDecidedAt(timeProvider.now());
        reservation.setDecidedBy(ownerUserId);
        StudioRoomReservation saved = reservationRepository.saveAndFlush(reservation);
        publishRejectedNotification(saved, false);
        return toOwner(saved);
    }

    @Transactional
    public StudioReservationResponse cancelCustomer(
            UUID requesterId,
            UUID reservationId,
            StudioVersionRequest request
    ) {
        StudioRoomReservation reference = reservationRepository
                .findByIdAndRequesterId(reservationId, requesterId)
                .orElseThrow(() -> new SoundConnectException(ErrorType.STUDIO_RESERVATION_NOT_FOUND));
        roomRepository.findByIdForUpdate(reference.getRoom().getId())
                .orElseThrow(() -> new SoundConnectException(ErrorType.STUDIO_ROOM_NOT_FOUND));
        StudioRoomReservation reservation = lockCustomerReservation(requesterId, reservationId);
        assertVersion(request.expectedVersion(), reservation.getVersion());
		if ((reservation.getStatus() != StudioReservationStatus.PENDING_APPROVAL
				&& reservation.getStatus() != StudioReservationStatus.CONFIRMED)
				|| !reservation.getStartsAt().isAfter(timeProvider.now())) {
            throw invalidStatus();
        }
        cancelReservation(reservation, StudioReservationStatus.CANCELLED_BY_CUSTOMER, requesterId);
        StudioRoomReservation saved = reservationRepository.saveAndFlush(reservation);
        publishCustomerCancellationNotification(saved);
        return toCustomer(saved);
    }

    @Transactional
    public StudioReservationOwnerResponse cancelOwner(
            UUID ownerUserId,
            UUID roomId,
            UUID reservationId,
            StudioVersionRequest request
    ) {
        lockOwnedRoom(ownerUserId, roomId);
        StudioRoomReservation reservation = lockRoomReservation(roomId, reservationId);
        assertVersion(request.expectedVersion(), reservation.getVersion());
		if ((reservation.getStatus() != StudioReservationStatus.PENDING_APPROVAL
				&& reservation.getStatus() != StudioReservationStatus.CONFIRMED)
				|| !reservation.getStartsAt().isAfter(timeProvider.now())) {
            throw invalidStatus();
        }
        cancelReservation(reservation, StudioReservationStatus.CANCELLED_BY_STUDIO, ownerUserId);
        StudioRoomReservation saved = reservationRepository.saveAndFlush(reservation);
        publishStudioCancellationNotification(saved);
        return toOwner(saved);
    }

    @Transactional
    public StudioOccupancyOwnerResponse createManualBlock(
            UUID ownerUserId,
            UUID roomId,
            StudioManualBlockCreateRequest request
    ) {
        StudioProfile profile = lockOwnedProfile(ownerUserId);
        StudioRoomOccupancy existing = occupancyRepository
                .findByCreatedByAndClientRequestId(ownerUserId, request.clientRequestId())
                .orElse(null);
        if (existing != null) {
            assertIdempotentManualBlock(existing, profile, roomId, request);
            return toOwner(existing);
        }
        StudioRoom room = lockOwnedRoom(profile, roomId);
        StudioBookingWindow window = timeProvider.validateManualBlockWindow(
                room.getStudioProfile(),
                request.date(),
                request.startTime(),
                request.durationHours()
        );
        assertNoOccupancyConflict(roomId, window);
        StudioRoomOccupancy occupancy = StudioRoomOccupancy.builder()
                .room(room)
                .type(StudioOccupancyType.MANUAL_BLOCK)
                .startsAt(window.startsAt())
                .endsAt(window.endsAt())
                .active(true)
                .createdBy(ownerUserId)
                .clientRequestId(request.clientRequestId())
                .build();
        try {
            return toOwner(occupancyRepository.saveAndFlush(occupancy));
        } catch (DataIntegrityViolationException exception) {
            throw conflict();
        }
    }

    @Transactional
    public StudioOccupancyOwnerResponse releaseManualBlock(
            UUID ownerUserId,
            UUID roomId,
            UUID occupancyId,
            StudioManualBlockReleaseRequest request
    ) {
        lockOwnedRoom(ownerUserId, roomId);
        StudioRoomOccupancy occupancy = occupancyRepository
                .findByIdAndRoomIdForUpdate(occupancyId, roomId)
                .orElseThrow(() -> new SoundConnectException(ErrorType.STUDIO_BLOCK_NOT_FOUND));
        if (occupancy.getType() != StudioOccupancyType.MANUAL_BLOCK) {
            throw new SoundConnectException(ErrorType.STUDIO_BLOCK_NOT_FOUND);
        }
        assertVersion(request.expectedVersion(), occupancy.getVersion());
        if (!occupancy.isActive()) {
            throw invalidStatus();
        }
        occupancy.setActive(false);
        occupancy.setReleasedAt(timeProvider.now());
        occupancy.setReleasedBy(ownerUserId);
        occupancy.setReleaseReason(normalizeReason(request.reason()));
        return toOwner(occupancyRepository.saveAndFlush(occupancy));
    }

    @Transactional(readOnly = true)
    public StudioPageResponse<StudioReservationResponse> listCustomer(UUID requesterId, int page, int size) {
        Page<StudioReservationResponse> result = reservationRepository
                .findByRequesterId(requesterId, reservationPage(page, size))
                .map(this::toCustomer);
        return StudioPageResponse.from(result);
    }

    @Transactional(readOnly = true)
    public StudioPageResponse<StudioReservationResponse> listCustomerRoomDate(
            UUID requesterId,
            UUID roomId,
            LocalDate date,
            int page,
            int size
    ) {
        int boundedPage = safePage(page);
        int boundedSize = safeSize(size);
        StudioRoom room = roomRepository.findByIdAndArchivedAtIsNull(roomId)
                .orElseThrow(() -> new SoundConnectException(ErrorType.STUDIO_ROOM_NOT_FOUND));
        StudioDateRange range = timeProvider.validatePublicRange(
                room.getStudioProfile(),
                date,
                date
        );
        Page<StudioReservationResponse> result = reservationRepository
                .findCustomerRoomReservationsInRange(
                        requesterId,
                        roomId,
                        range.startsAt(),
                        range.endsAt(),
                        PageRequest.of(boundedPage, boundedSize)
                )
                .map(this::toCustomer);
        return StudioPageResponse.from(result);
    }

    @Transactional(readOnly = true)
    public StudioRoomScheduleResponse ownerSchedule(
            UUID ownerUserId,
            UUID roomId,
            LocalDate from,
            LocalDate to,
            int page,
            int size
    ) {
        int boundedPage = safePage(page);
        int boundedSize = safeSize(size);
        StudioRoom room = ownedActiveRoom(ownerUserId, roomId);
        StudioDateRange range = timeProvider.validateOwnerRange(room.getStudioProfile(), from, to);
        Page<StudioReservationOwnerResponse> reservations = reservationRepository
                .findRoomReservationsInRange(
                        roomId,
                        range.startsAt(),
                        range.endsAt(),
                        PageRequest.of(boundedPage, boundedSize)
                )
                .map(this::toOwner);
        List<StudioOccupancyOwnerResponse> occupancies = occupancyRepository
                .findActiveByRoomInRange(roomId, range.startsAt(), range.endsAt())
                .stream()
                .map(this::toOwner)
                .toList();
        StudioProfile profile = room.getStudioProfile();
        StudioRoomDailyMetrics roomMetrics = dailyMetricsService.load(profile, List.of(roomId)).get(roomId);
        if (roomMetrics == null) {
            roomMetrics = dailyMetricsService.empty(profile);
        }
        StudioBookingClock bookingClock = timeProvider.bookingClock(profile);
        return new StudioRoomScheduleResponse(
                roomMapper.toOwner(room, roomMetrics),
                timeProvider.zoneOf(room.getStudioProfile()).getId(),
                bookingClock.todayLocalDate(),
                bookingClock.currentLocalTime(),
                bookingClock.latestBookableLocalDateTime(),
                range.from(),
                range.to(),
                StudioPageResponse.from(reservations),
                occupancies
        );
    }

    @Transactional(readOnly = true)
    public StudioRoomAvailabilityResponse publicAvailability(
            UUID profileId,
            UUID roomId,
            LocalDate from,
            LocalDate to
    ) {
        StudioRoom room = roomRepository.findByIdAndArchivedAtIsNull(roomId)
                .orElseThrow(() -> new SoundConnectException(ErrorType.STUDIO_ROOM_NOT_FOUND));
        if (!room.getStudioProfile().getId().equals(profileId)) {
            throw new SoundConnectException(ErrorType.STUDIO_ROOM_NOT_FOUND);
        }
        StudioDateRange range = timeProvider.validatePublicRange(room.getStudioProfile(), from, to);
		ZoneId zone = timeProvider.zoneOf(room.getStudioProfile());
        StudioBookingClock bookingClock = timeProvider.bookingClock(room.getStudioProfile());
        List<StudioUnavailableIntervalResponse> unavailable = occupancyRepository
                .findActiveByRoomInRange(roomId, range.startsAt(), range.endsAt())
                .stream()
				.map(occupancy -> {
					LocalWindow local = localWindow(occupancy.getStartsAt(), occupancy.getEndsAt(), zone);
					return new StudioUnavailableIntervalResponse(
							occupancy.getStartsAt(),
							occupancy.getEndsAt(),
							local.date(),
							local.startTime(),
							local.endTime()
					);
				})
                .toList();
        return new StudioRoomAvailabilityResponse(
                profileId,
                roomId,
                zone.getId(),
                bookingClock.todayLocalDate(),
                bookingClock.currentLocalTime(),
                bookingClock.latestBookableLocalDateTime(),
                StudioReservationTimeProvider.OPENING_HOUR,
                StudioReservationTimeProvider.CLOSING_HOUR,
                range.from(),
                range.to(),
                unavailable
        );
    }

    private StudioRoom lockOwnedRoom(UUID ownerUserId, UUID roomId) {
        StudioProfile profile = lockOwnedProfile(ownerUserId);
        return lockOwnedRoom(profile, roomId);
    }

    private StudioRoom lockOwnedRoom(StudioProfile profile, UUID roomId) {
        return roomRepository.findActiveByIdAndStudioProfileIdForUpdate(
                        roomId, profile.getId()
                )
                .orElseThrow(() -> new SoundConnectException(ErrorType.STUDIO_ROOM_NOT_FOUND));
    }

    private StudioProfile lockOwnedProfile(UUID ownerUserId) {
        return studioProfileRepository.findByUserIdForUpdate(ownerUserId)
                .orElseThrow(() -> new SoundConnectException(ErrorType.PROFILE_NOT_FOUND));
    }

    private StudioRoom ownedActiveRoom(UUID ownerUserId, UUID roomId) {
        StudioProfile profile = studioProfileRepository.findByUserId(ownerUserId)
                .orElseThrow(() -> new SoundConnectException(ErrorType.PROFILE_NOT_FOUND));
        return roomRepository.findActiveByIdAndStudioProfileId(roomId, profile.getId())
                .orElseThrow(() -> new SoundConnectException(ErrorType.STUDIO_ROOM_NOT_FOUND));
    }

    private StudioRoom lockActiveRoom(UUID roomId) {
        return roomRepository.findActiveByIdForUpdate(roomId)
                .orElseThrow(() -> new SoundConnectException(ErrorType.STUDIO_ROOM_NOT_FOUND));
    }

    private StudioRoomReservation lockRoomReservation(UUID roomId, UUID reservationId) {
        return reservationRepository.findByIdAndRoomIdForUpdate(reservationId, roomId)
                .orElseThrow(() -> new SoundConnectException(ErrorType.STUDIO_RESERVATION_NOT_FOUND));
    }

    private StudioRoomReservation lockCustomerReservation(UUID requesterId, UUID reservationId) {
        return reservationRepository.findByIdAndRequesterIdForUpdate(reservationId, requesterId)
                .orElseThrow(() -> new SoundConnectException(ErrorType.STUDIO_RESERVATION_NOT_FOUND));
    }

    private void assertNoOccupancyConflict(UUID roomId, StudioBookingWindow window) {
        if (occupancyRepository.existsActiveOverlap(roomId, window.startsAt(), window.endsAt())) {
            throw conflict();
        }
    }

    private void createReservationOccupancy(
            StudioRoom room,
            StudioRoomReservation reservation,
            UUID actingUserId
    ) {
        occupancyRepository.saveAndFlush(StudioRoomOccupancy.builder()
                .room(room)
                .reservation(reservation)
                .type(StudioOccupancyType.RESERVATION)
                .startsAt(reservation.getStartsAt())
                .endsAt(reservation.getEndsAt())
                .active(true)
                .createdBy(actingUserId)
                .build());
    }

    private void cancelReservation(
            StudioRoomReservation reservation,
            StudioReservationStatus cancelledStatus,
            UUID actingUserId
    ) {
        Instant now = timeProvider.now();
        if (reservation.getStatus() == StudioReservationStatus.CONFIRMED) {
            occupancyRepository.findActiveByReservationIdForUpdate(reservation.getId())
                    .ifPresent(occupancy -> {
                        occupancy.setActive(false);
                        occupancy.setReleasedAt(now);
                        occupancy.setReleasedBy(actingUserId);
                        occupancy.setReleaseReason("Reservation cancelled");
                        // Flush the released occupancy while its reservation is
                        // still CONFIRMED. This keeps database trigger/constraint
                        // evaluation deterministic within the transaction.
                        occupancyRepository.saveAndFlush(occupancy);
                    });
        }
        reservation.setStatus(cancelledStatus);
        reservation.setCancelledAt(now);
        reservation.setCancelledBy(actingUserId);
    }

    private void assertIdempotentPayload(
            StudioRoomReservation existing,
            StudioReservationCreateRequest request
    ) {
        StudioBookingWindow requestedWindow = timeProvider.convertWithoutFutureValidation(
                existing.getRoom().getStudioProfile(),
                request.date(),
                request.startTime(),
                request.durationHours()
        );
        if (!existing.getRoom().getId().equals(request.roomId())
                || !existing.getStartsAt().equals(requestedWindow.startsAt())
                || !existing.getEndsAt().equals(requestedWindow.endsAt())
                || !normalizeContactPhone(request.contactPhone())
                        .equals(existing.getContactPhoneSnapshot())) {
            throw new SoundConnectException(
                    ErrorType.STUDIO_RESERVATION_CONFLICT,
                    "clientRequestId was already used for a different reservation payload"
            );
        }
    }

    private void assertVersion(long expectedVersion, long actualVersion) {
        if (expectedVersion != actualVersion) {
            throw new SoundConnectException(ErrorType.STUDIO_STALE_UPDATE);
        }
    }

    private StudioReservationResponse toCustomer(StudioRoomReservation reservation) {
        StudioProfile profile = reservation.getRoom().getStudioProfile();
		ZoneId zone = timeProvider.zoneOf(profile);
		LocalWindow local = localWindow(reservation.getStartsAt(), reservation.getEndsAt(), zone);
        return new StudioReservationResponse(
                reservation.getId(),
                reservation.getClientRequestId(),
                reservation.getRoom().getId(),
                profile.getId(),
                reservation.getRoom().getName(),
                reservation.getStartsAt(),
                reservation.getEndsAt(),
				zone.getId(),
				local.date(),
				local.startTime(),
				local.endTime(),
                effectiveStatus(reservation),
                isCompleted(reservation),
                reservation.isApprovalRequiredSnapshot(),
                reservation.getHourlyPriceMinorSnapshot(),
                reservation.getTotalPriceMinorSnapshot(),
                reservation.getCurrencySnapshot(),
                reservation.getVersion()
        );
    }

    private StudioReservationOwnerResponse toOwner(StudioRoomReservation reservation) {
        User requester = reservation.getRequester();
		ZoneId zone = timeProvider.zoneOf(reservation.getRoom().getStudioProfile());
		LocalWindow local = localWindow(reservation.getStartsAt(), reservation.getEndsAt(), zone);
        return new StudioReservationOwnerResponse(
                reservation.getId(),
                reservation.getClientRequestId(),
                reservation.getRoom().getId(),
                requester.getId(),
                requester.getPublicCode(),
                reservation.getContactPhoneSnapshot(),
                requester.getUsername(),
                requester.getProfilePicture(),
                reservation.getStartsAt(),
                reservation.getEndsAt(),
				zone.getId(),
				local.date(),
				local.startTime(),
				local.endTime(),
                effectiveStatus(reservation),
                isCompleted(reservation),
                reservation.isApprovalRequiredSnapshot(),
                reservation.getHourlyPriceMinorSnapshot(),
                reservation.getTotalPriceMinorSnapshot(),
                reservation.getCurrencySnapshot(),
                reservation.getVersion()
        );
    }

    private String normalizeContactPhone(String rawPhone) {
        if (rawPhone == null) {
            throw new SoundConnectException(
                    ErrorType.VALIDATION_ERROR,
                    "Telefon numarası zorunludur"
            );
        }
        String normalized = Normalizer.normalize(rawPhone, Normalizer.Form.NFKC).trim();
        if (normalized.isEmpty() || !PHONE_CHARACTERS.matcher(normalized).matches()) {
            throw new SoundConnectException(
                    ErrorType.VALIDATION_ERROR,
                    "Telefon numarası geçersizdir"
            );
        }
        String digits = NON_PHONE_DIGITS.matcher(normalized).replaceAll("");
        if (digits.length() != 11 || !digits.startsWith("0")) {
            throw new SoundConnectException(
                    ErrorType.VALIDATION_ERROR,
                    "Telefon numarası 0 ile başlayan 11 rakamdan oluşmalıdır"
            );
        }
        return digits;
    }

    private void publishCreatedNotification(StudioRoomReservation reservation) {
        StudioProfile profile = reservation.getRoom().getStudioProfile();
        String requesterName = displayName(reservation.getRequester().getUsername(), "Bir kullanıcı");
        LocalWindow local = localWindow(
                reservation.getStartsAt(),
                reservation.getEndsAt(),
                timeProvider.zoneOf(profile)
        );
        eventPublisher.publishEvent(new StudioReservationNotificationEvent(
                profile.getUser().getId(),
                NotificationType.STUDIO_RESERVATION_CREATED,
                reservation.getStatus() == StudioReservationStatus.PENDING_APPROVAL
                        ? "Yeni rezervasyon talebi"
                        : "Yeni stüdyo rezervasyonu",
                requesterName + ", " + reservation.getRoom().getName()
                        + " için " + local.date() + " "
                        + local.startTime() + "–" + local.endTime()
                        + " saatleri arasında rezervasyon oluşturdu.",
                reservationPayload(reservation, "CREATED"),
                timeProvider.now()
        ));
    }

    private void publishPendingConflictNotificationIfNeeded(
            StudioRoomReservation reservation
    ) {
        Instant now = timeProvider.now();
        long requestCount = reservationRepository.countPendingOverlap(
                reservation.getRoom().getId(),
                StudioReservationStatus.PENDING_APPROVAL,
                now,
                reservation.getStartsAt(),
                reservation.getEndsAt()
        );
        if (requestCount < 2) {
            return;
        }
        StudioProfile profile = reservation.getRoom().getStudioProfile();
        LocalWindow local = localWindow(
                reservation.getStartsAt(),
                reservation.getEndsAt(),
                timeProvider.zoneOf(profile)
        );
        eventPublisher.publishEvent(new StudioReservationNotificationEvent(
                profile.getUser().getId(),
                NotificationType.STUDIO_RESERVATION_CONFLICTING_REQUESTS,
                "Çakışan rezervasyon talepleri",
                reservation.getRoom().getName() + " için " + local.date() + " "
                        + local.startTime() + "–" + local.endTime()
                        + " saatlerinde aynı anda " + requestCount
                        + " kullanıcı talep oluşturdu.",
                reservationPayload(reservation, "CONFLICTING_REQUESTS"),
                now
        ));
    }

    private void rejectCompetingRequests(
            List<StudioRoomReservation> competingRequests,
            UUID ownerUserId,
            Instant decidedAt
    ) {
        if (competingRequests.isEmpty()) {
            return;
        }
        competingRequests.forEach(competing -> {
            competing.setStatus(StudioReservationStatus.REJECTED_BY_STUDIO);
            competing.setDecidedAt(decidedAt);
            competing.setDecidedBy(ownerUserId);
        });
        reservationRepository.saveAllAndFlush(competingRequests);
        competingRequests.forEach(competing ->
                publishRejectedNotification(competing, true));
    }

    private void publishRejectedNotification(
            StudioRoomReservation reservation,
            boolean automaticallyRejected
    ) {
        StudioProfile profile = reservation.getRoom().getStudioProfile();
        String studioName = displayName(profile.getName(), "Stüdyo");
        LocalWindow local = localWindow(
                reservation.getStartsAt(),
                reservation.getEndsAt(),
                timeProvider.zoneOf(profile)
        );
        eventPublisher.publishEvent(new StudioReservationNotificationEvent(
                reservation.getRequester().getId(),
                NotificationType.STUDIO_RESERVATION_REJECTED,
                "Rezervasyon talebiniz reddedildi",
                studioName + ", " + reservation.getRoom().getName()
                        + " için " + local.date() + " "
                        + local.startTime() + "–" + local.endTime()
                        + " saatlerindeki talebinizi"
                        + (automaticallyRejected
                        ? " çakışan başka bir talep onaylandığı için reddetti."
                        : " reddetti."),
                reservationPayload(
                        reservation,
                        automaticallyRejected
                                ? "AUTO_REJECTED_CONFLICT"
                                : "REJECTED"
                ),
                timeProvider.now()
        ));
    }

    private void publishApprovedNotification(StudioRoomReservation reservation) {
        StudioProfile profile = reservation.getRoom().getStudioProfile();
        String studioName = displayName(profile.getName(), "Stüdyo");
        LocalWindow local = localWindow(
                reservation.getStartsAt(),
                reservation.getEndsAt(),
                timeProvider.zoneOf(profile)
        );
        eventPublisher.publishEvent(new StudioReservationNotificationEvent(
                reservation.getRequester().getId(),
                NotificationType.STUDIO_RESERVATION_APPROVED,
                "Rezervasyon talebiniz onaylandı",
                studioName + ", " + reservation.getRoom().getName()
                        + " için " + local.date() + " "
                        + local.startTime() + "–" + local.endTime()
                        + " saatlerindeki rezervasyon talebinizi onayladı.",
                reservationPayload(reservation, "APPROVED"),
                timeProvider.now()
        ));
    }

    private void publishStudioCancellationNotification(StudioRoomReservation reservation) {
        StudioProfile profile = reservation.getRoom().getStudioProfile();
        String studioName = displayName(profile.getName(), "Stüdyo");
        LocalWindow local = localWindow(
                reservation.getStartsAt(),
                reservation.getEndsAt(),
                timeProvider.zoneOf(profile)
        );
        eventPublisher.publishEvent(new StudioReservationNotificationEvent(
                reservation.getRequester().getId(),
                NotificationType.STUDIO_RESERVATION_CANCELLED_BY_STUDIO,
                "Rezervasyonunuz iptal edildi",
                studioName + ", " + reservation.getRoom().getName()
                        + " için " + local.date() + " "
                        + local.startTime() + "–" + local.endTime()
                        + " saatlerindeki rezervasyonunuzu iptal etti.",
                reservationPayload(reservation, "CANCELLED_BY_STUDIO"),
                timeProvider.now()
        ));
    }

    private void publishCustomerCancellationNotification(StudioRoomReservation reservation) {
        StudioProfile profile = reservation.getRoom().getStudioProfile();
        String requesterName = displayName(reservation.getRequester().getUsername(), "Bir kullanıcı");
        LocalWindow local = localWindow(
                reservation.getStartsAt(),
                reservation.getEndsAt(),
                timeProvider.zoneOf(profile)
        );
        eventPublisher.publishEvent(new StudioReservationNotificationEvent(
                profile.getUser().getId(),
                NotificationType.STUDIO_RESERVATION_CANCELLED_BY_CUSTOMER,
                "Rezervasyon müşteri tarafından iptal edildi",
                requesterName + ", " + reservation.getRoom().getName()
                        + " için " + local.date() + " "
                        + local.startTime() + "–" + local.endTime()
                        + " saatlerindeki rezervasyonunu iptal etti.",
                reservationPayload(reservation, "CANCELLED_BY_CUSTOMER"),
                timeProvider.now()
        ));
    }

    private Map<String, Object> reservationPayload(
            StudioRoomReservation reservation,
            String action
    ) {
        StudioProfile profile = reservation.getRoom().getStudioProfile();
        LocalWindow local = localWindow(
                reservation.getStartsAt(),
                reservation.getEndsAt(),
                timeProvider.zoneOf(profile)
        );
        return Map.ofEntries(
                Map.entry("module", "STUDIO"),
                Map.entry("action", action),
                Map.entry("reservationId", reservation.getId().toString()),
                Map.entry("roomId", reservation.getRoom().getId().toString()),
                Map.entry("roomName", reservation.getRoom().getName()),
                Map.entry("studioProfileId", profile.getId().toString()),
                Map.entry("studioName", displayName(profile.getName(), "Stüdyo")),
                Map.entry(
                        "zoneId",
                        timeProvider.zoneOf(profile).getId()
                ),
                Map.entry("localDate", local.date().toString()),
                Map.entry("requesterId", reservation.getRequester().getId().toString()),
                Map.entry("status", reservation.getStatus().name()),
                Map.entry("startsAt", reservation.getStartsAt().toString()),
                Map.entry("endsAt", reservation.getEndsAt().toString())
        );
    }

    private String displayName(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private StudioOccupancyOwnerResponse toOwner(StudioRoomOccupancy occupancy) {
		ZoneId zone = timeProvider.zoneOf(occupancy.getRoom().getStudioProfile());
		LocalWindow local = localWindow(occupancy.getStartsAt(), occupancy.getEndsAt(), zone);
        return new StudioOccupancyOwnerResponse(
                occupancy.getId(),
                occupancy.getRoom().getId(),
                occupancy.getReservation() == null ? null : occupancy.getReservation().getId(),
                occupancy.getClientRequestId(),
                occupancy.getType(),
                occupancy.getStartsAt(),
                occupancy.getEndsAt(),
				local.date(),
				local.startTime(),
				local.endTime(),
                occupancy.isActive(),
                occupancy.getVersion()
        );
    }

	private LocalWindow localWindow(Instant startsAt, Instant endsAt, ZoneId zone) {
		ZonedDateTime localStart = startsAt.atZone(zone);
		ZonedDateTime localEnd = endsAt.atZone(zone);
		return new LocalWindow(
				localStart.toLocalDate(),
				localStart.toLocalTime(),
				localEnd.toLocalTime()
		);
	}

	private record LocalWindow(LocalDate date, LocalTime startTime, LocalTime endTime) {
	}

    private StudioReservationStatus effectiveStatus(StudioRoomReservation reservation) {
        if (reservation.getStatus() == StudioReservationStatus.PENDING_APPROVAL
                && !reservation.getStartsAt().isAfter(timeProvider.now())) {
            return StudioReservationStatus.EXPIRED;
        }
        return reservation.getStatus();
    }

    private boolean isCompleted(StudioRoomReservation reservation) {
        return reservation.getStatus() == StudioReservationStatus.CONFIRMED
                && !reservation.getEndsAt().isAfter(timeProvider.now());
    }

    private String normalizeReason(String reason) {
        return reason == null || reason.isBlank() ? "Released by studio owner" : reason.strip();
    }

    private Long calculateTotalPrice(Long hourlyPriceMinor, int durationHours) {
        if (hourlyPriceMinor == null) {
            return null;
        }
        try {
            return Math.multiplyExact(hourlyPriceMinor, (long) durationHours);
        } catch (ArithmeticException exception) {
            throw new SoundConnectException(ErrorType.BAD_REQUEST, "Reservation price is outside the supported range");
        }
    }

    private void assertIdempotentManualBlock(
            StudioRoomOccupancy existing,
            StudioProfile ownedProfile,
            UUID requestedRoomId,
            StudioManualBlockCreateRequest request
    ) {
        StudioRoom existingRoom = existing.getRoom();
        StudioBookingWindow requestedWindow = timeProvider.convertManualBlockWithoutFutureValidation(
                existingRoom.getStudioProfile(),
                request.date(),
                request.startTime(),
                request.durationHours()
        );
        if (existing.getType() != StudioOccupancyType.MANUAL_BLOCK
                || !existingRoom.getStudioProfile().getId().equals(ownedProfile.getId())
                || !existingRoom.getId().equals(requestedRoomId)
                || !existing.getStartsAt().equals(requestedWindow.startsAt())
                || !existing.getEndsAt().equals(requestedWindow.endsAt())) {
            throw new SoundConnectException(
                    ErrorType.STUDIO_RESERVATION_CONFLICT,
                    "clientRequestId was already used for a different manual block payload"
            );
        }
    }

    private PageRequest reservationPage(int page, int size) {
        return PageRequest.of(
                safePage(page),
                safeSize(size),
                Sort.by("startsAt").descending().and(Sort.by("id").ascending())
        );
    }

    private int safePage(int page) {
        if (page > MAX_PAGE) {
            throw new SoundConnectException(
                    ErrorType.VALIDATION_ERROR,
                    "page cannot exceed " + MAX_PAGE
            );
        }
        return Math.max(page, 0);
    }

    private int safeSize(int size) {
        return Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    }

    private SoundConnectException conflict() {
        return new SoundConnectException(ErrorType.STUDIO_RESERVATION_CONFLICT);
    }

    private SoundConnectException invalidStatus() {
        return new SoundConnectException(ErrorType.STUDIO_RESERVATION_STATUS_INVALID);
    }
}

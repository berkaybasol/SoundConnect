package com.berkayb.soundconnect.modules.notification.dto.response;

import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.modules.studio.reservation.enums.StudioReservationStatus;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.*;
import java.util.UUID;

/** Exact authenticated read projection; deliberately excludes contact and payment snapshots. */
public record StudioReservationNotificationTargetResponse(
        UUID notificationId, UUID recipientId, NotificationType type,
        UUID reservationId, UUID roomId, UUID studioProfileId,
        String studioName, String roomName, boolean ownerMode,
        StudioReservationStatus status, boolean roomArchived, boolean completed,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant startsAt,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant endsAt, String zoneId,
        @JsonFormat(shape = JsonFormat.Shape.STRING) LocalDate localDate,
        @JsonFormat(shape = JsonFormat.Shape.STRING) LocalDate localEndDate,
        @JsonFormat(shape = JsonFormat.Shape.STRING) LocalTime localStartTime,
        @JsonFormat(shape = JsonFormat.Shape.STRING) LocalTime localEndTime) { }

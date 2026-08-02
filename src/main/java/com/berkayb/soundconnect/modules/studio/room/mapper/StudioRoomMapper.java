package com.berkayb.soundconnect.modules.studio.room.mapper;

import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.studio.room.dto.response.StudioRoomOwnerResponse;
import com.berkayb.soundconnect.modules.studio.room.dto.response.StudioRoomPhotoResponse;
import com.berkayb.soundconnect.modules.studio.room.dto.response.StudioRoomPublicPhotoResponse;
import com.berkayb.soundconnect.modules.studio.room.dto.response.StudioRoomPublicResponse;
import com.berkayb.soundconnect.modules.studio.room.entity.StudioRoom;
import com.berkayb.soundconnect.modules.studio.room.service.StudioRoomDailyMetrics;
import com.berkayb.soundconnect.modules.studio.reservation.support.StudioReservationTimeProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class StudioRoomMapper {
    private final MediaAssetService mediaAssetService;
    private final StudioReservationTimeProvider timeProvider;

    public StudioRoomOwnerResponse toOwner(StudioRoom room, StudioRoomDailyMetrics metrics) {
		return toOwner(room, metrics, null);
	}

	public StudioRoomOwnerResponse toOwner(
			StudioRoom room,
			StudioRoomDailyMetrics metrics,
			Map<UUID, String> displayUrls
	) {
        var now = timeProvider.now();
        return new StudioRoomOwnerResponse(
                room.getId(),
                room.getStudioProfile().getId(),
                room.getClientRequestId(),
                room.getSlotIndex(),
                room.getName(),
                room.getShortDescription(),
                room.getCapacity(),
                room.effectiveMinimumCapacity(),
                room.getHourlyPriceMinor(),
                room.getCurrency(),
                room.effectiveReservationApprovalRequired(now),
                room.futureReservationApprovalRequired(now),
                room.futureReservationApprovalPolicyEffectiveAt(now),
                room.getFeatures().stream().map(feature -> feature.getLabel()).toList(),
                room.getPhotos().stream()
                        .map(photo -> new StudioRoomPhotoResponse(
                                photo.getMediaAssetId(),
								displayUrl(photo.getMediaAssetId(), displayUrls),
                                photo.getOrderIndex()
                        ))
                        .toList(),
                metrics.localDate(),
                metrics.reservationCount(),
                metrics.occupiedHours(),
                metrics.availableHours(),
                metrics.availabilityStatus(),
                room.getArchivedAt(),
                room.getVersion()
        );
    }

    public StudioRoomPublicResponse toPublic(StudioRoom room, StudioRoomDailyMetrics metrics) {
		return toPublic(room, metrics, null);
	}

	public StudioRoomPublicResponse toPublic(
			StudioRoom room,
			StudioRoomDailyMetrics metrics,
			Map<UUID, String> displayUrls
	) {
        var now = timeProvider.now();
        return new StudioRoomPublicResponse(
                room.getId(),
                room.getStudioProfile().getId(),
                room.getSlotIndex(),
                room.getName(),
                room.getShortDescription(),
                room.getCapacity(),
                room.effectiveMinimumCapacity(),
                room.getHourlyPriceMinor(),
                room.getCurrency(),
                room.effectiveReservationApprovalRequired(now),
                room.getFeatures().stream().map(feature -> feature.getLabel()).toList(),
                room.getPhotos().stream()
                        .map(photo -> new StudioRoomPublicPhotoResponse(
								displayUrl(photo.getMediaAssetId(), displayUrls),
                                photo.getOrderIndex()
                        ))
                        .toList(),
                metrics.localDate(),
                metrics.availableHours(),
                metrics.availabilityStatus()
        );
    }

	private String displayUrl(UUID mediaAssetId, Map<UUID, String> displayUrls) {
		if (displayUrls == null) return mediaAssetService.getDisplayUrl(mediaAssetId);
		return displayUrls.get(mediaAssetId);
	}
}

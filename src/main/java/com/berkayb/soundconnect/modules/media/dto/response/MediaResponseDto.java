package com.berkayb.soundconnect.modules.media.dto.response;

import com.berkayb.soundconnect.modules.media.enums.*;
import lombok.Builder;

import java.util.UUID;

@Builder
public record MediaResponseDto(
		UUID uuid,
		MediaKind kind,
		MediaStatus status,
		MediaVisibility visibility,
		MediaOwnerType ownerType,
		UUID ownerId,
		String sourceUrl,
		String playbackUrl,
		String thumbnailUrl,
		String mimeType,
		long size,
		Integer durationSeconds,
		Integer width,
		Integer height,
		String title,
		String description,
		MediaStreamingProtocol streamingProtocol,
		MediaContentAudience contentAudience
) {
	public MediaResponseDto(UUID uuid, MediaKind kind, MediaStatus status, MediaVisibility visibility,
			MediaOwnerType ownerType, UUID ownerId, String sourceUrl, String playbackUrl, String thumbnailUrl,
			String mimeType, long size, Integer durationSeconds, Integer width, Integer height,
			String title, String description, MediaStreamingProtocol streamingProtocol) {
		this(uuid, kind, status, visibility, ownerType, ownerId, sourceUrl, playbackUrl, thumbnailUrl,
				mimeType, size, durationSeconds, width, height, title, description, streamingProtocol,
				MediaContentAudience.forOwner(ownerType, null));
	}
}

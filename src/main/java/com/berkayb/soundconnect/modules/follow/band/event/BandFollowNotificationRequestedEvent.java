package com.berkayb.soundconnect.modules.follow.band.event;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Commit-bound request for notifying the active members of a followed band.
 * Only stable identifiers cross the transaction boundary.
 */
public record BandFollowNotificationRequestedEvent(
		UUID followerId,
		UUID bandId,
		List<UUID> recipientIds
) {
	public BandFollowNotificationRequestedEvent {
		recipientIds = recipientIds == null
				? List.of()
				: recipientIds.stream()
				              .filter(Objects::nonNull)
				              .filter(recipientId -> !Objects.equals(recipientId, followerId))
				              .distinct()
				              .toList();
	}
}

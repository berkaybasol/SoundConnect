package com.berkayb.soundconnect.modules.message.dm.model;

import java.util.Objects;
import java.util.UUID;

/**
 * Canonical, order-independent identity for a one-to-one DM conversation.
 */
public record DmParticipantPair(UUID userAId, UUID userBId) {

	public DmParticipantPair {
		Objects.requireNonNull(userAId, "userAId must not be null");
		Objects.requireNonNull(userBId, "userBId must not be null");
		if (userAId.equals(userBId)) {
			throw new IllegalArgumentException("DM participants must be different users");
		}
		if (userAId.toString().compareTo(userBId.toString()) > 0) {
			UUID first = userAId;
			userAId = userBId;
			userBId = first;
		}
	}

	public static DmParticipantPair of(UUID firstUserId, UUID secondUserId) {
		return new DmParticipantPair(firstUserId, secondUserId);
	}
}

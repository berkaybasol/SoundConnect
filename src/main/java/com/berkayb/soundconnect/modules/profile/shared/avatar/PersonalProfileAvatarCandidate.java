package com.berkayb.soundconnect.modules.profile.shared.avatar;

import java.util.UUID;

/**
 * Lightweight, entity-free candidate row for bounded list avatar enrichment.
 * Candidate priority is applied after media visibility/readiness resolution so
 * an unusable higher-priority image cannot hide a displayable personal image.
 */
public record PersonalProfileAvatarCandidate(
		UUID userId,
		UUID musicianMediaId,
		UUID listenerMediaId,
		UUID organizerMediaId,
		UUID producerMediaId
) {
}

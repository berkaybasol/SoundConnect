package com.berkayb.soundconnect.modules.event.performer.dto;

import java.util.UUID;

/**
 * Immutable authorization data used before acquiring mutation locks.
 *
 * <p>A scalar projection is intentional: loading the request entity during the
 * preflight would put a potentially stale instance in the persistence context
 * before the pessimistic lock query runs.</p>
 */
public record EventPerformerRequestAuthorizationSnapshot(
		UUID eventId,
		UUID musicianOwnerUserId,
		UUID bandId
) {
}

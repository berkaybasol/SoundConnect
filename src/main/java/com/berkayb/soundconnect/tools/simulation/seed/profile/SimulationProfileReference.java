package com.berkayb.soundconnect.tools.simulation.seed.profile;

import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest.AccountRole;

import java.util.Objects;
import java.util.UUID;

/** Stable account-to-profile identifiers produced by the profile materialization phase. */
public record SimulationProfileReference(
		String accountKey,
		AccountRole role,
		UUID userId,
		UUID profileId,
		UUID venueAggregateId,
		State state
) {
	public SimulationProfileReference {
		accountKey = requireText(accountKey, "accountKey");
		Objects.requireNonNull(role, "role");
		Objects.requireNonNull(userId, "userId");
		Objects.requireNonNull(state, "state");

		boolean institutionalControl = state == State.INSTITUTION_PENDING_CONTROL
				|| state == State.INSTITUTION_REJECTED_CONTROL;
		if (state == State.UNVERIFIED_CONTROL && role != AccountRole.MUSICIAN) {
			throw new IllegalArgumentException("Only a musician may be an unverified profile control");
		}
		if (state == State.VISIBILITY_PENDING_CONTROL && role != AccountRole.LISTENER) {
			throw new IllegalArgumentException("Only a listener may be a visibility-pending control");
		}
		if (institutionalControl && role != AccountRole.VENUE && role != AccountRole.STUDIO) {
			throw new IllegalArgumentException("Institution controls require a venue or studio role");
		}
		if (institutionalControl != (profileId == null)) {
			throw new IllegalArgumentException(
					"Only pending/rejected institution controls may omit profileId");
		}
		if (role == AccountRole.VENUE && state == State.MATERIALIZED) {
			Objects.requireNonNull(venueAggregateId, "venueAggregateId");
		} else if (venueAggregateId != null) {
			throw new IllegalArgumentException("venueAggregateId is valid only for a materialized venue");
		}
	}

	private static String requireText(String value, String field) {
		if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
		return value.trim();
	}

	public enum State {
		MATERIALIZED,
		UNVERIFIED_CONTROL,
		VISIBILITY_PENDING_CONTROL,
		INSTITUTION_PENDING_CONTROL,
		INSTITUTION_REJECTED_CONTROL
	}
}

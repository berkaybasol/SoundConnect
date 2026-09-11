package com.berkayb.soundconnect.tools.simulation.seed.band;

import java.util.Objects;
import java.util.UUID;

/** Stable identifiers produced for a manifest band. */
public record SimulationBandReference(
		String bandKey,
		UUID bandId,
		String ownerAccountKey,
		UUID ownerUserId
) {
	public SimulationBandReference {
		bandKey = requireText(bandKey, "bandKey");
		Objects.requireNonNull(bandId, "bandId");
		ownerAccountKey = requireText(ownerAccountKey, "ownerAccountKey");
		Objects.requireNonNull(ownerUserId, "ownerUserId");
	}

	private static String requireText(String value, String field) {
		if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
		return value.trim();
	}
}

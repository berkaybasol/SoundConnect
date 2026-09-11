package com.berkayb.soundconnect.tools.simulation.state;

import java.time.Instant;
import java.time.LocalDate;

/** Minimal durable state required to make a partially completed RESUME deterministic. */
public record SimulationWorldState(
		int schemaVersion,
		int worldSchemaVersion,
		String worldId,
		long seed,
		Instant createdAt,
		Instant collabAnchor,
		LocalDate eventAnchor
) {
	public static final int CURRENT_SCHEMA_VERSION = 1;
}

package com.berkayb.soundconnect.tools.simulation.seed.media;

import java.time.Instant;

/** Credential-free, versioned result checkpoint for exact media resume. */
public record SimulationMediaCheckpoint(
		int schemaVersion,
		int worldSchemaVersion,
		String worldId,
		long seed,
		String identityFingerprint,
		Instant createdAt,
		SimulationMediaSeedResult result
) {
	public static final int CURRENT_SCHEMA_VERSION = 1;
}

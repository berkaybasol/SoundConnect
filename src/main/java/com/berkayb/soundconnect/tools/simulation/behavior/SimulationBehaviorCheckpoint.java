package com.berkayb.soundconnect.tools.simulation.behavior;

import java.time.Instant;

/** Credential-free durable cursor for restart-safe population actions. */
public record SimulationBehaviorCheckpoint(
		int schemaVersion,
		int worldSchemaVersion,
		String worldId,
		long seed,
		String planFingerprint,
		int plannedLikeCount,
		int plannedCommentCount,
		int baselineLikeCount,
		int baselineCommentCount,
		int nextLikeIndex,
		int nextCommentIndex,
		Instant updatedAt
) {
	public static final int CURRENT_SCHEMA_VERSION = 2;

	public SimulationBehaviorCheckpoint advanceLike(Instant now) {
		return new SimulationBehaviorCheckpoint(schemaVersion, worldSchemaVersion, worldId, seed,
				planFingerprint, plannedLikeCount, plannedCommentCount,
				baselineLikeCount, baselineCommentCount,
				nextLikeIndex + 1, nextCommentIndex, now);
	}

	public SimulationBehaviorCheckpoint advanceComment(Instant now) {
		return new SimulationBehaviorCheckpoint(schemaVersion, worldSchemaVersion, worldId, seed,
				planFingerprint, plannedLikeCount, plannedCommentCount,
				baselineLikeCount, baselineCommentCount,
				nextLikeIndex, nextCommentIndex + 1, now);
	}
}

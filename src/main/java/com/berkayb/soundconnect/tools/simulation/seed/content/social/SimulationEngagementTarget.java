package com.berkayb.soundconnect.tools.simulation.seed.content.social;

import com.berkayb.soundconnect.modules.engagement.enums.EngagementTargetType;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest.Scene;

import java.util.Objects;
import java.util.UUID;

/** A public, production-readable target made available to the engagement phase. */
public record SimulationEngagementTarget(
		String logicalKey,
		EngagementTargetType targetType,
		UUID targetId,
		String ownerAccountKey,
		UUID ownerUserId,
		Scene scene
) {
	public SimulationEngagementTarget {
		logicalKey = requireText(logicalKey, "logicalKey");
		Objects.requireNonNull(targetType, "targetType");
		Objects.requireNonNull(targetId, "targetId");
		ownerAccountKey = requireText(ownerAccountKey, "ownerAccountKey");
		Objects.requireNonNull(ownerUserId, "ownerUserId");
		Objects.requireNonNull(scene, "scene");
	}

	private static String requireText(String value, String field) {
		if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
		return value.trim();
	}
}

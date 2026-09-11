package com.berkayb.soundconnect.tools.simulation.seed.content.social;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Immutable logical identities produced by the listener social-content phase. */
public record SimulationSocialContentSeedResult(
		Map<String, UUID> overthinkingSourceIds,
		Map<String, UUID> tableGroupIds,
		List<SimulationEngagementTarget> engagementTargets
) {
	public SimulationSocialContentSeedResult {
		overthinkingSourceIds = immutable(overthinkingSourceIds);
		tableGroupIds = immutable(tableGroupIds);
		engagementTargets = engagementTargets == null ? List.of() : List.copyOf(engagementTargets);
	}

	private static Map<String, UUID> immutable(Map<String, UUID> source) {
		return source == null
				? Map.of()
				: Collections.unmodifiableMap(new LinkedHashMap<>(source));
	}
}

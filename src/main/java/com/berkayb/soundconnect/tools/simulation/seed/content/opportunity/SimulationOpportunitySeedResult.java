package com.berkayb.soundconnect.tools.simulation.seed.content.opportunity;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Counts and stable logical-id maps produced by opportunity materialization. */
public record SimulationOpportunitySeedResult(ModuleResult collabs, ModuleResult events) {
	public SimulationOpportunitySeedResult {
		Objects.requireNonNull(collabs, "collabs");
		Objects.requireNonNull(events, "events");
	}

	public record ModuleResult(
			int planned,
			int created,
			int skipped,
			int failed,
			Map<String, UUID> ids
	) {
		public ModuleResult {
			if (planned < 0 || created < 0 || skipped < 0 || failed < 0) {
				throw new IllegalArgumentException("Opportunity result counts cannot be negative");
			}
			if (created + skipped + failed != planned) {
				throw new IllegalArgumentException("Opportunity result counts must add up to planned");
			}
			ids = ids == null
					? Map.of()
					: Collections.unmodifiableMap(new LinkedHashMap<>(ids));
		}
	}
}

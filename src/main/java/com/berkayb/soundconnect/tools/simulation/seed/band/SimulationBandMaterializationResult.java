package com.berkayb.soundconnect.tools.simulation.seed.band;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Immutable logical-key lookup for later band-owned content phases. */
public record SimulationBandMaterializationResult(Map<String, SimulationBandReference> bandsByKey) {
	public SimulationBandMaterializationResult {
		if (bandsByKey == null) throw new IllegalArgumentException("bandsByKey is required");
		LinkedHashMap<String, SimulationBandReference> copy = new LinkedHashMap<>();
		HashSet<UUID> bandIds = new HashSet<>();
		bandsByKey.forEach((key, value) -> {
			if (key == null || key.isBlank() || value == null || !key.equals(value.bandKey())) {
				throw new IllegalArgumentException("Band result contains an invalid logical-key mapping");
			}
			if (!bandIds.add(value.bandId())) {
				throw new IllegalArgumentException("Band result contains a duplicate band id");
			}
			copy.put(key, value);
		});
		bandsByKey = Collections.unmodifiableMap(copy);
	}

	public SimulationBandReference require(String bandKey) {
		SimulationBandReference reference = bandsByKey.get(bandKey);
		if (reference == null) throw new IllegalStateException("Missing simulation band reference: " + bandKey);
		return reference;
	}
}

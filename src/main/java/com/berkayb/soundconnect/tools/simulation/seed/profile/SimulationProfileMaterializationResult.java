package com.berkayb.soundconnect.tools.simulation.seed.profile;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Immutable result shared with media and content seed phases. */
public record SimulationProfileMaterializationResult(
		Map<String, SimulationProfileReference> profilesByAccountKey
) {
	public SimulationProfileMaterializationResult {
		if (profilesByAccountKey == null) throw new IllegalArgumentException("profilesByAccountKey is required");
		LinkedHashMap<String, SimulationProfileReference> copy = new LinkedHashMap<>();
		HashSet<UUID> profileIds = new HashSet<>();
		HashSet<UUID> venueIds = new HashSet<>();
		profilesByAccountKey.forEach((key, value) -> {
			if (key == null || key.isBlank() || value == null || !key.equals(value.accountKey())) {
				throw new IllegalArgumentException("Profile result contains an invalid account mapping");
			}
			if (value.profileId() != null && !profileIds.add(value.profileId())) {
				throw new IllegalArgumentException("Profile result contains a duplicate profile id");
			}
			if (value.venueAggregateId() != null && !venueIds.add(value.venueAggregateId())) {
				throw new IllegalArgumentException("Profile result contains a duplicate venue aggregate id");
			}
			copy.put(key, value);
		});
		profilesByAccountKey = Collections.unmodifiableMap(copy);
	}

	public SimulationProfileReference require(String accountKey) {
		SimulationProfileReference reference = profilesByAccountKey.get(accountKey);
		if (reference == null) {
			throw new IllegalStateException("Missing simulation profile reference: " + accountKey);
		}
		return reference;
	}
}

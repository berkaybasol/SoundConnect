package com.berkayb.soundconnect.tools.simulation.seed.profile;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Outcome of the media-backed, second-phase avatar attachment. */
public record SimulationProfilePictureResult(Map<String, Outcome> outcomesByAccountKey) {
	public SimulationProfilePictureResult {
		if (outcomesByAccountKey == null) throw new IllegalArgumentException("outcomesByAccountKey is required");
		outcomesByAccountKey.forEach((key, value) -> {
			if (key == null || key.isBlank() || value == null) {
				throw new IllegalArgumentException("Profile-picture result contains an invalid outcome");
			}
		});
		outcomesByAccountKey = Collections.unmodifiableMap(new LinkedHashMap<>(outcomesByAccountKey));
	}

	public enum Outcome {
		APPLIED,
		ALREADY_PRESENT,
		INELIGIBLE_CONTROL
	}
}

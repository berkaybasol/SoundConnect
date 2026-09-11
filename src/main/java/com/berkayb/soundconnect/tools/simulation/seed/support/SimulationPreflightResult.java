package com.berkayb.soundconnect.tools.simulation.seed.support;

import com.berkayb.soundconnect.modules.instrument.entity.Instrument;

import java.util.Map;

/** Validated catalog state captured before a destructive fresh rebuild is permitted. */
public record SimulationPreflightResult(
		Map<String, SimulationResolvedLocation> locationsByAccountKey,
		Map<String, Instrument> instrumentsByName
) {
	public SimulationPreflightResult {
		locationsByAccountKey = locationsByAccountKey == null
				? Map.of() : Map.copyOf(locationsByAccountKey);
		instrumentsByName = instrumentsByName == null ? Map.of() : Map.copyOf(instrumentsByName);
	}
}

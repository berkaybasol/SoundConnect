package com.berkayb.soundconnect.tools.simulation.seed.media;

/** Fail-closed diagnostic for a deterministic simulation media seed run. */
public final class SimulationMediaSeedException extends IllegalStateException {

	public SimulationMediaSeedException(String message) {
		super(message);
	}

	public SimulationMediaSeedException(String message, Throwable cause) {
		super(message, cause);
	}
}

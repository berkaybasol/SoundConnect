package com.berkayb.soundconnect.tools.simulation.world;

/** Raised before any simulation side effect when the canonical world is malformed. */
public final class SimulationWorldManifestException extends IllegalStateException {
	public SimulationWorldManifestException(String message) {
		super(message);
	}

	public SimulationWorldManifestException(String message, Throwable cause) {
		super(message, cause);
	}
}

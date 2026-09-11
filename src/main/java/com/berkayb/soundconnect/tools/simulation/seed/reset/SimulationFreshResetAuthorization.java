package com.berkayb.soundconnect.tools.simulation.seed.reset;

/**
 * Narrow authorization boundary for the simulation's destructive fresh rebuild.
 *
 * <p>Keeping this decision separate from the SQL executor makes it impossible for
 * a caller to turn a runtime-safe database reset into a generic truncate helper.</p>
 */
@FunctionalInterface
public interface SimulationFreshResetAuthorization {

	/** Fails closed unless this invocation is explicitly authorized as a fresh reset. */
	void assertFreshResetAuthorized();
}

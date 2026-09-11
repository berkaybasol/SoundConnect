package com.berkayb.soundconnect.tools.simulation.seed.content.opportunity;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Persisted scheduling anchor shared by FRESH and RESUME runs.
 *
 * <p>Event creation has no client request id in the production API. Reusing this
 * exact window is therefore part of the simulation's idempotency contract.</p>
 */
public record SimulationOpportunityWindow(Instant collabAnchor, LocalDate eventAnchor) {
	public SimulationOpportunityWindow {
		Objects.requireNonNull(collabAnchor, "collabAnchor");
		Objects.requireNonNull(eventAnchor, "eventAnchor");
	}
}

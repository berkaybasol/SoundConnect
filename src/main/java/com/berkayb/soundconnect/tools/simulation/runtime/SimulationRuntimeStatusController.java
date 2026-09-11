package com.berkayb.soundconnect.tools.simulation.runtime;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only status endpoint available only in the guarded local simulation profile. */
@RestController
@Profile("local & simulation")
@ConditionalOnProperty(prefix = "app.simulation", name = "enabled", havingValue = "true")
@RequestMapping("/api/v1/public/simulation-status")
public final class SimulationRuntimeStatusController {

	private final SimulationRuntimeStatus status;

	public SimulationRuntimeStatusController(SimulationRuntimeStatus status) {
		this.status = status;
	}

	@GetMapping
	public SimulationRuntimeStatus.Snapshot status() {
		return status.snapshot();
	}
}

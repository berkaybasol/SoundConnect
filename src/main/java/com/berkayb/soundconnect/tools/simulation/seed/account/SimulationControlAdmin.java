package com.berkayb.soundconnect.tools.simulation.seed.account;

import java.util.UUID;

/** Stable reference to the infrastructure identity used for simulated reviews. */
public record SimulationControlAdmin(UUID userId, String username, String email, boolean created) {

	public SimulationControlAdmin {
		if (userId == null) throw new IllegalArgumentException("control admin userId is required");
		if (username == null || username.isBlank()) {
			throw new IllegalArgumentException("control admin username is required");
		}
		if (email == null || email.isBlank()) {
			throw new IllegalArgumentException("control admin email is required");
		}
	}
}

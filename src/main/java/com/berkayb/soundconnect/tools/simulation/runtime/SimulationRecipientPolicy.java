package com.berkayb.soundconnect.tools.simulation.runtime;

import com.berkayb.soundconnect.tools.simulation.SimulationProperties;

import java.util.Locale;
import java.util.Objects;

/** Strict allowlist for all addresses accepted by simulation-only mail seams. */
public final class SimulationRecipientPolicy {

	private final String configuredSuffix;

	public SimulationRecipientPolicy(SimulationProperties properties) {
		this.configuredSuffix = Objects.requireNonNull(properties.getEmailSuffix(), "email suffix")
				.trim()
				.toLowerCase(Locale.ROOT);
		if (!configuredSuffix.startsWith("@") || !configuredSuffix.endsWith(".invalid")) {
			throw new IllegalStateException("Simulation email suffix must be an @domain.invalid suffix");
		}
	}

	public String requireAllowed(String recipient) {
		if (recipient == null) {
			throw new IllegalArgumentException("Simulation mail recipient is required");
		}
		String normalized = recipient.trim().toLowerCase(Locale.ROOT);
		int at = normalized.indexOf('@');
		if (normalized.length() > 254
				|| normalized.indexOf('\r') >= 0
				|| normalized.indexOf('\n') >= 0
				|| at < 1
				|| at != normalized.lastIndexOf('@')
				|| !normalized.endsWith(configuredSuffix)) {
			throw new IllegalArgumentException("Simulation mail recipient is outside the configured .invalid suffix");
		}
		return normalized;
	}
}

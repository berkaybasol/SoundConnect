package com.berkayb.soundconnect.tools.simulation.runtime;

import com.berkayb.soundconnect.tools.simulation.SimulationMode;
import com.berkayb.soundconnect.tools.simulation.SimulationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Credential-free bootstrap state shown to local developers and the debug client. */
@Component
@Profile("local & simulation")
@ConditionalOnProperty(prefix = "app.simulation", name = "enabled", havingValue = "true")
public final class SimulationRuntimeStatus {

	private final SimulationProperties properties;
	private final Clock clock;
	private final AtomicReference<Snapshot> current;

	public SimulationRuntimeStatus(SimulationProperties properties, Clock clock) {
		this.properties = Objects.requireNonNull(properties, "properties");
		this.clock = Objects.requireNonNull(clock, "clock");
		Instant now = clock.instant();
		this.current = new AtomicReference<>(new Snapshot(
				properties.getMode(), State.BOOTSTRAPPING, "startup", null,
				now, now, "Simulation component loaded; bootstrap has not completed."));
	}

	public void phase(String worldId, String phase) {
		update(State.BOOTSTRAPPING, worldId, phase, "Bootstrap phase is running.");
	}

	public void ready(String worldId) {
		update(State.READY, worldId, "ready", "Synthetic world is ready for testing.");
	}

	public void paused() {
		update(State.PAUSED, null, "paused", "Simulation is enabled but intentionally paused.");
	}

	public void failed(String worldId, String phase, Throwable failure) {
		String message = failure == null ? "Simulation bootstrap failed."
				: "Simulation bootstrap failed: " + failure.getClass().getSimpleName();
		update(State.FAILED, worldId, phase, message);
	}

	public Snapshot snapshot() {
		return current.get();
	}

	private void update(State state, String worldId, String phase, String message) {
		Snapshot previous = current.get();
		current.set(new Snapshot(
				properties.getMode(), state, clean(phase), clean(worldId),
				previous.startedAt(), clock.instant(), clean(message)));
	}

	private static String clean(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}

	public enum State {
		BOOTSTRAPPING,
		READY,
		PAUSED,
		FAILED
	}

	public record Snapshot(
			SimulationMode mode,
			State state,
			String phase,
			String worldId,
			Instant startedAt,
			Instant updatedAt,
			String message
	) {
	}
}

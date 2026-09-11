package com.berkayb.soundconnect.tools.simulation.state;

import com.berkayb.soundconnect.tools.simulation.SimulationMode;
import com.berkayb.soundconnect.tools.simulation.SimulationProperties;
import com.berkayb.soundconnect.tools.simulation.SimulationRuntimeGuard;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Objects;

/** Atomic, credential-free local state used by FRESH/RESUME/FAST_FORWARD. */
@Component
@Profile("local & simulation")
@ConditionalOnProperty(prefix = "app.simulation", name = "enabled", havingValue = "true")
public final class SimulationWorldStateStore {

	private static final ZoneId PRODUCT_ZONE = ZoneId.of("Europe/Istanbul");

	private final SimulationRuntimeGuard runtimeGuard;
	private final SimulationProperties properties;
	private final ObjectMapper writer;
	private final ObjectReader reader;
	private final Clock clock;

	public SimulationWorldStateStore(
			SimulationRuntimeGuard runtimeGuard,
			SimulationProperties properties,
			ObjectMapper objectMapper,
			Clock clock
	) {
		this.runtimeGuard = Objects.requireNonNull(runtimeGuard, "runtimeGuard");
		this.properties = Objects.requireNonNull(properties, "properties");
		this.writer = Objects.requireNonNull(objectMapper, "objectMapper").copy().findAndRegisterModules();
		this.reader = this.writer.readerFor(SimulationWorldState.class)
				.with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
				.with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
				.with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
		this.clock = Objects.requireNonNull(clock, "clock");
	}

	public SimulationWorldState initializeOrLoad(SimulationWorldManifest manifest) {
		runtimeGuard.assertRuntimeAllowed();
		Objects.requireNonNull(manifest, "manifest");
		return switch (properties.getMode()) {
			case FRESH -> createFresh(manifest);
			case RESUME, FAST_FORWARD -> loadExisting(manifest);
			case PAUSE -> throw new IllegalStateException("PAUSE mode does not materialize simulation state");
		};
	}

	Path statePath(SimulationWorldManifest manifest) {
		String safeWorld = manifest.worldId().toLowerCase(Locale.ROOT)
				.replaceAll("[^a-z0-9._-]", "-");
		return properties.getReportDirectory().toAbsolutePath().normalize()
				.resolve(safeWorld + "-state.json");
	}

	private SimulationWorldState createFresh(SimulationWorldManifest manifest) {
		Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
		SimulationWorldState state = new SimulationWorldState(
				SimulationWorldState.CURRENT_SCHEMA_VERSION,
				manifest.schemaVersion(),
				manifest.worldId(),
				manifest.seed(),
				now,
				now,
				LocalDate.ofInstant(now, PRODUCT_ZONE)
		);
		writeAtomically(statePath(manifest), state);
		return state;
	}

	private SimulationWorldState loadExisting(SimulationWorldManifest manifest) {
		Path path = statePath(manifest);
		if (!Files.isRegularFile(path)) {
			throw new IllegalStateException(
					"Simulation RESUME/FAST_FORWARD requires state from an earlier FRESH run: " + path);
		}
		try {
			SimulationWorldState state = reader.readValue(path.toFile());
			validate(state, manifest);
			return state;
		} catch (IOException failure) {
			throw new IllegalStateException("Simulation world state cannot be read: " + path, failure);
		}
	}

	private static void validate(SimulationWorldState state, SimulationWorldManifest manifest) {
		if (state == null
				|| state.schemaVersion() != SimulationWorldState.CURRENT_SCHEMA_VERSION
				|| state.worldSchemaVersion() != manifest.schemaVersion()
				|| !manifest.worldId().equals(state.worldId())
				|| state.seed() != manifest.seed()
				|| state.createdAt() == null
				|| state.collabAnchor() == null
				|| state.eventAnchor() == null) {
			throw new IllegalStateException("Simulation world state does not match the current manifest");
		}
	}

	private void writeAtomically(Path target, SimulationWorldState state) {
		Path temporary = null;
		try {
			Files.createDirectories(target.getParent());
			temporary = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
			writer.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), state);
			try {
				Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException ignored) {
				Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException failure) {
			throw new IllegalStateException("Simulation world state cannot be written: " + target, failure);
		} finally {
			if (temporary != null) {
				try {
					Files.deleteIfExists(temporary);
				} catch (IOException ignored) {
					// The destination has already been written or the original failure is more useful.
				}
			}
		}
	}
}

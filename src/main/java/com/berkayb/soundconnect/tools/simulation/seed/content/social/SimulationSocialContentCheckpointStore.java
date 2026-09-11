package com.berkayb.soundconnect.tools.simulation.seed.content.social;

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
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Atomic persistence boundary for server-generated social-content identities. */
@Component
@Profile("local & simulation")
@ConditionalOnProperty(prefix = "app.simulation", name = "enabled", havingValue = "true")
public final class SimulationSocialContentCheckpointStore {

	private final SimulationRuntimeGuard runtimeGuard;
	private final SimulationProperties properties;
	private final ObjectMapper writer;
	private final ObjectReader reader;

	public SimulationSocialContentCheckpointStore(
			SimulationRuntimeGuard runtimeGuard,
			SimulationProperties properties,
			ObjectMapper objectMapper
	) {
		this.runtimeGuard = Objects.requireNonNull(runtimeGuard, "runtimeGuard");
		this.properties = Objects.requireNonNull(properties, "properties");
		this.writer = Objects.requireNonNull(objectMapper, "objectMapper").copy().findAndRegisterModules();
		this.reader = writer.readerFor(SimulationSocialContentCheckpoint.class)
				.with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
				.with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
				.with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
	}

	public synchronized void saveFresh(
			SimulationWorldManifest manifest,
			SimulationSocialContentCheckpoint checkpoint
	) {
		runtimeGuard.assertRuntimeAllowed();
		if (properties.getMode() != SimulationMode.FRESH) {
			throw new IllegalStateException("Only FRESH mode may replace the social-content checkpoint");
		}
		validate(checkpoint, Objects.requireNonNull(manifest, "manifest"));
		writeAtomically(path(manifest), checkpoint);
	}

	public synchronized SimulationSocialContentCheckpoint loadExisting(SimulationWorldManifest manifest) {
		runtimeGuard.assertRuntimeAllowed();
		Objects.requireNonNull(manifest, "manifest");
		if (properties.getMode() != SimulationMode.RESUME
				&& properties.getMode() != SimulationMode.FAST_FORWARD) {
			throw new IllegalStateException("Only RESUME or FAST_FORWARD may load social-content state");
		}
		Path checkpointPath = path(manifest);
		if (!Files.isRegularFile(checkpointPath)) {
			throw new IllegalStateException(
					"Simulation RESUME/FAST_FORWARD requires social state from a completed FRESH run: "
							+ checkpointPath);
		}
		try {
			SimulationSocialContentCheckpoint checkpoint = reader.readValue(checkpointPath.toFile());
			validate(checkpoint, manifest);
			return checkpoint;
		} catch (IOException failure) {
			throw new IllegalStateException(
					"Simulation social-content checkpoint cannot be read: " + checkpointPath, failure);
		}
	}

	Path path(SimulationWorldManifest manifest) {
		String safeWorld = manifest.worldId().toLowerCase(Locale.ROOT)
				.replaceAll("[^a-z0-9._-]", "-");
		return properties.getReportDirectory().toAbsolutePath().normalize()
				.resolve(safeWorld + "-social.json");
	}

	private static void validate(
			SimulationSocialContentCheckpoint checkpoint,
			SimulationWorldManifest manifest
	) {
		if (checkpoint == null
				|| checkpoint.schemaVersion() != SimulationSocialContentCheckpoint.CURRENT_SCHEMA_VERSION
				|| checkpoint.worldSchemaVersion() != manifest.schemaVersion()
				|| !manifest.worldId().equals(checkpoint.worldId())
				|| checkpoint.seed() != manifest.seed()
				|| checkpoint.overthinkingPublications().size()
				!= manifest.contentTargets().overthinkingProfileShares()
				|| checkpoint.tableGroups().size() != 3
				|| checkpoint.tableGroupPublications().size()
				!= manifest.contentTargets().tableGroupProfileShares()
				|| checkpoint.eventPublications().size()
				!= manifest.contentTargets().listenerEventProfilePublications()) {
			throw new IllegalStateException("Simulation social-content checkpoint does not match the world");
		}

		Set<String> logicalKeys = new HashSet<>();
		Set<String> sourceIdentities = new HashSet<>();
		for (var item : checkpoint.overthinkingPublications()) {
			unique(logicalKeys, item.logicalKey(), "social logical key");
			unique(sourceIdentities, "OVERTHINKING|" + item.sourcePostId(), "social source identity");
			unique(sourceIdentities, "OVERTHINKING_PROFILE_SHARE|" + item.profileShareId(),
					"social target identity");
		}
		Set<UUID> tableIds = new HashSet<>();
		Set<String> tableKeys = new HashSet<>();
		for (var item : checkpoint.tableGroups()) {
			unique(tableKeys, item.logicalKey(), "TableGroup logical key");
			if (!tableIds.add(item.tableGroupId())) {
				throw new IllegalStateException("Duplicate TableGroup id in social-content checkpoint");
			}
		}
		for (var item : checkpoint.tableGroupPublications()) {
			unique(logicalKeys, item.logicalKey(), "social logical key");
			if (!tableKeys.contains(item.tableLogicalKey()) || !tableIds.contains(item.tableGroupId())) {
				throw new IllegalStateException("TableGroup publication references an unknown aggregate");
			}
			unique(sourceIdentities, "TABLE_GROUP_POST|" + item.profileShareId(), "social target identity");
		}
		Set<String> actorEventPairs = new HashSet<>();
		for (var item : checkpoint.eventPublications()) {
			unique(logicalKeys, item.logicalKey(), "social logical key");
			unique(sourceIdentities, "EVENT_POST|" + item.profilePostId(), "social target identity");
			unique(actorEventPairs, item.listenerUserId() + "|" + item.eventId(), "listener/Event pair");
		}
	}

	private static void unique(Set<String> values, String value, String description) {
		if (!values.add(value)) throw new IllegalStateException("Duplicate " + description + ": " + value);
	}

	private void writeAtomically(Path target, SimulationSocialContentCheckpoint checkpoint) {
		Path temporary = null;
		try {
			Files.createDirectories(target.getParent());
			temporary = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
			writer.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), checkpoint);
			try {
				Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException ignored) {
				Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException failure) {
			throw new IllegalStateException("Simulation social-content checkpoint cannot be written: " + target, failure);
		} finally {
			if (temporary != null) {
				try {
					Files.deleteIfExists(temporary);
				} catch (IOException ignored) {
					// The destination or the original write failure is authoritative.
				}
			}
		}
	}
}

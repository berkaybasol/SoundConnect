package com.berkayb.soundconnect.tools.simulation.seed.media;

import com.berkayb.soundconnect.tools.simulation.SimulationProperties;
import com.berkayb.soundconnect.tools.simulation.SimulationRuntimeGuard;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/** Atomic, bounded and credential-free persistence for exact media resume. */
public final class SimulationMediaCheckpointStore {

	private static final long MAX_CHECKPOINT_BYTES = 512L * 1024L;

	private final SimulationRuntimeGuard runtimeGuard;
	private final SimulationProperties properties;
	private final Path reportDirectory;
	private final ObjectMapper writer;
	private final ObjectReader reader;
	private final Clock clock;

	public SimulationMediaCheckpointStore(
			SimulationRuntimeGuard runtimeGuard,
			SimulationProperties properties,
			ObjectMapper objectMapper,
			Clock clock
	) {
		this.runtimeGuard = Objects.requireNonNull(runtimeGuard, "runtimeGuard");
		this.properties = Objects.requireNonNull(properties, "properties");
		this.reportDirectory = properties
				.getReportDirectory().toAbsolutePath().normalize();
		this.writer = Objects.requireNonNull(objectMapper, "objectMapper")
				.copy()
				.findAndRegisterModules()
				.enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
				.enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
		this.reader = writer.readerFor(SimulationMediaCheckpoint.class)
				.with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
				.with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
				.with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
		this.clock = Objects.requireNonNull(clock, "clock");
	}

	public synchronized Optional<SimulationMediaCheckpoint> loadIfPresent(
			SimulationWorldManifest manifest
	) {
		runtimeGuard.assertRuntimeAllowed();
		Path path = checkpointPath(manifest);
		if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
		if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
			throw new SimulationMediaSeedException("Simulation media checkpoint is unsafe");
		}
		try {
			long size = Files.size(path);
			if (size < 1 || size > MAX_CHECKPOINT_BYTES) {
				throw new SimulationMediaSeedException(
						"Simulation media checkpoint exceeds its hard bound");
			}
			SimulationMediaCheckpoint checkpoint = reader.readValue(path.toFile());
			validateShape(checkpoint);
			return Optional.of(checkpoint);
		} catch (IOException failure) {
			throw new SimulationMediaSeedException(
					"Simulation media checkpoint cannot be read", failure);
		}
	}

	public synchronized void save(
			SimulationWorldManifest manifest,
			String identityFingerprint,
			SimulationMediaSeedResult result
	) {
		runtimeGuard.assertRuntimeAllowed();
		SimulationMediaCheckpoint checkpoint = new SimulationMediaCheckpoint(
				SimulationMediaCheckpoint.CURRENT_SCHEMA_VERSION,
				manifest.schemaVersion(),
				manifest.worldId(),
				manifest.seed(),
				identityFingerprint,
				clock.instant(),
				Objects.requireNonNull(result, "result"));
		validateShape(checkpoint);
		writeAtomically(checkpointPath(manifest), checkpoint);
	}

	/** Deletes exactly this world's stale result authority before a FRESH rebuild. */
	public synchronized void resetForFresh(SimulationWorldManifest manifest) {
		runtimeGuard.assertRuntimeAllowed();
		if (properties.getMode() != com.berkayb.soundconnect.tools.simulation.SimulationMode.FRESH) {
			throw new SimulationMediaSeedException(
					"Simulation media checkpoint reset requires FRESH mode");
		}
		Path target = checkpointPath(manifest);
		try {
			if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
				if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
						|| Files.isSymbolicLink(target)) {
					throw new SimulationMediaSeedException(
							"Simulation media checkpoint reset target is unsafe");
				}
				runtimeGuard.assertRuntimeAllowed();
				Files.delete(target);
			}
		} catch (IOException failure) {
			throw new SimulationMediaSeedException(
					"Simulation media checkpoint cannot be reset", failure);
		}
	}

	public String identityFingerprint(
			SimulationWorldManifest manifest,
			Map<String, UUID> accountUserIds,
			Map<String, UUID> profileIds,
			Map<String, UUID> bandIds
	) {
		runtimeGuard.assertRuntimeAllowed();
		Objects.requireNonNull(manifest, "manifest");
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			update(digest, "simulation-media-identity-v1");
			digest.update(writer.writeValueAsBytes(manifest));
			updateMap(digest, "accountUserIds", accountUserIds);
			updateMap(digest, "profileIds", profileIds);
			updateMap(digest, "bandIds", bandIds);
			return HexFormat.of().formatHex(digest.digest());
		} catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException("SHA-256 is unavailable", impossible);
		} catch (IOException serializationFailure) {
			throw new SimulationMediaSeedException(
					"Simulation media identity cannot be fingerprinted", serializationFailure);
		}
	}

	public boolean matches(
			SimulationMediaCheckpoint checkpoint,
			SimulationWorldManifest manifest,
			String identityFingerprint
	) {
		runtimeGuard.assertRuntimeAllowed();
		return checkpoint != null
				&& manifest != null
				&& checkpoint.schemaVersion() == SimulationMediaCheckpoint.CURRENT_SCHEMA_VERSION
				&& checkpoint.worldSchemaVersion() == manifest.schemaVersion()
				&& Objects.equals(checkpoint.worldId(), manifest.worldId())
				&& checkpoint.seed() == manifest.seed()
				&& Objects.equals(checkpoint.identityFingerprint(), identityFingerprint);
	}

	Path checkpointPath(SimulationWorldManifest manifest) {
		Objects.requireNonNull(manifest, "manifest");
		String worldId = Objects.requireNonNull(manifest.worldId(), "manifest.worldId");
		String safeWorld = worldId.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "-");
		if (safeWorld.isBlank() || safeWorld.length() > 160) {
			throw new SimulationMediaSeedException("Simulation world id is unsafe for checkpointing");
		}
		String suffix = shortDigest(worldId);
		Path path = reportDirectory.resolve(safeWorld + "-" + suffix + "-media-v1.json").normalize();
		if (!path.startsWith(reportDirectory)) {
			throw new SimulationMediaSeedException("Simulation media checkpoint escapes report-directory");
		}
		assertNoSymlinkComponents(reportDirectory);
		return path;
	}

	private void writeAtomically(Path target, SimulationMediaCheckpoint checkpoint) {
		Path temporary = null;
		try {
			assertNoSymlinkComponents(reportDirectory);
			Files.createDirectories(reportDirectory);
			assertNoSymlinkComponents(reportDirectory);
			if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(target)) {
				throw new SimulationMediaSeedException("Simulation media checkpoint target is unsafe");
			}
			temporary = Files.createTempFile(
					reportDirectory, target.getFileName().toString(), ".tmp");
			writer.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), checkpoint);
			if (Files.size(temporary) < 1 || Files.size(temporary) > MAX_CHECKPOINT_BYTES) {
				throw new SimulationMediaSeedException(
						"Simulation media checkpoint exceeds its hard bound");
			}
			runtimeGuard.assertRuntimeAllowed();
			try {
				Files.move(
						temporary,
						target,
						StandardCopyOption.ATOMIC_MOVE,
						StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException unsupported) {
				Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException failure) {
			throw new SimulationMediaSeedException(
					"Simulation media checkpoint cannot be written", failure);
		} finally {
			if (temporary != null) {
				try {
					Files.deleteIfExists(temporary);
				} catch (IOException ignored) {
					// The committed checkpoint or original failure remains authoritative.
				}
			}
		}
	}

	private static void validateShape(SimulationMediaCheckpoint checkpoint) {
		if (checkpoint == null
				|| checkpoint.schemaVersion() != SimulationMediaCheckpoint.CURRENT_SCHEMA_VERSION
				|| checkpoint.worldSchemaVersion() < 1
				|| checkpoint.worldId() == null
				|| checkpoint.worldId().isBlank()
				|| checkpoint.worldId().length() > 256
				|| checkpoint.identityFingerprint() == null
				|| !checkpoint.identityFingerprint().matches("[0-9a-f]{64}")
				|| checkpoint.createdAt() == null
				|| checkpoint.result() == null) {
			throw new SimulationMediaSeedException(
					"Simulation media checkpoint has an invalid contract");
		}
	}

	private static void updateMap(
			MessageDigest digest,
			String label,
			Map<String, UUID> source
	) {
		Objects.requireNonNull(source, label);
		update(digest, label);
		for (Map.Entry<String, UUID> entry : new TreeMap<>(source).entrySet()) {
			if (entry.getKey() == null || entry.getValue() == null) {
				throw new SimulationMediaSeedException(label + " contains an incomplete identity");
			}
			update(digest, entry.getKey());
			update(digest, entry.getValue().toString());
		}
	}

	private static void update(MessageDigest digest, String value) {
		byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
		digest.update((byte) (bytes.length >>> 24));
		digest.update((byte) (bytes.length >>> 16));
		digest.update((byte) (bytes.length >>> 8));
		digest.update((byte) bytes.length);
		digest.update(bytes);
	}

	private static String shortDigest(String value) {
		try {
			return HexFormat.of().formatHex(
					MessageDigest.getInstance("SHA-256")
							.digest(value.getBytes(StandardCharsets.UTF_8)))
					.substring(0, 12);
		} catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException("SHA-256 is unavailable", impossible);
		}
	}

	private static void assertNoSymlinkComponents(Path path) {
		Path absolute = path.toAbsolutePath().normalize();
		Path current = absolute.getRoot();
		if (current == null) {
			throw new SimulationMediaSeedException("Simulation report-directory must be absolute");
		}
		for (Path segment : absolute) {
			current = current.resolve(segment);
			if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)
					&& Files.isSymbolicLink(current)) {
				throw new SimulationMediaSeedException(
						"Simulation report-directory contains a symbolic link");
			}
		}
	}
}

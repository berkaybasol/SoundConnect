package com.berkayb.soundconnect.tools.simulation.behavior;

import com.berkayb.soundconnect.tools.simulation.SimulationMode;
import com.berkayb.soundconnect.tools.simulation.SimulationProperties;
import com.berkayb.soundconnect.tools.simulation.SimulationRuntimeGuard;
import com.berkayb.soundconnect.tools.simulation.seed.engagement.SimulationEngagementPlan;
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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.Locale;
import java.util.Objects;

/** Atomic checkpoint store; it contains only world identity and action indices. */
@Component
@Profile("local & simulation")
@ConditionalOnProperty(prefix = "app.simulation", name = "enabled", havingValue = "true")
public final class SimulationBehaviorCheckpointStore {

	private final SimulationRuntimeGuard runtimeGuard;
	private final SimulationProperties properties;
	private final ObjectMapper writer;
	private final ObjectReader reader;
	private final Clock clock;

	public SimulationBehaviorCheckpointStore(
			SimulationRuntimeGuard runtimeGuard,
			SimulationProperties properties,
			ObjectMapper objectMapper,
			Clock clock
	) {
		this.runtimeGuard = Objects.requireNonNull(runtimeGuard, "runtimeGuard");
		this.properties = Objects.requireNonNull(properties, "properties");
		this.writer = Objects.requireNonNull(objectMapper, "objectMapper").copy().findAndRegisterModules();
		this.reader = writer.readerFor(SimulationBehaviorCheckpoint.class)
				.with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
				.with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
				.with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
		this.clock = Objects.requireNonNull(clock, "clock");
	}

	public synchronized SimulationBehaviorCheckpoint initialize(
			SimulationWorldManifest manifest,
			SimulationEngagementPlan plan,
			int baselineLikes,
			int baselineComments
	) {
		runtimeGuard.assertRuntimeAllowed();
		Objects.requireNonNull(manifest, "manifest");
		Objects.requireNonNull(plan, "plan");
		if (baselineLikes < 0 || baselineComments < 0) {
			throw new IllegalArgumentException("Behavior baseline cursors cannot be negative");
		}
		if (baselineLikes > plan.likes().size() || baselineComments > plan.comments().size()) {
			throw new IllegalArgumentException("Behavior baseline exceeds the supplied plan");
		}
		validateConfiguredBaseline(manifest, baselineLikes, baselineComments);
		String fingerprint = fingerprint(manifest, plan);
		return switch (properties.getMode()) {
			case FRESH -> create(manifest, plan, fingerprint, baselineLikes, baselineComments);
			case RESUME, FAST_FORWARD -> loadOrCreate(
					manifest, plan, fingerprint, baselineLikes, baselineComments);
			case PAUSE -> throw new IllegalStateException("PAUSE mode cannot initialize behavior state");
		};
	}

	public synchronized void save(
			SimulationWorldManifest manifest,
			SimulationEngagementPlan plan,
			SimulationBehaviorCheckpoint checkpoint
	) {
		runtimeGuard.assertRuntimeAllowed();
		Objects.requireNonNull(plan, "plan");
		Objects.requireNonNull(checkpoint, "checkpoint");
		validate(checkpoint, manifest, plan, fingerprint(manifest, plan),
				manifest.contentTargets().likes(), manifest.contentTargets().comments());
		write(path(manifest), checkpoint);
	}

	private static void validateConfiguredBaseline(
			SimulationWorldManifest manifest,
			int baselineLikes,
			int baselineComments
	) {
		if (manifest.contentTargets() == null
				|| baselineLikes != manifest.contentTargets().likes()
				|| baselineComments != manifest.contentTargets().comments()) {
			throw new IllegalStateException("Behavior baseline does not match the world manifest");
		}
	}

	private SimulationBehaviorCheckpoint loadOrCreate(
			SimulationWorldManifest manifest,
			SimulationEngagementPlan plan,
			String fingerprint,
			int baselineLikes,
			int baselineComments
	) {
		Path path = path(manifest);
		if (!Files.isRegularFile(path)) {
			return create(manifest, plan, fingerprint, baselineLikes, baselineComments);
		}
		try {
			SimulationBehaviorCheckpoint checkpoint = reader.readValue(path.toFile());
			validate(checkpoint, manifest, plan, fingerprint, baselineLikes, baselineComments);
			if (checkpoint.nextLikeIndex() < baselineLikes
					|| checkpoint.nextCommentIndex() < baselineComments) {
				throw new IllegalStateException("Behavior checkpoint precedes the materialized baseline");
			}
			return checkpoint;
		} catch (IOException failure) {
			throw new IllegalStateException("Simulation behavior checkpoint cannot be read: " + path, failure);
		}
	}

	private SimulationBehaviorCheckpoint create(
			SimulationWorldManifest manifest,
			SimulationEngagementPlan plan,
			String fingerprint,
			int baselineLikes,
			int baselineComments
	) {
		SimulationBehaviorCheckpoint checkpoint = new SimulationBehaviorCheckpoint(
				SimulationBehaviorCheckpoint.CURRENT_SCHEMA_VERSION,
				manifest.schemaVersion(), manifest.worldId(), manifest.seed(), fingerprint,
				plan.likes().size(), plan.comments().size(), baselineLikes, baselineComments,
				baselineLikes, baselineComments, clock.instant());
		write(path(manifest), checkpoint);
		return checkpoint;
	}

	private static void validate(
			SimulationBehaviorCheckpoint checkpoint,
			SimulationWorldManifest manifest,
			SimulationEngagementPlan plan,
			String fingerprint,
			int baselineLikes,
			int baselineComments
	) {
		validateStandalone(checkpoint, manifest);
		if (!fingerprint.equals(checkpoint.planFingerprint())
				|| checkpoint.plannedLikeCount() != plan.likes().size()
				|| checkpoint.plannedCommentCount() != plan.comments().size()
				|| checkpoint.baselineLikeCount() != baselineLikes
				|| checkpoint.baselineCommentCount() != baselineComments) {
			throw new IllegalStateException("Simulation behavior checkpoint does not match the world");
		}
	}

	private static void validateStandalone(
			SimulationBehaviorCheckpoint checkpoint,
			SimulationWorldManifest manifest
	) {
		Objects.requireNonNull(manifest, "manifest");
		if (checkpoint == null
				|| checkpoint.schemaVersion() != SimulationBehaviorCheckpoint.CURRENT_SCHEMA_VERSION
				|| checkpoint.worldSchemaVersion() != manifest.schemaVersion()
				|| !manifest.worldId().equals(checkpoint.worldId())
				|| manifest.seed() != checkpoint.seed()
				|| checkpoint.planFingerprint() == null
				|| checkpoint.planFingerprint().isBlank()
				|| checkpoint.plannedLikeCount() < 0
				|| checkpoint.plannedCommentCount() < 0
				|| checkpoint.baselineLikeCount() < 0
				|| checkpoint.baselineCommentCount() < 0
				|| checkpoint.baselineLikeCount() > checkpoint.plannedLikeCount()
				|| checkpoint.baselineCommentCount() > checkpoint.plannedCommentCount()
				|| checkpoint.nextLikeIndex() < checkpoint.baselineLikeCount()
				|| checkpoint.nextCommentIndex() < checkpoint.baselineCommentCount()
				|| checkpoint.nextLikeIndex() > checkpoint.plannedLikeCount()
				|| checkpoint.nextCommentIndex() > checkpoint.plannedCommentCount()
				|| checkpoint.updatedAt() == null) {
			throw new IllegalStateException("Simulation behavior checkpoint does not match the world");
		}
	}

	private static String fingerprint(
			SimulationWorldManifest manifest,
			SimulationEngagementPlan plan
	) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			update(digest, Integer.toString(manifest.schemaVersion()));
			update(digest, manifest.worldId());
			update(digest, Long.toString(manifest.seed()));
			for (var action : plan.likes()) {
				update(digest, "LIKE");
				update(digest, action.logicalKey());
				update(digest, action.actorAccountKey());
				update(digest, String.valueOf(action.actorUserId()));
				updateTarget(digest, action.target());
			}
			for (var action : plan.comments()) {
				update(digest, "COMMENT");
				update(digest, action.logicalKey());
				update(digest, action.actorAccountKey());
				update(digest, String.valueOf(action.actorUserId()));
				updateTarget(digest, action.target());
				update(digest, action.text().strip());
			}
			return java.util.HexFormat.of().formatHex(digest.digest());
		} catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException("SHA-256 is unavailable", impossible);
		}
	}

	private static void updateTarget(
			MessageDigest digest,
			com.berkayb.soundconnect.tools.simulation.seed.content.social.SimulationEngagementTarget target
	) {
		if (target == null) throw new IllegalStateException("Behavior plan target is missing");
		update(digest, target.logicalKey());
		update(digest, target.targetType().name());
		update(digest, String.valueOf(target.targetId()));
		update(digest, target.ownerAccountKey());
		update(digest, String.valueOf(target.ownerUserId()));
		update(digest, target.scene().name());
	}

	private static void update(MessageDigest digest, String value) {
		if (value == null) throw new IllegalStateException("Behavior plan identity is incomplete");
		byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
		digest.update((byte) (bytes.length >>> 24));
		digest.update((byte) (bytes.length >>> 16));
		digest.update((byte) (bytes.length >>> 8));
		digest.update((byte) bytes.length);
		digest.update(bytes);
	}

	private Path path(SimulationWorldManifest manifest) {
		String safeWorld = manifest.worldId().toLowerCase(Locale.ROOT)
				.replaceAll("[^a-z0-9._-]", "-");
		return properties.getReportDirectory().toAbsolutePath().normalize()
				.resolve(safeWorld + "-behavior.json");
	}

	private void write(Path target, SimulationBehaviorCheckpoint checkpoint) {
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
			throw new IllegalStateException("Simulation behavior checkpoint cannot be written: " + target, failure);
		} finally {
			if (temporary != null) {
				try { Files.deleteIfExists(temporary); }
				catch (IOException ignored) { /* Original write result is authoritative. */ }
			}
		}
	}
}

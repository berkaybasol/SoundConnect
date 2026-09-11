package com.berkayb.soundconnect.tools.simulation.behavior;

import com.berkayb.soundconnect.tools.simulation.SimulationMode;
import com.berkayb.soundconnect.tools.simulation.SimulationProperties;
import com.berkayb.soundconnect.tools.simulation.SimulationRuntimeGuard;
import com.berkayb.soundconnect.tools.simulation.seed.engagement.SimulationEngagementPlan;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifestLoader;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class SimulationBehaviorCheckpointStoreTest {

	@TempDir Path temporary;

	@Test
	void freshThenResumeRetainsDurableCursors() {
		SimulationProperties properties = new SimulationProperties();
		properties.setEnabled(true);
		properties.setMode(SimulationMode.FRESH);
		properties.setReportDirectory(temporary);
		Clock clock = Clock.fixed(Instant.parse("2026-09-11T12:00:00Z"), ZoneOffset.UTC);
		var manifest = new SimulationWorldManifestLoader(new ObjectMapper()).loadDefault();
		SimulationBehaviorCheckpointStore store = new SimulationBehaviorCheckpointStore(
				mock(SimulationRuntimeGuard.class), properties, new ObjectMapper(), clock);
		SimulationEngagementPlan plan = new SimulationEngagementPlan(
				java.util.Collections.nCopies(301, likeAction()),
				java.util.Collections.nCopies(101, commentAction()));

		SimulationBehaviorCheckpoint initial = store.initialize(manifest, plan, 300, 100);
		SimulationBehaviorCheckpoint advanced = initial.advanceLike(clock.instant()).advanceComment(clock.instant());
		store.save(manifest, plan, advanced);

		properties.setMode(SimulationMode.RESUME);
		SimulationBehaviorCheckpoint resumed = store.initialize(manifest, plan, 300, 100);
		assertThat(resumed.nextLikeIndex()).isEqualTo(301);
		assertThat(resumed.nextCommentIndex()).isEqualTo(101);
		assertThat(resumed.worldId()).isEqualTo(manifest.worldId());
	}

	@Test
	void resumeRejectsAChangedActionPlan() {
		SimulationProperties properties = new SimulationProperties();
		properties.setEnabled(true);
		properties.setMode(SimulationMode.FRESH);
		properties.setReportDirectory(temporary);
		Clock clock = Clock.fixed(Instant.parse("2026-09-11T12:00:00Z"), ZoneOffset.UTC);
		var manifest = new SimulationWorldManifestLoader(new ObjectMapper()).loadDefault();
		SimulationBehaviorCheckpointStore store = new SimulationBehaviorCheckpointStore(
				mock(SimulationRuntimeGuard.class), properties, new ObjectMapper(), clock);
		SimulationEngagementPlan original = new SimulationEngagementPlan(
				java.util.Collections.nCopies(300, likeAction()),
				java.util.Collections.nCopies(100, commentAction()));
		store.initialize(manifest, original, 300, 100);

		properties.setMode(SimulationMode.RESUME);
		SimulationEngagementPlan changed = new SimulationEngagementPlan(
				java.util.Collections.nCopies(300, likeAction()),
				java.util.Collections.nCopies(100, new SimulationEngagementPlan.CommentAction(
						"comment", "actor",
						java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"),
						target(), "değişmiş yorum")));

		assertThatThrownBy(() -> store.initialize(manifest, changed, 300, 100))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("does not match");
	}

	private static SimulationEngagementPlan.LikeAction likeAction() {
		return new SimulationEngagementPlan.LikeAction(
				"like", "actor", java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"), target());
	}

	private static SimulationEngagementPlan.CommentAction commentAction() {
		return new SimulationEngagementPlan.CommentAction(
				"comment", "actor", java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"),
				target(), "yorum");
	}

	private static com.berkayb.soundconnect.tools.simulation.seed.content.social.SimulationEngagementTarget target() {
		return new com.berkayb.soundconnect.tools.simulation.seed.content.social.SimulationEngagementTarget(
				"target", com.berkayb.soundconnect.modules.engagement.enums.EngagementTargetType.EVENT,
				java.util.UUID.fromString("00000000-0000-0000-0000-000000000002"),
				"owner", java.util.UUID.fromString("00000000-0000-0000-0000-000000000003"),
				com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest.Scene.ISTANBUL_ALTERNATIVE_ROCK);
	}
}

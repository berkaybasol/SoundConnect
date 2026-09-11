package com.berkayb.soundconnect.tools.simulation.behavior;

import com.berkayb.soundconnect.modules.engagement.enums.EngagementTargetType;
import com.berkayb.soundconnect.tools.simulation.SimulationProperties;
import com.berkayb.soundconnect.tools.simulation.SimulationRuntimeGuard;
import com.berkayb.soundconnect.tools.simulation.report.SimulationRunLedger;
import com.berkayb.soundconnect.tools.simulation.report.SimulationRunReportWriter;
import com.berkayb.soundconnect.tools.simulation.runtime.SimulationWorldLease;
import com.berkayb.soundconnect.tools.simulation.seed.content.social.SimulationEngagementTarget;
import com.berkayb.soundconnect.tools.simulation.seed.engagement.SimulationEngagementPlan;
import com.berkayb.soundconnect.tools.simulation.seed.engagement.SimulationEngagementSeeder;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifestLoader;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SimulationPopulationEngineTest {

	@Mock private SimulationRuntimeGuard guard;
	@Mock private SimulationWorldLease worldLease;
	@Mock private SimulationBehaviorCheckpointStore checkpoints;
	@Mock private SimulationEngagementSeeder seeder;
	@Mock private SimulationRunReportWriter reports;

	@Test
	void advancesAndPersistsOnlyCompletedAllowlistedActions() {
		Clock clock = Clock.fixed(Instant.parse("2026-09-11T12:00:00Z"), ZoneOffset.UTC);
		SimulationBehaviorProperties behavior = new SimulationBehaviorProperties();
		SimulationProperties simulation = new SimulationProperties();
		SimulationWorldManifest manifest = new SimulationWorldManifestLoader(new ObjectMapper()).loadDefault();
		UUID actor = UUID.randomUUID();
		SimulationEngagementTarget target = new SimulationEngagementTarget(
				"event-01", EngagementTargetType.EVENT, UUID.randomUUID(),
				"venue-01", UUID.randomUUID(), SimulationWorldManifest.Scene.ISTANBUL_ALTERNATIVE_ROCK);
		SimulationEngagementPlan plan = new SimulationEngagementPlan(
				List.of(
						new SimulationEngagementPlan.LikeAction("like-1", "actor", actor, target),
						new SimulationEngagementPlan.LikeAction("like-2", "actor", actor, target)),
				List.of());
		SimulationBehaviorCheckpoint start = new SimulationBehaviorCheckpoint(
				SimulationBehaviorCheckpoint.CURRENT_SCHEMA_VERSION,
				manifest.schemaVersion(), manifest.worldId(), manifest.seed(), "fingerprint",
				2, 0, 0, 0, 0, 0, clock.instant());
		when(checkpoints.initialize(manifest, plan, 0, 0)).thenReturn(start);
		when(seeder.seed(any(), any())).thenReturn(new SimulationEngagementSeeder.Result(1, 1, 0, 0, 0, 0));
		SimulationRunLedger ledger = new SimulationRunLedger(manifest.worldId(), manifest.seed(), clock);
		SimulationPopulationEngine engine = new SimulationPopulationEngine(
				guard, worldLease, simulation, behavior, checkpoints, seeder, reports, clock);

		engine.initialize(manifest, plan, ledger, 0, 0);
		assertThat(engine.runActions(2)).isEqualTo(2);

		ArgumentCaptor<SimulationBehaviorCheckpoint> captor = ArgumentCaptor.forClass(
				SimulationBehaviorCheckpoint.class);
		verify(checkpoints, times(2)).save(
				org.mockito.ArgumentMatchers.eq(manifest),
				org.mockito.ArgumentMatchers.eq(plan), captor.capture());
		assertThat(captor.getValue().nextLikeIndex()).isEqualTo(2);
		assertThat(captor.getValue().nextCommentIndex()).isZero();
		verify(seeder, times(2)).seed(any(), org.mockito.ArgumentMatchers.eq(ledger));
	}
}

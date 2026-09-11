package com.berkayb.soundconnect.tools.simulation.behavior;

import com.berkayb.soundconnect.tools.simulation.SimulationRuntimeGuard;
import com.berkayb.soundconnect.tools.simulation.SimulationProperties;
import com.berkayb.soundconnect.tools.simulation.report.SimulationRunLedger;
import com.berkayb.soundconnect.tools.simulation.report.SimulationRunReportWriter;
import com.berkayb.soundconnect.tools.simulation.runtime.SimulationWorldLease;
import com.berkayb.soundconnect.tools.simulation.seed.engagement.SimulationEngagementPlan;
import com.berkayb.soundconnect.tools.simulation.seed.engagement.SimulationEngagementSeeder;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Restart-safe deterministic population runtime.
 *
 * <p>The engine has a deliberately tiny allowlist: it may execute only prepared
 * like and comment commands through {@link SimulationEngagementSeeder}. It has
 * no SQL, arbitrary endpoint, reflection, script or LLM execution capability.</p>
 */
@Component
@Profile("local & simulation")
@ConditionalOnProperty(prefix = "app.simulation", name = "enabled", havingValue = "true")
@Slf4j
public final class SimulationPopulationEngine {

	private final SimulationRuntimeGuard runtimeGuard;
	private final SimulationWorldLease worldLease;
	private final SimulationProperties simulationProperties;
	private final SimulationBehaviorProperties properties;
	private final SimulationBehaviorCheckpointStore checkpoints;
	private final SimulationEngagementSeeder engagementSeeder;
	private final SimulationRunReportWriter reports;
	private final Clock clock;
	private final ReentrantLock execution = new ReentrantLock();
	private volatile Session session;

	public SimulationPopulationEngine(
			SimulationRuntimeGuard runtimeGuard,
			SimulationWorldLease worldLease,
			SimulationProperties simulationProperties,
			SimulationBehaviorProperties properties,
			SimulationBehaviorCheckpointStore checkpoints,
			SimulationEngagementSeeder engagementSeeder,
			SimulationRunReportWriter reports,
			Clock clock
	) {
		this.runtimeGuard = Objects.requireNonNull(runtimeGuard, "runtimeGuard");
		this.worldLease = Objects.requireNonNull(worldLease, "worldLease");
		this.simulationProperties = Objects.requireNonNull(simulationProperties, "simulationProperties");
		this.properties = Objects.requireNonNull(properties, "properties");
		this.checkpoints = Objects.requireNonNull(checkpoints, "checkpoints");
		this.engagementSeeder = Objects.requireNonNull(engagementSeeder, "engagementSeeder");
		this.reports = Objects.requireNonNull(reports, "reports");
		this.clock = Objects.requireNonNull(clock, "clock");
	}

	public void initialize(
			SimulationWorldManifest manifest,
			SimulationEngagementPlan fullPlan,
			SimulationRunLedger ledger,
			int baselineLikes,
			int baselineComments
	) {
		runtimeGuard.assertRuntimeAllowed();
		worldLease.assertHeld();
		Objects.requireNonNull(manifest, "manifest");
		Objects.requireNonNull(fullPlan, "fullPlan");
		Objects.requireNonNull(ledger, "ledger");
		if (baselineLikes > fullPlan.likes().size() || baselineComments > fullPlan.comments().size()) {
			throw new IllegalStateException("Behavior plan is smaller than the seeded baseline");
		}
		SimulationBehaviorCheckpoint checkpoint = checkpoints.initialize(
				manifest, fullPlan, baselineLikes, baselineComments);
		validateCursor(checkpoint, fullPlan);
		this.session = new Session(manifest, fullPlan, ledger, checkpoint);
		ledger.succeeded("behavior", "INITIALIZE", null, null,
				"nextLike=" + checkpoint.nextLikeIndex()
						+ ", nextComment=" + checkpoint.nextCommentIndex());
	}

	public int fastForwardConfiguredWindow() {
		int requested = Math.multiplyExact(
				properties.getFastForwardDays(), properties.getActionsPerFastForwardDay());
		return runActions(requested, true);
	}

	@Scheduled(
			fixedDelayString = "${app.simulation.behavior.tick-delay-ms:60000}",
			initialDelayString = "${app.simulation.behavior.initial-delay-ms:30000}"
	)
	public void scheduledTick() {
		if (!properties.isEnabled() || session == null) return;
		try {
			worldLease.assertHeld();
		} catch (RuntimeException leaseFailure) {
			Session abandoned = session;
			session = null;
			if (abandoned != null) {
				abandoned.ledger().failed("behavior", "LEASE_LOST", null, null, leaseFailure);
				writeReport(abandoned);
			}
			log.error("Simulation population engine stopped because its world lease was lost");
			return;
		}
		try {
			runActions(properties.getActionsPerTick());
		} catch (RuntimeException failure) {
			Session active = session;
			if (active != null) {
				active.ledger().failed("behavior", "TICK_ABORT", null, null, failure);
				writeReport(active);
			}
			log.warn("Simulation population tick failed; it will retry safely. exceptionType={}",
					failure.getClass().getSimpleName());
		}
	}

	int runActions(int requested) {
		return runActions(requested, false);
	}

	private int runActions(int requested, boolean waitForExclusiveExecution) {
		if (requested < 0) throw new IllegalArgumentException("requested action count cannot be negative");
		if (requested == 0) return 0;
		if (waitForExclusiveExecution) execution.lock();
		else if (!execution.tryLock()) return 0;
		try {
			Session active = session;
			if (active == null) return 0;
			runtimeGuard.assertRuntimeAllowed();
			worldLease.assertHeld();
			int executed = 0;
			for (; executed < requested; executed++) {
				SimulationBehaviorCheckpoint cursor = active.checkpoint();
				boolean likeAvailable = cursor.nextLikeIndex() < active.plan().likes().size();
				boolean commentAvailable = cursor.nextCommentIndex() < active.plan().comments().size();
				if (!likeAvailable && !commentAvailable) break;

				boolean executeLike = likeAvailable && (!commentAvailable
						|| (cursor.nextLikeIndex() + cursor.nextCommentIndex()) % 3 != 2);
				if (executeLike) {
					var action = active.plan().likes().get(cursor.nextLikeIndex());
					engagementSeeder.seed(new SimulationEngagementPlan(List.of(action), List.of()), active.ledger());
					active.checkpoint(cursor.advanceLike(clock.instant()));
				} else {
					var action = active.plan().comments().get(cursor.nextCommentIndex());
					engagementSeeder.seed(new SimulationEngagementPlan(List.of(), List.of(action)), active.ledger());
					active.checkpoint(cursor.advanceComment(clock.instant()));
				}
				checkpoints.save(active.manifest(), active.plan(), active.checkpoint());
			}
			if (executed > 0) writeReport(active);
			return executed;
		} finally {
			execution.unlock();
		}
	}

	private static void validateCursor(
			SimulationBehaviorCheckpoint checkpoint,
			SimulationEngagementPlan plan
	) {
		if (checkpoint.nextLikeIndex() > plan.likes().size()
				|| checkpoint.nextCommentIndex() > plan.comments().size()) {
			throw new IllegalStateException("Behavior checkpoint exceeds the configured action plan");
		}
	}

	private void writeReport(Session active) {
		reports.write(simulationProperties.getReportDirectory(), active.ledger().snapshot());
	}

	private final class Session {
		private final SimulationWorldManifest manifest;
		private final SimulationEngagementPlan plan;
		private final SimulationRunLedger ledger;
		private SimulationBehaviorCheckpoint checkpoint;

		private Session(
				SimulationWorldManifest manifest,
				SimulationEngagementPlan plan,
				SimulationRunLedger ledger,
				SimulationBehaviorCheckpoint checkpoint
		) {
			this.manifest = manifest;
			this.plan = plan;
			this.ledger = ledger;
			this.checkpoint = checkpoint;
		}

		private SimulationWorldManifest manifest() { return manifest; }
		private SimulationEngagementPlan plan() { return plan; }
		private SimulationRunLedger ledger() { return ledger; }
		private SimulationBehaviorCheckpoint checkpoint() { return checkpoint; }
		private void checkpoint(SimulationBehaviorCheckpoint value) { checkpoint = value; }
	}
}

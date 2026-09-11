package com.berkayb.soundconnect.tools.simulation.seed.content.opportunity;

import com.berkayb.soundconnect.tools.simulation.SimulationRuntimeGuard;
import com.berkayb.soundconnect.tools.simulation.report.SimulationRunLedger;
import com.berkayb.soundconnect.tools.simulation.seed.support.SimulationPreflightResult;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Coordinates the deterministic Collab and Event opportunity slice. */
@Component
@Profile("local & simulation")
@ConditionalOnProperty(prefix = "app.simulation", name = "enabled", havingValue = "true")
public final class SimulationOpportunitySeeder {

	private final SimulationRuntimeGuard runtimeGuard;
	private final SimulationOpportunityPlanner planner;
	private final SimulationCollabMaterializer collabMaterializer;
	private final SimulationEventMaterializer eventMaterializer;

	public SimulationOpportunitySeeder(
			SimulationRuntimeGuard runtimeGuard,
			SimulationOpportunityPlanner planner,
			SimulationCollabMaterializer collabMaterializer,
			SimulationEventMaterializer eventMaterializer
	) {
		this.runtimeGuard = Objects.requireNonNull(runtimeGuard, "runtimeGuard");
		this.planner = Objects.requireNonNull(planner, "planner");
		this.collabMaterializer = Objects.requireNonNull(collabMaterializer, "collabMaterializer");
		this.eventMaterializer = Objects.requireNonNull(eventMaterializer, "eventMaterializer");
	}

	/**
	 * Materializes the opportunity plan for one persisted scheduling window.
	 *
	 * @param profileIds account key to domain profile id; used for musician and studio actors
	 * @param venueIds account key to {@code Venue} aggregate id, not {@code VenueProfile} id
	 * @param bandIds band manifest key to {@code Band} id
	 * @param allowExisting true only for RESUME, where every existing payload is verified exactly
	 */
	public SimulationOpportunitySeedResult seed(
			SimulationWorldManifest manifest,
			Map<String, UUID> accountUserIds,
			Map<String, UUID> profileIds,
			Map<String, UUID> venueIds,
			Map<String, UUID> bandIds,
			SimulationPreflightResult preflight,
			SimulationOpportunityWindow window,
			boolean allowExisting,
			SimulationRunLedger ledger
	) {
		runtimeGuard.assertRuntimeAllowed();
		Objects.requireNonNull(manifest, "manifest");
		Objects.requireNonNull(window, "window");
		SimulationOpportunityPlan plan = planner.plan(manifest, window);
		SimulationOpportunitySeedResult.ModuleResult collabs = collabMaterializer.materialize(
				plan.collabs(), accountUserIds, profileIds, venueIds, bandIds,
				preflight, allowExisting, ledger);
		SimulationOpportunitySeedResult.ModuleResult events = eventMaterializer.materialize(
				plan.events(), manifest, accountUserIds, profileIds, venueIds, bandIds,
				allowExisting, ledger);
		return new SimulationOpportunitySeedResult(collabs, events);
	}
}

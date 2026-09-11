package com.berkayb.soundconnect.tools.simulation.seed.follow;

import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifestLoader;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.HashSet;

import static org.assertj.core.api.Assertions.assertThat;

class SimulationFollowPlannerTest {

	private final SimulationWorldManifest manifest = new SimulationWorldManifestLoader(new ObjectMapper())
			.loadDefault();

	@Test
	void producesExactUniqueDeterministicBaseline() {
		SimulationFollowPlanner planner = new SimulationFollowPlanner();

		SimulationFollowPlan first = planner.plan(manifest);
		SimulationFollowPlan second = planner.plan(manifest);

		assertThat(first).isEqualTo(second);
		assertThat(first.edges()).hasSize(240);
		assertThat(new HashSet<>(first.edges())).hasSize(240);
		assertThat(first.edges()).noneMatch(edge ->
				edge.followerAccountKey().equals(edge.followedAccountKey()));
	}

	@Test
	void keepsColdGhostPendingAndUnverifiedControlsOutOfTheGraph() {
		SimulationFollowPlan plan = new SimulationFollowPlanner().plan(manifest);
		var cold = manifest.accounts().stream()
				.filter(account -> account.observer() == SimulationWorldManifest.ObserverProfile.COLD_START)
				.findFirst().orElseThrow().key();
		var forbidden = manifest.accounts().stream()
				.filter(account -> account.emailVerification() == SimulationWorldManifest.EmailVerificationState.UNVERIFIED
						|| account.listenerVisibility() == SimulationWorldManifest.ListenerVisibilityState.GHOST
						|| account.listenerVisibility() == SimulationWorldManifest.ListenerVisibilityState.VISIBILITY_PENDING
						|| account.institutionState() == SimulationWorldManifest.InstitutionState.PENDING
						|| account.institutionState() == SimulationWorldManifest.InstitutionState.REJECTED)
				.map(SimulationWorldManifest.Account::key)
				.collect(java.util.stream.Collectors.toSet());
		forbidden.add(cold);

		assertThat(plan.edges()).noneMatch(edge -> forbidden.contains(edge.followerAccountKey())
				|| forbidden.contains(edge.followedAccountKey()));
	}

	@Test
	void heavilyFavorsWithinSceneRelationships() {
		SimulationFollowPlan plan = new SimulationFollowPlanner().plan(manifest);
		var scenes = manifest.accounts().stream().collect(java.util.stream.Collectors.toMap(
				SimulationWorldManifest.Account::key, SimulationWorldManifest.Account::scene));
		long localEdges = plan.edges().stream().filter(edge ->
				scenes.get(edge.followerAccountKey()) == scenes.get(edge.followedAccountKey())).count();

		assertThat(localEdges).isBetween(180L, 200L);
		assertThat(plan.edges().size() - localEdges).isGreaterThanOrEqualTo(40L);
	}
}

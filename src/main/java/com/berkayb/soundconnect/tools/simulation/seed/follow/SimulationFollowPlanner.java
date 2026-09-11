package com.berkayb.soundconnect.tools.simulation.seed.follow;

import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest.Account;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest.AccountRole;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest.EmailVerificationState;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest.InstitutionState;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest.ListenerVisibilityState;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest.ObserverProfile;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Builds the same scene-weighted social graph for the same manifest and seed. */
public final class SimulationFollowPlanner {

	private static final long SAME_SCENE_WEIGHT = 1_000_000L;
	private static final long OBSERVER_CONNECTION_WEIGHT = 180_000L;
	private static final long COMPLEMENTARY_ROLE_WEIGHT = 80_000L;
	private static final long HASH_RANGE = 50_000L;
	private static final int LOCAL_EDGE_PERCENT = 80;

	public SimulationFollowPlan plan(SimulationWorldManifest manifest) {
		if (manifest == null) throw new IllegalArgumentException("manifest is required");
		int target = manifest.contentTargets().follows();
		List<Node> nodes = manifest.accounts().stream()
				.filter(this::isEligible)
				.map(account -> new Node(
						account.key(), account.scene(), account.role(), account.observer()))
				.sorted(Comparator.comparing(Node::key))
				.toList();
		List<Candidate> candidates = candidates(nodes, manifest.seed());
		if (target > candidates.size()) {
			throw new IllegalStateException("Follow target exceeds eligible ordered account pairs");
		}

		LinkedHashSet<SimulationFollowPlan.Edge> selected = new LinkedHashSet<>();
		// Guarantee that every non-cold eligible identity participates on both sides;
		// the score then fills the remaining graph with mostly local-scene ties.
		for (Node node : nodes) {
			candidates.stream()
					.filter(candidate -> candidate.from().equals(node.key()))
					.findFirst()
					.ifPresent(candidate -> selected.add(candidate.edge()));
			candidates.stream()
					.filter(candidate -> candidate.to().equals(node.key()))
					.findFirst()
					.ifPresent(candidate -> selected.add(candidate.edge()));
		}
		int localTarget = Math.toIntExact((long) target * LOCAL_EDGE_PERCENT / 100L);
		int localSelected = (int) selected.stream()
				.filter(edge -> sameScene(edge, nodes)).count();
		for (Candidate candidate : candidates) {
			if (localSelected >= localTarget) break;
			if (candidate.sameScene() && selected.add(candidate.edge())) localSelected++;
		}
		for (Candidate candidate : candidates) {
			if (selected.size() == target) break;
			if (!candidate.sameScene()) selected.add(candidate.edge());
		}
		// Tiny custom manifests may not have enough cross-scene pairs. The canonical
		// world does; this final fill keeps the planner generally well-defined.
		for (Candidate candidate : candidates) {
			if (selected.size() == target) break;
			selected.add(candidate.edge());
		}
		if (selected.size() != target) {
			throw new IllegalStateException("Unable to produce the requested follow count");
		}
		return new SimulationFollowPlan(new ArrayList<>(selected));
	}

	private List<Candidate> candidates(List<Node> nodes, long seed) {
		List<Candidate> result = new ArrayList<>();
		Set<SimulationFollowPlan.Edge> unique = new HashSet<>();
		for (Node from : nodes) {
			for (Node to : nodes) {
				if (from.key().equals(to.key())) continue;
				SimulationFollowPlan.Edge edge = new SimulationFollowPlan.Edge(from.key(), to.key());
				if (!unique.add(edge)) throw new IllegalStateException("Duplicate follow candidate");
				result.add(new Candidate(edge, score(seed, from, to), from.scene() == to.scene()));
			}
		}
		result.sort(Comparator.comparingLong(Candidate::score).reversed()
				.thenComparing(Candidate::from)
				.thenComparing(Candidate::to));
		return result;
	}

	private static boolean sameScene(SimulationFollowPlan.Edge edge, List<Node> nodes) {
		SimulationWorldManifest.Scene from = nodes.stream()
				.filter(node -> node.key().equals(edge.followerAccountKey()))
				.findFirst().orElseThrow().scene();
		SimulationWorldManifest.Scene to = nodes.stream()
				.filter(node -> node.key().equals(edge.followedAccountKey()))
				.findFirst().orElseThrow().scene();
		return from == to;
	}

	private long score(long seed, Node from, Node to) {
		long score = from.scene() == to.scene() ? SAME_SCENE_WEIGHT : 0L;
		if (from.observer() == ObserverProfile.COMPLETE || to.observer() == ObserverProfile.COMPLETE) {
			score += OBSERVER_CONNECTION_WEIGHT;
		}
		if (isComplementary(from.role(), to.role())) score += COMPLEMENTARY_ROLE_WEIGHT;
		return score + Math.floorMod(stableHash(seed + ":" + from.key() + ":" + to.key()), HASH_RANGE);
	}

	private boolean isEligible(Account account) {
		if (account.emailVerification() != EmailVerificationState.VERIFIED
				|| account.observer() == ObserverProfile.COLD_START) {
			return false;
		}
		return switch (account.role()) {
			case MUSICIAN -> true;
			case LISTENER -> account.listenerVisibility() == ListenerVisibilityState.STANDARD;
			case VENUE, STUDIO -> account.institutionState() == InstitutionState.APPROVED;
		};
	}

	private static boolean isComplementary(AccountRole from, AccountRole to) {
		return switch (from) {
			case MUSICIAN -> to == AccountRole.MUSICIAN || to == AccountRole.VENUE
					|| to == AccountRole.STUDIO || to == AccountRole.LISTENER;
			case LISTENER -> to == AccountRole.MUSICIAN || to == AccountRole.VENUE;
			case VENUE -> to == AccountRole.MUSICIAN || to == AccountRole.STUDIO;
			case STUDIO -> to == AccountRole.MUSICIAN || to == AccountRole.VENUE;
		};
	}

	private static long stableHash(String value) {
		try {
			byte[] bytes = MessageDigest.getInstance("SHA-256")
					.digest(value.getBytes(StandardCharsets.UTF_8));
			long result = 0L;
			for (int index = 0; index < Long.BYTES; index++) {
				result = (result << 8) | (bytes[index] & 0xffL);
			}
			return result;
		} catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException("SHA-256 is unavailable", impossible);
		}
	}

	private record Node(
			String key,
			SimulationWorldManifest.Scene scene,
			AccountRole role,
			ObserverProfile observer
	) {
	}

	private record Candidate(SimulationFollowPlan.Edge edge, long score, boolean sameScene) {
		String from() {
			return edge.followerAccountKey();
		}

		String to() {
			return edge.followedAccountKey();
		}
	}
}

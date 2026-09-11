package com.berkayb.soundconnect.tools.simulation.seed.follow;

import java.util.List;

/** Stable logical edges; database identifiers are deliberately resolved only during execution. */
public record SimulationFollowPlan(List<Edge> edges) {
	public SimulationFollowPlan {
		edges = edges == null ? List.of() : List.copyOf(edges);
	}

	public record Edge(String followerAccountKey, String followedAccountKey) {
		public Edge {
			if (followerAccountKey == null || followerAccountKey.isBlank()) {
				throw new IllegalArgumentException("followerAccountKey is required");
			}
			if (followedAccountKey == null || followedAccountKey.isBlank()) {
				throw new IllegalArgumentException("followedAccountKey is required");
			}
			if (followerAccountKey.equals(followedAccountKey)) {
				throw new IllegalArgumentException("self follow is not allowed");
			}
		}
	}
}

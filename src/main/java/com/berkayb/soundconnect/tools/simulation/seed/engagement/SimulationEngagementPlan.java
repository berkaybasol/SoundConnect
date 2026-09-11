package com.berkayb.soundconnect.tools.simulation.seed.engagement;

import com.berkayb.soundconnect.tools.simulation.seed.content.social.SimulationEngagementTarget;

import java.util.List;
import java.util.UUID;

/** Deterministic, bounded actions executed through the production engagement services. */
public record SimulationEngagementPlan(
		List<LikeAction> likes,
		List<CommentAction> comments
) {
	public SimulationEngagementPlan {
		likes = likes == null ? List.of() : List.copyOf(likes);
		comments = comments == null ? List.of() : List.copyOf(comments);
	}

	public record LikeAction(
			String logicalKey,
			String actorAccountKey,
			UUID actorUserId,
			SimulationEngagementTarget target
	) {
	}

	public record CommentAction(
			String logicalKey,
			String actorAccountKey,
			UUID actorUserId,
			SimulationEngagementTarget target,
			String text
	) {
	}
}

package com.berkayb.soundconnect.tools.simulation.seed.follow;

import com.berkayb.soundconnect.tools.simulation.SimulationRuntimeGuard;
import com.berkayb.soundconnect.tools.simulation.report.SimulationRunLedger;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Applies a logical follow plan through the production follow service. */
@Component
@Profile("local & simulation")
@ConditionalOnProperty(prefix = "app.simulation", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class SimulationFollowSeeder {

	private final SimulationRuntimeGuard runtimeGuard;
	private final SimulationFollowEdgeExecutor edgeExecutor;

	public Result seed(
			SimulationFollowPlan plan,
			Map<String, UUID> accountUserIds,
			SimulationRunLedger ledger
	) {
		runtimeGuard.assertRuntimeAllowed();
		Objects.requireNonNull(plan, "plan");
		Objects.requireNonNull(accountUserIds, "accountUserIds");
		Objects.requireNonNull(ledger, "ledger");
		int created = 0;
		int existing = 0;
		for (SimulationFollowPlan.Edge edge : plan.edges()) {
			UUID followerId = requireId(accountUserIds, edge.followerAccountKey());
			UUID followedId = requireId(accountUserIds, edge.followedAccountKey());
			try {
				SimulationFollowEdgeExecutor.Outcome outcome = edgeExecutor.ensure(followerId, followedId);
				if (outcome == SimulationFollowEdgeExecutor.Outcome.CREATED) {
					created++;
					ledger.succeeded("follow-graph", "FOLLOW", edge.followerAccountKey(),
							edge.followedAccountKey(), "created");
				} else {
					existing++;
					ledger.skipped("follow-graph", "FOLLOW", edge.followerAccountKey(),
							edge.followedAccountKey(), "already present");
				}
			} catch (RuntimeException failure) {
				ledger.failed("follow-graph", "FOLLOW", edge.followerAccountKey(),
						edge.followedAccountKey(), failure);
				throw failure;
			}
		}
		return new Result(plan.edges().size(), created, existing);
	}

	private static UUID requireId(Map<String, UUID> accountUserIds, String key) {
		UUID value = accountUserIds.get(key);
		if (value == null) throw new IllegalStateException("No registered account for manifest key: " + key);
		return value;
	}

	public record Result(int planned, int created, int alreadyPresent) {
	}
}

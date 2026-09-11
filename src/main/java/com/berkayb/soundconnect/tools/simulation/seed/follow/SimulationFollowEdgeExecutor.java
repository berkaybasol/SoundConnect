package com.berkayb.soundconnect.tools.simulation.seed.follow;

import com.berkayb.soundconnect.modules.follow.service.FollowService;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.berkayb.soundconnect.tools.simulation.SimulationRuntimeGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Executes one idempotent edge in its own transaction so a failure is precisely attributable. */
@Component
@Profile("local & simulation")
@ConditionalOnProperty(prefix = "app.simulation", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class SimulationFollowEdgeExecutor {

	private final SimulationRuntimeGuard runtimeGuard;
	private final UserRepository userRepository;
	private final FollowService followService;

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public Outcome ensure(UUID followerId, UUID followedId) {
		runtimeGuard.assertRuntimeAllowed();
		if (followerId == null || followedId == null) {
			throw new IllegalArgumentException("Both follow user identifiers are required");
		}
		if (followerId.equals(followedId)) {
			throw new IllegalArgumentException("Simulation cannot create a self follow");
		}
		User follower = userRepository.findById(followerId)
				.orElseThrow(() -> new IllegalStateException("Simulation follower does not exist"));
		User followed = userRepository.findById(followedId)
				.orElseThrow(() -> new IllegalStateException("Simulation follow target does not exist"));
		if (followService.isFollowing(follower, followed)) return Outcome.ALREADY_PRESENT;
		followService.follow(follower, followed);
		return Outcome.CREATED;
	}

	public enum Outcome {
		CREATED,
		ALREADY_PRESENT
	}
}

package com.berkayb.soundconnect.tools.simulation.seed.follow;

import com.berkayb.soundconnect.modules.follow.service.FollowService;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.berkayb.soundconnect.tools.simulation.SimulationRuntimeGuard;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SimulationFollowEdgeExecutorTest {

	private final SimulationRuntimeGuard guard = mock(SimulationRuntimeGuard.class);
	private final UserRepository users = mock(UserRepository.class);
	private final FollowService follows = mock(FollowService.class);
	private final SimulationFollowEdgeExecutor executor = new SimulationFollowEdgeExecutor(guard, users, follows);

	@Test
	void usesProductionFollowServiceForANewEdge() {
		User follower = User.builder().id(UUID.randomUUID()).build();
		User followed = User.builder().id(UUID.randomUUID()).build();
		when(users.findById(follower.getId())).thenReturn(Optional.of(follower));
		when(users.findById(followed.getId())).thenReturn(Optional.of(followed));
		when(follows.isFollowing(follower, followed)).thenReturn(false);

		assertThat(executor.ensure(follower.getId(), followed.getId()))
				.isEqualTo(SimulationFollowEdgeExecutor.Outcome.CREATED);

		verify(guard).assertRuntimeAllowed();
		verify(follows).follow(follower, followed);
	}

	@Test
	void makesResumeIdempotent() {
		User follower = User.builder().id(UUID.randomUUID()).build();
		User followed = User.builder().id(UUID.randomUUID()).build();
		when(users.findById(follower.getId())).thenReturn(Optional.of(follower));
		when(users.findById(followed.getId())).thenReturn(Optional.of(followed));
		when(follows.isFollowing(follower, followed)).thenReturn(true);

		assertThat(executor.ensure(follower.getId(), followed.getId()))
				.isEqualTo(SimulationFollowEdgeExecutor.Outcome.ALREADY_PRESENT);
		verify(follows, never()).follow(follower, followed);
	}
}

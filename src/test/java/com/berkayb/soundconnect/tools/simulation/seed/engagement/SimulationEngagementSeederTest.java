package com.berkayb.soundconnect.tools.simulation.seed.engagement;

import com.berkayb.soundconnect.modules.comment.dto.response.CommentResponseDto;
import com.berkayb.soundconnect.modules.comment.service.CommentService;
import com.berkayb.soundconnect.modules.engagement.enums.EngagementTargetType;
import com.berkayb.soundconnect.modules.like.service.LikeService;
import com.berkayb.soundconnect.tools.simulation.SimulationRuntimeGuard;
import com.berkayb.soundconnect.tools.simulation.report.SimulationRunLedger;
import com.berkayb.soundconnect.tools.simulation.seed.content.social.SimulationEngagementTarget;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest.Scene;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SimulationEngagementSeederTest {

	@Mock private SimulationRuntimeGuard runtimeGuard;
	@Mock private LikeService likes;
	@Mock private CommentService comments;
	@Mock private SimulationCommentPresence presence;

	private SimulationEngagementSeeder seeder;
	private SimulationEngagementPlan plan;
	private SimulationRunLedger ledger;

	@BeforeEach
	void setUp() {
		seeder = new SimulationEngagementSeeder(runtimeGuard, likes, comments, presence);
		UUID actor = UUID.randomUUID();
		SimulationEngagementTarget target = new SimulationEngagementTarget(
				"event-01", EngagementTargetType.EVENT, UUID.randomUUID(),
				"venue-01", UUID.randomUUID(), Scene.ISTANBUL_ALTERNATIVE_ROCK);
		plan = new SimulationEngagementPlan(
				List.of(new SimulationEngagementPlan.LikeAction("like-001", "musician-01", actor, target)),
				List.of(new SimulationEngagementPlan.CommentAction(
						"comment-001", "musician-01", actor, target, "Güzel bir program olmuş.")));
		ledger = new SimulationRunLedger("test-world", 42L, Clock.systemUTC());
	}

	@Test
	void createsMissingActionsThroughProductionServices() {
		when(likes.isLiked(any(), any(), any())).thenReturn(false);
		when(presence.exists(any(), any(), any(), any())).thenReturn(false);
		CommentResponseDto response = org.mockito.Mockito.mock(CommentResponseDto.class);
		when(response.id()).thenReturn(UUID.randomUUID());
		when(comments.createComment(any(), any(), any(), any())).thenReturn(response);

		SimulationEngagementSeeder.Result result = seeder.seed(plan, ledger);

		assertThat(result.createdLikes()).isOne();
		assertThat(result.createdComments()).isOne();
		verify(likes).like(any(), any(), any());
		verify(comments).createComment(any(), any(), any(), any());
	}

	@Test
	void resumeSkipsAlreadyMaterializedActions() {
		when(likes.isLiked(any(), any(), any())).thenReturn(true);
		when(presence.exists(any(), any(), any(), any())).thenReturn(true);

		SimulationEngagementSeeder.Result result = seeder.seed(plan, ledger);

		assertThat(result.existingLikes()).isOne();
		assertThat(result.existingComments()).isOne();
		verify(likes, never()).like(any(), any(), any());
		verify(comments, never()).createComment(any(), any(), any(), any());
	}
}

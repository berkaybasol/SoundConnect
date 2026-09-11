package com.berkayb.soundconnect.tools.simulation.seed.engagement;

import com.berkayb.soundconnect.modules.comment.dto.request.CommentCreateRequestDto;
import com.berkayb.soundconnect.modules.comment.service.CommentService;
import com.berkayb.soundconnect.modules.like.service.LikeService;
import com.berkayb.soundconnect.tools.simulation.SimulationRuntimeGuard;
import com.berkayb.soundconnect.tools.simulation.report.SimulationRunLedger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Applies the bounded engagement plan through the same services used by authenticated clients. */
@Component
@Profile("local & simulation")
@ConditionalOnProperty(prefix = "app.simulation", name = "enabled", havingValue = "true")
public final class SimulationEngagementSeeder {

	private final SimulationRuntimeGuard runtimeGuard;
	private final LikeService likes;
	private final CommentService comments;
	private final SimulationCommentPresence commentPresence;

	public SimulationEngagementSeeder(
			SimulationRuntimeGuard runtimeGuard,
			LikeService likes,
			CommentService comments,
			SimulationCommentPresence commentPresence
	) {
		this.runtimeGuard = Objects.requireNonNull(runtimeGuard, "runtimeGuard");
		this.likes = Objects.requireNonNull(likes, "likes");
		this.comments = Objects.requireNonNull(comments, "comments");
		this.commentPresence = Objects.requireNonNull(commentPresence, "commentPresence");
	}

	public Result seed(SimulationEngagementPlan plan, SimulationRunLedger ledger) {
		runtimeGuard.assertRuntimeAllowed();
		Objects.requireNonNull(plan, "plan");
		Objects.requireNonNull(ledger, "ledger");
		int createdLikes = 0;
		int existingLikes = 0;
		int createdComments = 0;
		int existingComments = 0;

		for (var action : plan.likes()) {
			try {
				boolean existing = likes.isLiked(action.actorUserId(),
						action.target().targetType(), action.target().targetId());
				if (existing) {
					existingLikes++;
					ledger.skipped("engagement", "LIKE", action.actorAccountKey(),
							action.target().logicalKey(), "already present");
				} else {
					likes.like(action.actorUserId(), action.target().targetType(), action.target().targetId());
					createdLikes++;
					ledger.succeeded("engagement", "LIKE", action.actorAccountKey(),
							action.target().logicalKey(), "created");
				}
			} catch (RuntimeException failure) {
				ledger.failed("engagement", "LIKE", action.actorAccountKey(),
						action.target().logicalKey(), failure);
				throw failure;
			}
		}

		for (var action : plan.comments()) {
			try {
				boolean existing = commentPresence.exists(action.actorUserId(),
						action.target().targetType(), action.target().targetId(), action.text());
				if (existing) {
					existingComments++;
					ledger.skipped("engagement", "COMMENT", action.actorAccountKey(),
							action.target().logicalKey(), "already present");
				} else {
					var response = comments.createComment(action.actorUserId(),
							action.target().targetType(), action.target().targetId(),
							new CommentCreateRequestDto(action.text(), null));
					if (response == null || response.id() == null) {
						throw new IllegalStateException("Comment service returned no comment id");
					}
					createdComments++;
					ledger.succeeded("engagement", "COMMENT", action.actorAccountKey(),
							action.target().logicalKey(), "created");
				}
			} catch (RuntimeException failure) {
				ledger.failed("engagement", "COMMENT", action.actorAccountKey(),
						action.target().logicalKey(), failure);
				throw failure;
			}
		}
		return new Result(plan.likes().size(), createdLikes, existingLikes,
				plan.comments().size(), createdComments, existingComments);
	}

	public record Result(
			int plannedLikes,
			int createdLikes,
			int existingLikes,
			int plannedComments,
			int createdComments,
			int existingComments
	) {
	}
}

package com.berkayb.soundconnect.modules.comment.mapper;

import com.berkayb.soundconnect.modules.comment.dto.response.CommentReplyResponseDto;
import com.berkayb.soundconnect.modules.comment.dto.response.CommentResponseDto;
import com.berkayb.soundconnect.modules.comment.dto.support.UserSummaryDto;
import com.berkayb.soundconnect.modules.comment.entity.Comment;
import com.berkayb.soundconnect.modules.comment.support.CommentAuthorBatchResolver;
import com.berkayb.soundconnect.modules.profile.shared.identity.GhostListenerIdentity;
import com.berkayb.soundconnect.modules.user.entity.User;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.springframework.beans.factory.annotation.Autowired;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Comment entity <-> DTO dönüşümleri için MapStruct mapper.
 */
@Mapper(componentModel = "spring")
public abstract class CommentMapper {
	
	@Autowired
	protected CommentAuthorBatchResolver authorResolver;
	
	protected UserSummaryDto toUserSummaryDto(User user) {
		return toUserSummaryDto(user, null);
	}

	protected UserSummaryDto toUserSummaryDto(User user, GhostListenerIdentity ghostIdentity) {
		if (user == null) {
			return null;
		}
		if (ghostIdentity != null) {
			return new UserSummaryDto(
					user.getId(),
					ghostIdentity.username(),
					ghostIdentity.profilePictureUrl(),
					ghostIdentity.visibilityMode()
			);
		}
		
		return authorResolver.resolve(java.util.Set.of(user.getId())).get(user.getId());
	}

	/** Production page mapping supplies a pre-resolved author: never hydrate a user/profile graph here. */
	public CommentResponseDto toResolvedComment(Comment comment, int replyCount, boolean anonymous, UserSummaryDto user) {
		return new CommentResponseDto(comment.getId(), anonymous ? anonymousUserSummaryDto() : user, anonymous,
				comment.getText(), comment.isDeleted(), comment.getParentComment() == null ? null : comment.getParentComment().getId(),
				replyCount, toApiInstant(comment.getCreatedAt()));
	}

	public CommentReplyResponseDto toResolvedReply(Comment comment, boolean anonymous, UserSummaryDto user) {
		return new CommentReplyResponseDto(comment.getId(), anonymous ? anonymousUserSummaryDto() : user, anonymous,
				comment.getText(), comment.isDeleted(), comment.getParentComment() == null ? null : comment.getParentComment().getId(),
				toApiInstant(comment.getCreatedAt()));
	}

	/** JpaAuditingConfig stores UTC wall-clock LocalDateTime; the wire contract must include its UTC offset. */
	protected Instant toApiInstant(LocalDateTime utcDateTime) {
		return utcDateTime == null ? null : utcDateTime.toInstant(ZoneOffset.UTC);
	}
	
	/**
	 * Root comment için Response DTO.
	 * maskAuthor true ise gerçek user bilgisi yerine anonim özet döner.
	 */
	public CommentResponseDto toCommentResponseDto(Comment comment, int replyCount, boolean maskAuthor) {
		return toCommentResponseDto(comment, replyCount, maskAuthor, null);
	}

	@Mapping(target = "user", expression = "java(maskAuthor ? anonymousUserSummaryDto() : toUserSummaryDto(comment.getUser(), ghostIdentity))")
	@Mapping(target = "anonymousAuthor", source = "maskAuthor")
	@Mapping(target = "parentCommentId",
			expression = "java(comment.getParentComment() != null ? comment.getParentComment().getId() : null)")
	@Mapping(target = "replyCount", source = "replyCount")
	@Mapping(target = "likeCount", constant = "0L")
	@Mapping(target = "likedByMe", constant = "false")
	@Mapping(target = "createdAt", expression = "java(toApiInstant(comment.getCreatedAt()))")
	public abstract CommentResponseDto toCommentResponseDto(
			Comment comment,
			int replyCount,
			boolean maskAuthor,
			GhostListenerIdentity ghostIdentity
	);
	
	/**
	 * Reply yorumlar için Response DTO.
	 * maskAuthor true ise gerçek user bilgisi yerine anonim özet döner.
	 */
	public CommentReplyResponseDto toCommentReplyResponseDto(Comment comment, boolean maskAuthor) {
		return toCommentReplyResponseDto(comment, maskAuthor, null);
	}

	@Mapping(target = "user", expression = "java(maskAuthor ? anonymousUserSummaryDto() : toUserSummaryDto(comment.getUser(), ghostIdentity))")
	@Mapping(target = "anonymousAuthor", source = "maskAuthor")
	@Mapping(target = "likeCount", constant = "0L")
	@Mapping(target = "likedByMe", constant = "false")
	@Mapping(target = "parentCommentId",
			expression = "java(comment.getParentComment() != null ? comment.getParentComment().getId() : null)")
	@Mapping(target = "createdAt", expression = "java(toApiInstant(comment.getCreatedAt()))")
	public abstract CommentReplyResponseDto toCommentReplyResponseDto(
			Comment comment,
			boolean maskAuthor,
			GhostListenerIdentity ghostIdentity
	);
	
	protected UserSummaryDto anonymousUserSummaryDto() {
		return new UserSummaryDto(
				null,
				"Anonymous Author",
				null
		);
	}
	
	protected String resolveAvatarUrl(User user) {
		UserSummaryDto summary = toUserSummaryDto(user);
		return summary == null ? null : summary.avatarUrl();
	}
}

package com.berkayb.soundconnect.modules.comment.dto.response;

import com.berkayb.soundconnect.modules.comment.dto.support.UserSummaryDto;

import java.time.LocalDateTime;
import java.util.UUID;

public record CommentResponseDto(
		UUID id,
		UserSummaryDto user,
		boolean anonymousAuthor,
		String text,
		boolean deleted,
		UUID parentCommentId,
		int replyCount,
		LocalDateTime createdAt
) {
}
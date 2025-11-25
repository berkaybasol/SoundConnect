package com.berkayb.soundconnect.modules.social.comment.dto.response;

import com.berkayb.soundconnect.modules.social.comment.dto.support.UserSummaryDto;

import java.time.LocalDateTime;
import java.util.UUID;

// Reply yorumlari icin dto
public record CommentReplyResponseDto(
		UUID id,
		UserSummaryDto user,
		String text,
		boolean deleted,
		UUID parentCommentId,
		LocalDateTime createdAt
) {
}
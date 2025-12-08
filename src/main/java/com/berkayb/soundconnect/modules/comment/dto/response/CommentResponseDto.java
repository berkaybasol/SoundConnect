package com.berkayb.soundconnect.modules.comment.dto.response;

import com.berkayb.soundconnect.modules.comment.dto.support.UserSummaryDto;

import java.time.LocalDateTime;
import java.util.UUID;

// tek bir yttorumun UI'ya gonen tam modeli. ReplyCount dahil ama repy'lerin kendisi bu dto'da yer almaz.
public record CommentResponseDto(
		UUID id,
		UserSummaryDto user,
		String text,
		boolean deleted,
		UUID parentCommentId,
		int replyCount,
		LocalDateTime createdAt
) {
}
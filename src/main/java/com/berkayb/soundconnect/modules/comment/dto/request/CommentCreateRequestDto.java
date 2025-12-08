package com.berkayb.soundconnect.modules.comment.dto.request;

import java.util.UUID;

public record CommentCreateRequestDto(
		String text,
		UUID parentCommentId
) {
}
package com.berkayb.soundconnect.modules.comment.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CommentCreateRequestDto(
		@NotBlank(message = "Yorum metni bos olamaz")
		@Size(max = 500, message = "Yorum metni en fazla 500 karakter olabilir")
		String text,
		
		UUID parentCommentId
) {
}
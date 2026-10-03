package com.berkayb.soundconnect.modules.message.dm.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record DMMessageRequestDto(
		@NotNull UUID conversationId,
		@NotNull UUID recipientId,
		@NotBlank @Size(max = MAX_CONTENT_LENGTH) String content,
		@Pattern(regexp = "text") String messageType,
		UUID clientMessageId
		) {
	public static final int MAX_CONTENT_LENGTH = 10_000;
	/** Compatibility for server-side callers; mobile retries should always supply a stable UUID. */
	public DMMessageRequestDto(UUID conversationId, UUID recipientId, String content, String messageType) {
		this(conversationId, recipientId, content, messageType, null);
	}
}

package com.berkayb.soundconnect.modules.notification.dto.response;

import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_EMPTY) // @JsonInclude(JsonInclude.Include.NON_EMPTY): Eger bir alan bossa JSON ciktisina ekleme
public record NotificationResponseDto(
		UUID id,
		UUID recipientId,
		NotificationType type,
		String title,
		String message,
		boolean read,
		Instant createdAt,
		Map<String, Object> payload
) {
}
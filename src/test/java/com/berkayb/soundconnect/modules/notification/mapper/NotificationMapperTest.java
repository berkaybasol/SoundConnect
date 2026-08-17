package com.berkayb.soundconnect.modules.notification.mapper;

import com.berkayb.soundconnect.modules.notification.dto.response.NotificationResponseDto;
import com.berkayb.soundconnect.modules.notification.entity.Notification;
import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationMapperTest {
	
	private final NotificationMapper mapper = Mappers.getMapper(NotificationMapper.class);
	
	@Test
	@DisplayName("title boş/null ise type.defaultTitle kullanılır; payload ve recipient aynen taşınır")
	void toDto_titleFallbackToDefault_whenNullOrBlank() {
		UUID userId = UUID.randomUUID();
		Instant occurredAt = Instant.parse("2025-01-02T03:04:05Z");
		
		Notification entity = Notification.builder()
		                                  .recipientId(userId)
		                                  .type(NotificationType.MEDIA_TRANSCODE_READY) // defaultTitle: "Medya hazır (izlenebilir)"
		                                  .title(null)                                  // fallback tetiklensin
		                                  .message("Video hazır, izlenebilir.")
		                                  .occurredAt(occurredAt)
		                                  .payload(Map.of("foo", "bar"))
		                                  .read(false)
		                                  .build();
		
		NotificationResponseDto dto = mapper.toDto(entity);
		
		assertThat(dto).isNotNull();
		assertThat(dto.recipientId()).isEqualTo(userId);
		assertThat(dto.type()).isEqualTo(NotificationType.MEDIA_TRANSCODE_READY);
		assertThat(dto.title()).isEqualTo(NotificationType.MEDIA_TRANSCODE_READY.getDefaultTitle()); // fallback
		assertThat(dto.message()).isEqualTo("Video hazır, izlenebilir.");
		assertThat(dto.payload()).containsEntry("foo", "bar");
		assertThat(dto.read()).isFalse();
		
		// Wire compatibility: producer event time is still exposed as createdAt.
		assertThat(dto.createdAt()).isEqualTo(occurredAt);
	}
	
	@Test
	@DisplayName("title dolu ise aynen kullanılır; defaultTitle'a düşmez")
	void toDto_respectsExplicitTitle_whenPresent() {
		Instant occurredAt = Instant.parse("2024-12-31T23:59:59Z");
		Notification entity = Notification.builder()
		                                  .recipientId(UUID.randomUUID())
		                                  .type(NotificationType.SOCIAL_NEW_FOLLOWER)
		                                  .title("Özel Başlık") // explicit title
		                                  .message("Yeni takipçin var.")
		                                  .occurredAt(occurredAt)
		                                  .payload(null)
		                                  .read(true)
		                                  .build();
		
		NotificationResponseDto dto = mapper.toDto(entity);
		
		assertThat(dto.title()).isEqualTo("Özel Başlık"); // fallback yok
		assertThat(dto.read()).isTrue();
		assertThat(dto.createdAt()).isEqualTo(occurredAt);
	}
	
	@Test
	@DisplayName("occurredAt null ise DTO.createdAt de null olmalı")
	void toDto_occurredAtNull_mapsToNullInstant() {
		Notification entity = Notification.builder()
		                                  .recipientId(UUID.randomUUID())
		                                  .type(NotificationType.MEDIA_UPLOAD_RECEVIED)
		                                  .title(null) // fallback devrede
		                                  .message(null)
		                                  .payload(null)
		                                  .read(false)
		                                  .build();
		
		NotificationResponseDto dto = mapper.toDto(entity);
		
		assertThat(dto.createdAt()).isNull();
		assertThat(dto.title()).isEqualTo(NotificationType.MEDIA_UPLOAD_RECEVIED.getDefaultTitle());
	}
}

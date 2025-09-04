package com.berkayb.soundconnect.modules.notification.mapper;

import com.berkayb.soundconnect.modules.notification.dto.response.NotificationResponseDto;
import com.berkayb.soundconnect.modules.notification.entity.Notification;
import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.Month;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationMapperTest {
	
	private final NotificationMapper mapper = Mappers.getMapper(NotificationMapper.class);
	
	@Test
	@DisplayName("title boş/null ise type.defaultTitle kullanılır; payload ve recipient aynen taşınır")
	void toDto_titleFallbackToDefault_whenNullOrBlank() {
		UUID userId = UUID.randomUUID();
		
		Notification entity = Notification.builder()
		                                  .recipientId(userId)
		                                  .type(NotificationType.MEDIA_TRANSCODE_READY) // defaultTitle: "Medya hazır (izlenebilir)"
		                                  .title(null)                                  // fallback tetiklensin
		                                  .message("Video hazır, izlenebilir.")
		                                  .payload(Map.of("foo", "bar"))
		                                  .read(false)
		                                  .build();
		
		// BaseEntity.createdAt alanını set et (mapper Instant'e çeviriyor)
		LocalDateTime created = LocalDateTime.of(2025, Month.JANUARY, 2, 3, 4, 5);
		ReflectionTestUtils.setField(entity, "createdAt", created);
		
		NotificationResponseDto dto = mapper.toDto(entity);
		
		assertThat(dto).isNotNull();
		assertThat(dto.recipientId()).isEqualTo(userId);
		assertThat(dto.type()).isEqualTo(NotificationType.MEDIA_TRANSCODE_READY);
		assertThat(dto.title()).isEqualTo(NotificationType.MEDIA_TRANSCODE_READY.getDefaultTitle()); // fallback
		assertThat(dto.message()).isEqualTo("Video hazır, izlenebilir.");
		assertThat(dto.payload()).containsEntry("foo", "bar");
		assertThat(dto.read()).isFalse();
		
		// createdAt → Instant(UTC)
		Instant expected = created.toInstant(ZoneOffset.UTC);
		assertThat(dto.createdAt()).isEqualTo(expected);
	}
	
	@Test
	@DisplayName("title dolu ise aynen kullanılır; defaultTitle'a düşmez")
	void toDto_respectsExplicitTitle_whenPresent() {
		Notification entity = Notification.builder()
		                                  .recipientId(UUID.randomUUID())
		                                  .type(NotificationType.SOCIAL_NEW_FOLLOWER)
		                                  .title("Özel Başlık") // explicit title
		                                  .message("Yeni takipçin var.")
		                                  .payload(null)
		                                  .read(true)
		                                  .build();
		
		LocalDateTime created = LocalDateTime.of(2024, Month.DECEMBER, 31, 23, 59, 59);
		ReflectionTestUtils.setField(entity, "createdAt", created);
		
		NotificationResponseDto dto = mapper.toDto(entity);
		
		assertThat(dto.title()).isEqualTo("Özel Başlık"); // fallback yok
		assertThat(dto.read()).isTrue();
		assertThat(dto.createdAt()).isEqualTo(created.toInstant(ZoneOffset.UTC));
	}
	
	@Test
	@DisplayName("createdAt null ise DTO.createdAt de null olmalı")
	void toDto_createdAtNull_mapsToNullInstant() {
		Notification entity = Notification.builder()
		                                  .recipientId(UUID.randomUUID())
		                                  .type(NotificationType.MEDIA_UPLOAD_RECEIVED)
		                                  .title(null) // fallback devrede
		                                  .message(null)
		                                  .payload(null)
		                                  .read(false)
		                                  .build();
		
		// createdAt set edilmedi (null)
		NotificationResponseDto dto = mapper.toDto(entity);
		
		assertThat(dto.createdAt()).isNull();
		assertThat(dto.title()).isEqualTo(NotificationType.MEDIA_UPLOAD_RECEIVED.getDefaultTitle());
	}
}
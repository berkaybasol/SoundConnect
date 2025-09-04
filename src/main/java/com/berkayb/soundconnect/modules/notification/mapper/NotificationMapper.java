package com.berkayb.soundconnect.modules.notification.mapper;

import com.berkayb.soundconnect.modules.notification.dto.response.NotificationResponseDto;
import com.berkayb.soundconnect.modules.notification.entity.Notification;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.NullValueCheckStrategy;
import org.mapstruct.ReportingPolicy;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Mapper(
		componentModel = "spring",
		unmappedTargetPolicy = ReportingPolicy.IGNORE,
		nullValueCheckStrategy = NullValueCheckStrategy.ALWAYS
)
public interface NotificationMapper {
	
	@Mapping(
			target = "title",
			expression = "java( (n.getTitle() == null || n.getTitle().isBlank()) ? n.getType().getDefaultTitle() : n.getTitle() )"
	)
	@Mapping(
			target = "createdAt",
			expression = "java(map(n.getCreatedAt()))"
	)
	NotificationResponseDto toDto(Notification n);
	
	List<NotificationResponseDto> toDtoList(List<Notification> notifications);
	
	// --- helper method ---
	default Instant map(LocalDateTime value) {
		return value != null ? value.toInstant(ZoneOffset.UTC) : null;
	}
}
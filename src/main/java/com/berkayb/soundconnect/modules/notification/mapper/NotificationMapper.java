package com.berkayb.soundconnect.modules.notification.mapper;

import com.berkayb.soundconnect.modules.notification.dto.response.NotificationResponseDto;
import com.berkayb.soundconnect.modules.notification.entity.Notification;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.NullValueCheckStrategy;
import org.mapstruct.ReportingPolicy;

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
			source = "occurredAt"
	)
	@Mapping(target = "payload", expression = "java(publicPayload(n))")
	NotificationResponseDto toDto(Notification n);

	default java.util.Map<String, Object> publicPayload(Notification notification) {
		var payload = notification.getPayload();
		if (payload == null || !payload.containsKey("invitingRepresentativeId")) return payload;
		var visible = new java.util.LinkedHashMap<>(payload);
		visible.remove("invitingRepresentativeId");
		return visible;
	}
	
	List<NotificationResponseDto> toDtoList(List<Notification> notifications);
}

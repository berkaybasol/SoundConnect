package com.berkayb.soundconnect.modules.message.dm.mapper;

import com.berkayb.soundconnect.modules.message.dm.dto.request.DMMessageRequestDto;
import com.berkayb.soundconnect.modules.message.dm.dto.response.DMMessageResponseDto;
import com.berkayb.soundconnect.modules.message.dm.entity.DMMessage;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface DMMessageMapper {
	
	@Mapping(target = "conversationId", ignore = true)
	@Mapping(target = "senderId",       ignore = true)
	@Mapping(target = "recipientId",    source = "recipientId")
	@Mapping(target = "content",        source = "content")
	@Mapping(target = "messageType",    source = "messageType")
	DMMessage toEntity(DMMessageRequestDto dto);
	
	
	@Mapping(target = "messageId", source = "id")
	@Mapping(target = "sentAt",   source = "createdAt")
	DMMessageResponseDto toResponseDto(DMMessage entity);
}
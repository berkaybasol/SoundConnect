package com.berkayb.soundconnect.modules.message.dm.mapper;

import com.berkayb.soundconnect.modules.message.dm.dto.request.DMMessageRequestDto;
import com.berkayb.soundconnect.modules.message.dm.dto.response.DMMessageResponseDto;
import com.berkayb.soundconnect.modules.message.dm.entity.DMMessage;
import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;

@Mapper(componentModel = "spring")
public interface DMMessageMapper {
	DMMessageMapper INSTANCE = Mappers.getMapper(DMMessageMapper.class);
	
	DMMessage toEntity(DMMessageRequestDto dto);
	
	DMMessageResponseDto toResponseDto(DMMessage entity);
}
package com.berkayb.soundconnect.modules.tablegroup.chat.mapper;

import com.berkayb.soundconnect.modules.tablegroup.chat.dto.response.TableGroupMessageResponseDto;
import com.berkayb.soundconnect.modules.tablegroup.chat.entity.TableGroupMessage;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface TableGroupMessageMapper {
	
	// Entity -> ResponseDto donusumu. bu donusum REST controller cevaplarinda ve WS broadcast payloadinda kullanilcak
	@Mapping(target = "messageId",    source = "id")
	@Mapping(target = "tableGroupId", source = "tableGroupId")
	@Mapping(target = "senderId",     source = "senderId")
	@Mapping(target = "content",      source = "content")
	@Mapping(target = "messageType",  source = "messageType")
	@Mapping(target = "sentAt",       source = "createdAt")
	@Mapping(target = "deletedAt",    source = "deletedAt")
	TableGroupMessageResponseDto toResponseDto(TableGroupMessage entity);
}
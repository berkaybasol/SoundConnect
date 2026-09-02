package com.berkayb.soundconnect.modules.tablegroup.chat.mapper;

import com.berkayb.soundconnect.modules.tablegroup.chat.dto.response.TableGroupMessageResponseDto;
import com.berkayb.soundconnect.modules.tablegroup.chat.entity.TableGroupMessage;
import com.berkayb.soundconnect.modules.tablegroup.game.dto.response.TableGroupGameResponseDto;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Mapper(componentModel = "spring")
public interface TableGroupMessageMapper {
	
	// Entity -> ResponseDto donusumu. bu donusum REST controller cevaplarinda ve WS broadcast payloadinda kullanilcak
	@Mapping(target = "messageId",    source = "id")
	@Mapping(target = "tableGroupId", source = "tableGroupId")
	@Mapping(target = "senderId",     source = "senderId")
	@Mapping(target = "clientMessageId", source = "clientMessageId")
	@Mapping(target = "content",      source = "content")
	@Mapping(target = "messageType",  source = "messageType")
	@Mapping(target = "sentAt",       source = "createdAt", qualifiedByName = "utcInstant")
	@Mapping(target = "deletedAt",    source = "deletedAt", qualifiedByName = "utcInstant")
	@Mapping(target = "game",         ignore = true)
	TableGroupMessageResponseDto toResponseDto(TableGroupMessage entity);

	default TableGroupMessageResponseDto toResponseDto(
			TableGroupMessage entity,
			TableGroupGameResponseDto game
	) {
		TableGroupMessageResponseDto base = toResponseDto(entity);
		return new TableGroupMessageResponseDto(
				base.messageId(),
				base.tableGroupId(),
				base.senderId(),
				base.content(),
				base.messageType(),
				base.sentAt(),
				base.deletedAt(),
				base.clientMessageId(),
				game
		);
	}

	@Named("utcInstant")
	default Instant toUtcInstant(LocalDateTime value) {
		return value == null ? null : value.toInstant(ZoneOffset.UTC);
	}
}

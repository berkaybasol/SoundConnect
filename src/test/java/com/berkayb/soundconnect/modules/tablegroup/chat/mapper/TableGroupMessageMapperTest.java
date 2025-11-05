package com.berkayb.soundconnect.modules.tablegroup.chat.mapper;

import com.berkayb.soundconnect.modules.tablegroup.chat.dto.response.TableGroupMessageResponseDto;
import com.berkayb.soundconnect.modules.tablegroup.chat.entity.TableGroupMessage;
import com.berkayb.soundconnect.modules.tablegroup.chat.enums.MessageType;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TableGroupMessageMapperTest {
	
	private final TableGroupMessageMapper mapper =
			Mappers.getMapper(TableGroupMessageMapper.class);
	
	@Test
	void toResponseDto_shouldMapAllFieldsCorrectly() {
		// given
		UUID messageId   = UUID.randomUUID();
		UUID tableGroupId = UUID.randomUUID();
		UUID senderId    = UUID.randomUUID();
		String content   = "kanka nerdesiniz";
		MessageType type = MessageType.TEXT;
		LocalDateTime sentAt    = LocalDateTime.now().minusMinutes(5);
		LocalDateTime deletedAt = LocalDateTime.now().minusMinutes(1);
		
		// BaseEntity'den gelen id/createdAt alanlarını da @SuperBuilder sayesinde set edebiliyoruz
		TableGroupMessage entity = TableGroupMessage.builder()
		                                            .id(messageId)
		                                            .tableGroupId(tableGroupId)
		                                            .senderId(senderId)
		                                            .content(content)
		                                            .messageType(type)
		                                            .createdAt(sentAt)    // sentAt -> DTO'da sentAt
		                                            .deletedAt(deletedAt)
		                                            .build();
		
		// when
		TableGroupMessageResponseDto dto = mapper.toResponseDto(entity);
		
		// then
		assertThat(dto).isNotNull();
		assertThat(dto.messageId()).isEqualTo(messageId);
		assertThat(dto.tableGroupId()).isEqualTo(tableGroupId);
		assertThat(dto.senderId()).isEqualTo(senderId);
		assertThat(dto.content()).isEqualTo(content);
		assertThat(dto.messageType()).isEqualTo(type);
		assertThat(dto.sentAt()).isEqualTo(sentAt);
		assertThat(dto.deletedAt()).isEqualTo(deletedAt);
	}
	
	@Test
	void toResponseDto_whenDeletedAtNull_shouldKeepNull() {
		// given
		UUID messageId   = UUID.randomUUID();
		UUID tableGroupId = UUID.randomUUID();
		UUID senderId    = UUID.randomUUID();
		String content   = "selam";
		MessageType type = MessageType.TEXT;
		LocalDateTime sentAt = LocalDateTime.now();
		
		TableGroupMessage entity = TableGroupMessage.builder()
		                                            .id(messageId)
		                                            .tableGroupId(tableGroupId)
		                                            .senderId(senderId)
		                                            .content(content)
		                                            .messageType(type)
		                                            .createdAt(sentAt)
		                                            .deletedAt(null)
		                                            .build();
		
		// when
		TableGroupMessageResponseDto dto = mapper.toResponseDto(entity);
		
		// then
		assertThat(dto.deletedAt()).isNull();
	}
}
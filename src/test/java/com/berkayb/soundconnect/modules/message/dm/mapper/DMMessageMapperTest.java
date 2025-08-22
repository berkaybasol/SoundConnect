// src/test/java/com/berkayb/soundconnect/modules/message/dm/mapper/DMMessageMapperTest.java
package com.berkayb.soundconnect.modules.message.dm.mapper;

import com.berkayb.soundconnect.modules.message.dm.dto.request.DMMessageRequestDto;
import com.berkayb.soundconnect.modules.message.dm.dto.response.DMMessageResponseDto;
import com.berkayb.soundconnect.modules.message.dm.entity.DMMessage;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("mapper")
class DMMessageMapperTest {
	
	private final DMMessageMapper mapper = Mappers.getMapper(DMMessageMapper.class);
	
	@Test
	void toEntity_should_map_selected_fields_and_ignore_conversation_and_sender() {
		UUID dtoConvId = UUID.randomUUID(); // mapper bunu IGNORE ediyor
		UUID recipient = UUID.randomUUID();
		
		DMMessageRequestDto dto = new DMMessageRequestDto(
				dtoConvId, recipient, "hi there", "text"
		);
		
		DMMessage entity = mapper.toEntity(dto);
		
		// ignore edilenler
		assertThat(entity.getConversationId()).isNull();
		assertThat(entity.getSenderId()).isNull();
		
		// map edilenler
		assertThat(entity.getRecipientId()).isEqualTo(recipient);
		assertThat(entity.getContent()).isEqualTo("hi there");
		assertThat(entity.getMessageType()).isEqualTo("text");
	}
	
	@Test
	void toResponseDto_should_map_ids_and_times_and_common_fields() {
		UUID id = UUID.randomUUID();
		UUID convId = UUID.randomUUID();
		UUID sender = UUID.randomUUID();
		UUID recipient = UUID.randomUUID();
		LocalDateTime created = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
		
		DMMessage entity = DMMessage.builder()
		                            .conversationId(convId)
		                            .senderId(sender)
		                            .recipientId(recipient)
		                            .content("ping")
		                            .messageType("text")
		                            .build();
		// id ve createdAt testte elle veriyoruz
		entity.setId(id);
		entity.setCreatedAt(created);
		
		DMMessageResponseDto dto = mapper.toResponseDto(entity);
		
		assertThat(dto.messageId()).isEqualTo(id);
		assertThat(dto.sentAt()).isEqualTo(created);
		
		// aynı isimli alanlar otomatik map'leniyor
		assertThat(dto.conversationId()).isEqualTo(convId);
		assertThat(dto.senderId()).isEqualTo(sender);
		assertThat(dto.recipientId()).isEqualTo(recipient);
		assertThat(dto.content()).isEqualTo("ping");
		assertThat(dto.messageType()).isEqualTo("text");
	}
}
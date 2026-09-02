package com.berkayb.soundconnect.modules.tablegroup.chat.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.berkayb.soundconnect.modules.tablegroup.chat.enums.MessageType;
import com.berkayb.soundconnect.modules.tablegroup.game.dto.response.TableGroupGameResponseDto;

import java.time.Instant;
import java.util.UUID;

public record TableGroupMessageResponseDto(
		UUID messageId,
		
		UUID tableGroupId,
		
		UUID senderId,
		
		String content,
		
		MessageType messageType,
		
		@JsonFormat(shape = JsonFormat.Shape.STRING)
		Instant sentAt,
		
		@JsonFormat(shape = JsonFormat.Shape.STRING)
		Instant deletedAt,

		UUID clientMessageId,

		TableGroupGameResponseDto game
) {
	public TableGroupMessageResponseDto(
			UUID messageId,
			UUID tableGroupId,
			UUID senderId,
			String content,
			MessageType messageType,
			Instant sentAt,
			Instant deletedAt
	) {
		this(messageId, tableGroupId, senderId, content, messageType, sentAt, deletedAt, null, null);
	}

	public TableGroupMessageResponseDto(
			UUID messageId,
			UUID tableGroupId,
			UUID senderId,
			String content,
			MessageType messageType,
			Instant sentAt,
			Instant deletedAt,
			TableGroupGameResponseDto game
	) {
		this(messageId, tableGroupId, senderId, content, messageType, sentAt, deletedAt, null, game);
	}
}

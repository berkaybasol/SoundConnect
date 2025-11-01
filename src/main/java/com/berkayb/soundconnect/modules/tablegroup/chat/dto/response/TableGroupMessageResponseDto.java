package com.berkayb.soundconnect.modules.tablegroup.chat.dto.response;

import com.berkayb.soundconnect.modules.tablegroup.chat.enums.MessageType;

import java.time.LocalDateTime;
import java.util.UUID;

public record TableGroupMessageResponseDto(
		UUID messageId,
		
		UUID tableGroupId,
		
		UUID senderId,
		
		MessageType messageType,
		
		LocalDateTime sentAt,
		
		LocalDateTime deletedAt
) {
}
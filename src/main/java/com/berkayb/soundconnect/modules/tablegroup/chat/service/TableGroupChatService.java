package com.berkayb.soundconnect.modules.tablegroup.chat.service;

import com.berkayb.soundconnect.modules.tablegroup.chat.dto.request.TableGroupMessageRequestDto;
import com.berkayb.soundconnect.modules.tablegroup.chat.dto.response.TableGroupMessageResponseDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface TableGroupChatService {
	
	// mesaj gonderir
	TableGroupMessageResponseDto sendMessage(UUID senderId, UUID tableGroupId, TableGroupMessageRequestDto requestDto);
	
	// masanin butun mesajlarini getirir
	Page<TableGroupMessageResponseDto> getMessages(UUID requesterId, UUID tableGroupId, Pageable pageable);
	
	
}
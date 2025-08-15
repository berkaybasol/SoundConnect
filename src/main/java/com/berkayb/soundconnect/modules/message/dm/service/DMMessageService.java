package com.berkayb.soundconnect.modules.message.dm.service;

import com.berkayb.soundconnect.modules.message.dm.dto.request.DMMessageRequestDto;
import com.berkayb.soundconnect.modules.message.dm.dto.response.DMMessageResponseDto;

import java.util.List;
import java.util.UUID;

public interface DMMessageService {
	
	// belirli bir konusmanin (conversationId) tum mesajlarini sirayla doner
	List<DMMessageResponseDto> getMessagesByConversationId(UUID conversationId);
	
	// Yeni mesaj gonderir ve response doner
	DMMessageResponseDto sendMessage(DMMessageRequestDto requestDto, UUID senderId);
	
	
	// okundu olarak isaretler
	void markMessageAsRead(UUID messageId, UUID readerId);
	
}
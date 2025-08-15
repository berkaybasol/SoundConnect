package com.berkayb.soundconnect.modules.message.dm.service;

import com.berkayb.soundconnect.modules.message.dm.dto.response.DMConversationPreviewResponseDto;

import java.util.List;
import java.util.UUID;

public interface DMconversationService {
	// kullanicin dahil oldugu konusmalari ozet halinde getirir
	List<DMConversationPreviewResponseDto> getAllConversationsForUser(UUID userId);
	
	// iki kullanici arasinda var olan ya da yeni olusturulan conversation'un idsini doner (varsa getirir yoksa olusturur)
	UUID getOrCreateConversation(UUID userAId, UUID userBId);
	
	
}
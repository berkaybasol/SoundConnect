package com.berkayb.soundconnect.modules.tablegroup.chat.controller;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.tablegroup.chat.dto.request.TableGroupMessageRequestDto;
import com.berkayb.soundconnect.modules.tablegroup.chat.dto.response.TableGroupMessageResponseDto;
import com.berkayb.soundconnect.modules.tablegroup.chat.service.TableGroupChatService;
import com.berkayb.soundconnect.shared.realtime.WebSocketChannels;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;

import java.util.UUID;

/**
 * Masa sohbeti (TableGroup chat) için WebSocket controller.
 *
 * Akış:
 * - Client subscribe olur: /topic/table-group/{tableGroupId}
 * - Client publish eder:  /app/table-group/{tableGroupId}/chat
 *
 * Bu controller publish'i dinler (MessageMapping),
 * mesajı yetki kurallarına göre işler (service),
 * DB'ye yazar,
 * sonra herkese geri broadcast eder (convertAndSend).
 *
 * Bu sayede hem REST hem WS aynı iş kurallarını kullanır.
 */
@Controller
@RequiredArgsConstructor
@Slf4j
public class TableGroupChatWebSocketController {
	
	private final TableGroupChatService chatService;
	private final SimpMessagingTemplate messagingTemplate;
	
	/**
	 * The WebSocket authentication principal carries the immutable user UUID.
	 */
	private UUID getCurrentUserId(Authentication authentication) {
		if (authentication != null && authentication.getPrincipal() instanceof UserDetailsImpl principal) {
			return principal.getId();
		}
		throw new AuthenticationCredentialsNotFoundException("Authenticated WebSocket user required");
	}
	
	/**
	 * Client buraya STOMP ile mesaj gönderir:
	 *
	 * SEND destination: /app/table-group/{tableGroupId}/chat
	 * Body (JSON):
	 * {
	 *   "content": "kanka nerdesiniz",
	 *   "messageType": "TEXT"
	 * }
	 *
	 * Biz:
	 * 1. Kullanıcı kim? (principal)
	 * 2. Bu kullanıcı bu masada yazabilir mi? (service içinde kontrol)
	 * 3. Mesajı DB'ye kaydet
	 * 4. Bu mesajı tüm masadaki subscriber'lara publish et
	 *
	 * Subscriber tarafı şurayı dinliyor:
	 * SUBSCRIBE destination: /topic/table-group/{tableGroupId}
	 */
	@MessageMapping("/table-group/{tableGroupId}/chat")
	public void handleMessage(
			Authentication principal,
			@Payload @Valid TableGroupMessageRequestDto requestDto,
			@DestinationVariable("tableGroupId") UUID tableGroupId
	) {
		// Authenticated user
		UUID senderId = getCurrentUserId(principal);
		
		// Sadece business + DB + unread + WS publish -> hepsi service'in içinde
		TableGroupMessageResponseDto sentDto =
				chatService.sendMessage(senderId, tableGroupId, requestDto);
		
		
		log.info(
				"WebSocket MSG sent -> tableGroupId={}, senderId={}, messageType={}, messageId={}",
				tableGroupId,
				senderId,
				sentDto.messageType(),
				sentDto.messageId()
		);
	}
}

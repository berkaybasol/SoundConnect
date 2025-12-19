package com.berkayb.soundconnect.modules.pulse.controller;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.pulse.dto.request.PulseMessageSendRequestDto;
import com.berkayb.soundconnect.modules.pulse.event.PulseMessageEvent;
import com.berkayb.soundconnect.modules.pulse.redis.PulseRedisService;
import com.berkayb.soundconnect.modules.pulse.service.PulseRoomService;
import com.berkayb.soundconnect.shared.realtime.WebSocketChannels;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.time.Instant;
import java.util.UUID;

/**
 * Pulse modulu icin WebSocket/STOMP uzerinden gelen mesajlari karsilayan controller
 * - Client -> /app/pulse/send uzerinden mesaj gonderir
 * - Server -> /topic/pulse{roomId} uzerinden ilgili odaya broadcast yapar
 */

@Controller
@RequiredArgsConstructor
@Slf4j
public class PulseMessageController {
	
	private final SimpMessagingTemplate messagingTemplate;
	private final PulseRedisService pulseRedisService;
	private final PulseRoomService pulseRoomService;
	
	// Pulse odasina gelen bir mesaji isler
	@MessageMapping("/pulse/send")
	public void handlePulseMessage (@Payload PulseMessageSendRequestDto request, Principal principal) {
		
		// WebSocket oturumunda kullanici authenticated olmali.
		if (principal == null) {
			log.warn("[Pulse] Ananymmous principal ile mesaj gonderilmeye calisildi. Mesaj ignore edildi.");
			return;
		}
		
		// Spring Security + STOMP entegrasyonunda Principal genelde Authentication implementasyonu olur
		if (!(principal instanceof Authentication authentication)) {
			log.warn("[Pulse] Principal Authentication degil. type = {}", principal.getClass().getName());
			return;
		}
		
		Object principalObj = authentication.getPrincipal();
		if (!(principalObj instanceof UserDetailsImpl userDetails)) {
			log.warn("[Pulse] Principal UserDetailsImpl degil. type={}", principalObj.getClass().getName());
			return;
		}
		UUID userId = userDetails.getId();
		String username = userDetails.getUsername();
		
		// profil foto projedeki user yapisina gore uyarlayabilinsin
		String profileImageUrl = null;
		if (userDetails.getUser() != null) {
			try {
				profileImageUrl = userDetails.getUser().getProfilePicture();
			} catch (Exception e) {
				log.debug("[Pulse] Kullanici profil fotosu okunurken hata olustu id={}", userId, e);
			}
		}
		
		// temel validasyonlar
		if (request == null) {
			log.warn("[Pulse] Null request ile mesaj gonderilmeye calisildi. userId{}", userId);
			return;
		}
		
		if (request.roomId() == null) {
			log.warn("[Pulse] roomId bos. userId={}", userId);
			return;
		}
		
		String content = request.content();
		if (content == null) {
			log.warn("[Pulse] content null. userId={}, roomId={}", userId, request.roomId());
			return;
		}
		
		content = content.trim();
		if (content.isEmpty()) {
			log.debug("[Pulse] Bos/whitespace mesaj ignore edildi. userId={}, roomId={}", userId,request.roomId());
			return;
		}
		
		// mesaj uzunluk limiti
		int maxLength = 264;
		if (content.length() > maxLength) {
			log.warn("[Pulse] Mesaj max uzunluktan buyuk. userId={}, roomId={}, length={}", userId, request.roomId(), content.length());
			return;
		}
		
		if (pulseRedisService.isUserInCooldown(userId)) {
			log.debug("[Pulse] Cooldown active. Message ignored. userId={}", userId);
			return;
		}
		
		UUID roomId = request.roomId();

		// kullanıcı odada mı?
		if (!pulseRedisService.isUserInRoom(roomId, userId)) {
			log.warn("[Pulse] User not in room. message ignored. roomId={}, userId={}", roomId, userId);
			return;
		}
		
		pulseRedisService.setUserCooldown(userId, pulseRoomService.getCooldownSeconds());
		
		
		// event nesnesini olustur
		PulseMessageEvent event = PulseMessageEvent.builder()
				.roomId(request.roomId())
				.userId(userId)
				.username(username)
				.profileImageUrl(profileImageUrl)
				.content(content)
				.sentAt(Instant.now())
				.build();
		
		String destination = WebSocketChannels.pulseRoom(request.roomId());
		log.debug("[Pulse] Mesaj yayinlaniyor. roomId={}, userId={}, destination={}", request.roomId(), userId, destination);
		messagingTemplate.convertAndSend(destination, event);
		
	}
}
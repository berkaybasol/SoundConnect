package com.berkayb.soundconnect.modules.pulse.controller;


// pulse odalarindaki anket islemleri. websocket uzerinden yonetir.

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.pulse.dto.request.PulseVoteRequestDto;
import com.berkayb.soundconnect.modules.pulse.enums.PulseRoomStatus;
import com.berkayb.soundconnect.modules.pulse.event.PulseVoteUpdateEvent;
import com.berkayb.soundconnect.modules.pulse.model.PulseRoomState;
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
import java.util.Map;
import java.util.UUID;

@Controller
@RequiredArgsConstructor
@Slf4j
public class PulseVoteController {
	
	private final PulseRedisService pulseRedisService;
	private final PulseRoomService pulseRoomService;
	private final SimpMessagingTemplate messagingTemplate;
	
	@MessageMapping("/pulse/vote")
	public void handleVote(@Payload PulseVoteRequestDto request, Principal principal) {
		if (principal == null || !(principal instanceof Authentication auth)) {
			log.warn("[PulseVote] Anonymous vote ignored.");
			return;
		}
		
		Object p = auth.getPrincipal();
		if (!(p instanceof UserDetailsImpl userDetails)) {
			log.warn("[PulseVote] Invalid principal type={}", p.getClass().getName());
			return;
		}
		
		UUID userId = userDetails.getId();
		
		if (request == null || request.roomId() == null || request.topic() == null) {
			log.info("[PulseVote] Invalid vote request. userId={}, request={}", userId, request);
			return;
		}
		
		String topic = request.topic().trim();
		if (topic.isEmpty()) {
			log.debug("[PulseVote] Empty topic ignored. userId={}", userId);
			return;
		}
		
		UUID roomId = request.roomId();
		
		// oda state kontrolu
		PulseRoomState room = pulseRoomService.getRoom(roomId);
		
		if (room.getStatus() != PulseRoomStatus.VOTING){
			log.debug("[PulseVote] vote ignored. room not in voting. roomId={}",roomId);
			return;
		}
		// kullanici odada mi?
		if (!pulseRedisService.isUserInRoom(roomId, userId)) {
			log.warn("[PulseVote] User not in room. vote ignored. roomId={}, userId={}", roomId, userId);
			return;
		}
		
		if (pulseRedisService.isUserInCooldown(userId)) {
			log.debug("[PulseVote] Cooldown active. Vote ignored. userId={}", userId);
			return;
		}
		pulseRedisService.setUserCooldown(userId, pulseRoomService.getCooldownSeconds());
		
		
		// oyu kaydet
		pulseRedisService.saveVote(roomId, userId, topic);
		
		// guncel sonuclari al
		Map<String, Integer> results = pulseRedisService.getVoteResults(roomId);
		
		// event olustur
		PulseVoteUpdateEvent event = PulseVoteUpdateEvent.builder()
				.roomId(roomId)
				.results(results)
				.build();
		
		// odaya broadcast et
		messagingTemplate.convertAndSend(WebSocketChannels.pulseVote(roomId), event);
		
		log.debug("[PulseVote] Vote processed. roomId={}, userId={}, topic={}", roomId, userId, topic);
	}
}
package com.berkayb.soundconnect.modules.pulse.service;

import com.berkayb.soundconnect.modules.pulse.config.PulseProperties;
import com.berkayb.soundconnect.modules.pulse.enums.PulseRoomStatus;
import com.berkayb.soundconnect.modules.pulse.model.PulseRoomState;
import com.berkayb.soundconnect.modules.pulse.redis.PulseRedisService;
import com.berkayb.soundconnect.modules.pulse.registry.PulseRoomRegistry;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.berkayb.soundconnect.shared.realtime.WebSocketChannels;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;


@Service
@RequiredArgsConstructor
@Slf4j
public class PulseRoomServiceImpl implements PulseRoomService {
	
	private final PulseRedisService pulseRedisService;
	private final PulseRoomRegistry pulseRoomRegistry;
	private final SimpMessagingTemplate messagingTemplate;
	private final PulseProperties pulseProperties;
	
	// oda bitimine kac dk kala voting durumuna gecsin
	private static final Duration VOTING_WINDOW = Duration.ofMinutes(5);

	// oda bitimine kac sana kala closing durumuna gecsin
	private static final Duration CLOSING_WINDOW = Duration.ofSeconds(30);
	
	/**
	 * Scheduler her 5 saniyede bir lifecyle kontrolu yapar
	 */
	@Override
	@Scheduled(fixedDelayString = "${pulse.lifecycle-tick-interval-ms:5000}")
	public void tickRoomLifecycle() {
		if (pulseRoomRegistry.isEmpty()) {
			log.debug("[PulseRoom] Registry empty. No rooms to tick.");
			return;
		}
		Instant now = Instant.now();
		
		for (UUID roomId : pulseRoomRegistry.getAllRoomIds()) {
			pulseRedisService.getRoomState(roomId).ifPresentOrElse(room -> {
				handleLifecycle(room,now);
			}, () -> {
				// Resgistry'de var ama rediste yoksa logla.
				log.warn("[PulseRoom] Room exists in registry but not in Redis. roomId={}", roomId);
			});
		}
	}
	
	@PostConstruct
	public void initRoomsOnStartup() {
		// uygulama ayaga kalkinca registry bossa odalari olustur.
		if (!pulseRoomRegistry.isEmpty()) {
			log.info("[PulseRoom] Registry already has rooms, skipping init.");
			return;
		}
		log.info("[PulseRoom] Creating initial rooms. count={}, duration={}min", pulseProperties.getRooms().getCount(), pulseProperties.getRoomDurationMinutes());
		
		for (int i = 1; i <= pulseProperties.getRooms().getCount(); i++) {
			createInitialRoom(i);
		}
		log.info("[PulseRoom] Initial rooms created successfully.");
	}
	
	@Override
	public PulseRoomState createRoom(String initialTopic) {
		PulseRoomState room = PulseRoomState.builder()
				.roomId(UUID.randomUUID())
				.roomNumber(null)
				.topic(sanitizeTopic(initialTopic))
				.endsAt(Instant.now().plus(Duration.ofMinutes(pulseProperties.getRoomDurationMinutes())))
				.activeUserCount(0)
				.status(PulseRoomStatus.ACTIVE)
				.build();
			
		pulseRedisService.saveRoomState(room);
		pulseRoomRegistry.register(room.getRoomId());
		
		publishRoomState(room);
		publishPresence(room.getRoomId(), 0);
		
		log.info("[PulseRoom] Room created. roomId={}, topic={}", room.getRoomId(), room.getTopic());
		return room;
		
	}
	
	@Override
	public PulseRoomState getRoom(UUID roomId) {
		return pulseRedisService.getRoomState(roomId)
				.orElseThrow(() -> new SoundConnectException(ErrorType.ROOM_NOT_FOUND));
	}
	
	@Override
	public void endRoom(UUID roomId) {
		// pulse tasariminda oda kapanmaz sadece cycle yenilenir.
		// bu methodu ileride admin/maintenance icin sakliyoz
		PulseRoomState room = getRoom(roomId);
		room.setStatus(PulseRoomStatus.CLOSING);
		pulseRedisService.saveRoomState(room);
		
		publishRoomState(room);
		
		log.warn("[PulseRoom] endRoom called. (reserved for admin/maintenance). roomId={}", roomId);
	}
	
	@Override
	public void updateTopic(UUID roomId, String newTopic) {
		PulseRoomState room = getRoom(roomId);
		
		room.setTopic(sanitizeTopic(newTopic));
		room.setEndsAt(Instant.now().plus(Duration.ofMinutes(pulseProperties.getRoomDurationMinutes())));
		room.setStatus(PulseRoomStatus.ACTIVE);
		
		// activeUserCountu redis set uzerinden guncelle
		int active = pulseRedisService.getActiveUserCount(roomId);
		room.setActiveUserCount(active);
		
		pulseRedisService.saveRoomState(room);
		
		publishRoomState(room);
		publishPresence(roomId, active);
		
		log.info("[PulseRoom] Topic updated. roomId={}, topic={}", roomId, room.getTopic());
	}
	
	@Override
	public void userJoin(UUID roomId, UUID userId) {
		// oda var mi
		PulseRoomState room = getRoom(roomId);
		
		pulseRedisService.addUserToRoom(roomId,userId);
		
		int active = pulseRedisService.getActiveUserCount(roomId);
		room.setActiveUserCount(active);
		pulseRedisService.saveRoomState(room);
		
		publishPresence(roomId, active);
		publishRoomState(room);
		
		log.debug("[PulseRoom] User joined. roomId={}, userId={}, active={}", roomId, userId, active);
	}
	
	@Override
	public void userLeave(UUID roomId, UUID userId) {
		PulseRoomState room = getRoom(roomId);
		
		pulseRedisService.removeUserFromRoom(roomId,userId);
		
		int active = pulseRedisService.getActiveUserCount(roomId);
		room.setActiveUserCount(active);
		pulseRedisService.saveRoomState(room);
		
		publishPresence(roomId, active);
		publishRoomState(room);
		
		log.debug("[PulseRoom] User left. roomId={}, userId={}, active={}", roomId, userId, active);
	}
	
	@Override
	public int getActiveUserCount(UUID roomId) {
		return pulseRedisService.getActiveUserCount(roomId);
	}
	
	@Override
	public boolean isUserInRoom(UUID roomId, UUID userId) {
		return pulseRedisService.isUserInRoom(roomId,userId);
	}
	
	// helpers
	
	private void createInitialRoom(int roomNumber) {
		PulseRoomState room = PulseRoomState.builder()
		                                    .roomId(UUID.randomUUID())
		                                    .roomNumber(roomNumber)
		                                    .topic(pickRandomTopic())
		                                    .endsAt(Instant.now().plus(Duration.ofMinutes(pulseProperties.getRoomDurationMinutes())))
		                                    .activeUserCount(0)
		                                    .status(PulseRoomStatus.ACTIVE)
		                                    .build();
		
		pulseRedisService.saveRoomState(room);
		pulseRoomRegistry.register(room.getRoomId());
		
		publishRoomState(room);
		publishPresence(room.getRoomId(), 0);
		
		log.info("[PulseRoom] Initial room created. roomNo={}, roomId={}, topic={}", roomNumber, room.getRoomId(), room.getTopic());
	}
	
	private void handleLifecycle(PulseRoomState room, Instant now) {
		Instant endsAt = room.getEndsAt();
		
		if (endsAt == null) {
			room.setEndsAt(now.plus(Duration.ofMinutes(pulseProperties.getRoomDurationMinutes())));
			room.setStatus(PulseRoomStatus.ACTIVE);
			persistAndBroadcastRoom(room);
			return;
		}
		
		Duration remaining = Duration.between(now, endsAt);
		
		// süre doldu → yeni cycle
		if (!remaining.isPositive()) {
			rotateRoomCycle(room, now);
			return;
		}
		
		// VOTING: son 5 dk - son 30 sn arası
		if (remaining.compareTo(VOTING_WINDOW) <= 0 && remaining.compareTo(CLOSING_WINDOW) > 0) {
			if (room.getStatus() != PulseRoomStatus.VOTING) {
				room.setStatus(PulseRoomStatus.VOTING);
				seedVotingTopics(room);
				persistAndBroadcastRoom(room);
			}
			return;
		}
		
		// CLOSING: son 30 sn
		if (remaining.compareTo(CLOSING_WINDOW) <= 0) {
			if (room.getStatus() != PulseRoomStatus.CLOSING) {
				room.setStatus(PulseRoomStatus.CLOSING);
				persistAndBroadcastRoom(room);
			}
			return;
		}
		
		// ACTIVE
		if (room.getStatus() != PulseRoomStatus.ACTIVE) {
			room.setStatus(PulseRoomStatus.ACTIVE);
			persistAndBroadcastRoom(room);
		}
	}
	
	public void rotateRoomCycle(PulseRoomState room, Instant now) {
		String newTopic = pickWinningTopic(room.getRoomId());
		
		
		room.setTopic(newTopic);
		room.setEndsAt(now.plus(Duration.ofMinutes(pulseProperties.getRoomDurationMinutes())));
		room.setStatus(PulseRoomStatus.ACTIVE);
		
		// presence silmiyoruz kullanicilar odada kaliyor
		int active = pulseRedisService.getActiveUserCount(room.getRoomId());
		room.setActiveUserCount(active);
		
		pulseRedisService.saveRoomState(room);
		publishRoomState(room);
		publishPresence(room.getRoomId(), active);
		
		pulseRedisService.clearVotes(room.getRoomId());
		
		log.info("[PulseRoom] Cycle rotated. roomId={}, roomNo={}, newTopic={}, active={}", room.getRoomId(),
		         room.getRoomNumber(),newTopic, active);
	}
	
	private void persistAndBroadcastRoom(PulseRoomState room) {
		// activeUserCount'u guncel tutuyoz
		int active = pulseRedisService.getActiveUserCount(room.getRoomId());
		room.setActiveUserCount(active);
		
		pulseRedisService.saveRoomState(room);
		publishRoomState(room);
		publishPresence(room.getRoomId(), active);
	}
	
	private String pickRandomTopic() {
		if (pulseProperties.getDefaultTopics() == null || pulseProperties.getDefaultTopics().isEmpty()) {
			return "Gundem Serbest";
		}
		int idx = ThreadLocalRandom.current().nextInt(pulseProperties.getDefaultTopics().size());
		return pulseProperties.getDefaultTopics().get(idx);
	}
	
	public void publishRoomState(PulseRoomState room) {
		// Room state guncellemeleri ayri kanaldan gitsin message event ile karismasin
		String destination = WebSocketChannels.pulseRoom(room.getRoomId()) + "/state";
		messagingTemplate.convertAndSend(destination, room);
	}
	
	private String sanitizeTopic(String topic) {
		if (topic == null) return "Gundem Serbest";
		String t = topic.trim();
		return t.isEmpty() ? "Gundem Serbest" : t;
	}
	
	
	
	public void publishPresence(UUID roomId, int activeCount) {
		String destination = WebSocketChannels.pulseRoom(roomId) + "/presence";
		messagingTemplate.convertAndSend(destination, activeCount);
	}
	
	private void seedVotingTopics(PulseRoomState room) {
		// oylar temiz başlasın
		pulseRedisService.clearVotes(room.getRoomId());
		
		if (pulseProperties.getDefaultTopics() == null || pulseProperties.getDefaultTopics().isEmpty()) {
			return;
		}
		
		// topic count'ları 0 ile initialize et
		pulseProperties.getDefaultTopics().forEach(topic -> pulseRedisService.initVoteTopic(room.getRoomId(), topic));
		
		log.info("[PulseVote] Voting topics initialized. roomId={}, topics={}", room.getRoomId(), pulseProperties.getDefaultTopics());
	}
	
	
	private String pickWinningTopic(UUID roomId) {
		var results = pulseRedisService.getVoteResults(roomId);
		
		if (results == null || results.isEmpty()) {
			log.warn("[PulseVote] No votes found. Falling back to random topic.");
			return pickRandomTopic();
		}
		
		return results.entrySet()
		              .stream()
		              .max((a, b) -> Integer.compare(a.getValue(), b.getValue()))
		              .map(e -> e.getKey())
		              .orElseGet(this::pickRandomTopic);
	}
	
	
	public int getCooldownSeconds() {
		return pulseProperties.getCooldown().getSeconds();
	}
}
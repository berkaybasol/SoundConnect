package com.berkayb.soundconnect.modules.pulse.redis;

import com.berkayb.soundconnect.modules.pulse.model.PulseRoomState;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class PulseRedisServiceImpl implements PulseRedisService{
	
	private final RedisTemplate<String, String> redisTemplate;
	private final ObjectMapper objectMapper;
	
	// KEY HELPERS
	private String roomStateKey(UUID roomId) {
		return "pulse:room:" + roomId;
	}
	
	private String presenceKey(UUID roomId) {
		return "pulse:room:" + roomId + ":users";
	}
	
	private String cooldownKey(UUID userId) {
		return "pulse:cooldown:" + userId;
	}
	
	@Override
	public void initVoteTopic(UUID roomId, String topic) {
		redisTemplate.opsForHash().put(voteTopicCountKey(roomId), topic, "0");
	}
	
	@Override
	public void saveRoomState(PulseRoomState state) {
	try {
		String json = objectMapper.writeValueAsString(state);
		redisTemplate.opsForValue().set(roomStateKey(state.getRoomId()), json);
		log.debug("[PulseRedis] RoomState saved. roomId={}", state.getRoomId());
	} catch (Exception e) {
		log.error("[PulseRedis] Failed to save RoomState. roomId={}", state.getRoomId(), e);
		}
	}
	
	@Override
	public Optional<PulseRoomState> getRoomState(UUID roomId) {
		try {
			String json = redisTemplate.opsForValue().get(roomStateKey(roomId));
			if (json == null) return Optional.empty();
			return Optional.of(objectMapper.readValue(json, PulseRoomState.class));
		} catch (Exception e) {
			log.error("[PulseRedis] Failed to read RoomState. roomId={}", roomId, e);
			return Optional.empty();
		}
	}
	
	@Override
	public void deleteRoomState(UUID roomId) {
		redisTemplate.delete(roomStateKey(roomId));
		log.debug("[PulseRedis] RoomState deleted. roomId={}", roomId);
	}
	
	@Override
	public void addUserToRoom(UUID roomId, UUID userId) {
		redisTemplate.opsForSet().add(presenceKey(roomId), userId.toString());
		log.debug("[PulseRedis] User added to room. roomId={}, userId={}", roomId, userId);
	}
	
	@Override
	public void removeUserFromRoom(UUID roomId, UUID userId) {
		redisTemplate.opsForSet().remove(presenceKey(roomId), userId.toString());
		log.debug("[PulseRedis] User removed from room. roomId={}, userId={}", roomId, userId);
	}
	
	@Override
	public int getActiveUserCount(UUID roomId) {
		Long count = redisTemplate.opsForSet().size(presenceKey(roomId));
		return count == null ? 0 : count.intValue();
	}
	
	@Override
	public boolean isUserInRoom(UUID roomId, UUID userId) {
		Boolean result = redisTemplate.opsForSet().isMember(presenceKey(roomId), userId.toString());
		return result != null && result;
	}
	
	@Override
	public void setUserCooldown(UUID userId, long ttlSeconds) {
		redisTemplate.opsForValue().set(cooldownKey(userId), "1", Duration.ofSeconds(ttlSeconds));
		log.debug("[PulseRedis] CooldowFn set. userId={}, ttl={}", userId, ttlSeconds);
	}
	
	@Override
	public boolean isUserInCooldown(UUID userId) {
		Boolean exists = redisTemplate.hasKey(cooldownKey(userId));
		return exists != null && exists;
	}
	
	@Override
	public void saveVote(UUID roomId, UUID userId, String topic) {
		String userVoteKey = voteUserKey(roomId);
		String topicCountKey = voteTopicCountKey(roomId);
		
		String userIdStr = userId.toString();
		
		Object prevObj = redisTemplate.opsForHash().get(userVoteKey, userIdStr);
		String previousTopic = prevObj == null ? null : prevObj.toString();
		
		// aynı topic'e tekrar oy verdiyse no-op
		if (previousTopic != null && previousTopic.equals(topic)) {
			return;
		}
		
		// önceki oy varsa geri al
		if (previousTopic != null) {
			redisTemplate.opsForHash().increment(topicCountKey, previousTopic, -1);
		}
		
		// yeni oyu setle
		redisTemplate.opsForHash().put(userVoteKey, userIdStr, topic);
		
		// yeni topic count++
		redisTemplate.opsForHash().increment(topicCountKey, topic, 1);
		
		log.debug("[PulseRedis] Vote saved. roomId={}, userId={}, topic={}, prev={}", roomId, userId, topic, previousTopic);
	}
	
	
	@Override
	public boolean hasUserVoted(UUID roomId, UUID userId) {
		Boolean exists = redisTemplate.opsForHash().hasKey(voteUserKey(roomId), userId.toString());
		return exists != null && exists;
	}
	
	@Override
	public Map<String, Integer> getVoteResults(UUID roomId) {
		Map<Object, Object> raw = redisTemplate.opsForHash().entries(voteTopicCountKey(roomId));
		
		if (raw == null || raw.isEmpty()) {
			return Map.of();
		}
		return raw.entrySet()
				.stream()
				.collect(Collectors.toMap(
						e -> e.getKey().toString(),
						e -> Integer.parseInt(e.getValue().toString())
				));
	}
	
	@Override
	public void clearVotes(UUID roomId) {
		redisTemplate.delete(voteUserKey(roomId));
		redisTemplate.delete(voteTopicCountKey(roomId));
		
		log.info("[PulseRedis] Votes cleared. roomId={}", roomId);
	}
	
	// KEY HELPERS
	private String voteKey(UUID roomId) {
		return "pulse:vote:" + roomId;
	}
	
	private String voteTopicCountKey(UUID roomId) {
		return "pulse:vote:topics:" + roomId;
	}
	
	private String voteUserKey(UUID roomId) {
		return "pulse:vote:users:" + roomId;
	}
}
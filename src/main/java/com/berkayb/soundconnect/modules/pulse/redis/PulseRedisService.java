package com.berkayb.soundconnect.modules.pulse.redis;

import com.berkayb.soundconnect.modules.pulse.model.PulseRoomState;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Pulse modulu icin Redis uzerinden:
 * - oda state
 * - user presence
 * - cooldown
 * islemlerini yoneten service inteface.
 */
public interface PulseRedisService {
	
	void saveRoomState(PulseRoomState state);
	
	Optional<PulseRoomState> getRoomState(UUID roomId);
	
	void deleteRoomState(UUID roomId);
	
	// belirtilen useri belirtilen odaya ekle. Redis SET -> SADD
	void addUserToRoom(UUID roomId, UUID userId);
	
	// kullanici odayi terk ettiginde Removes from Set -> SREM
	void removeUserFromRoom(UUID roomId, UUID userId);
	
	// odadaki aktif kullanici sayisi -> SCARD
	int getActiveUserCount(UUID roomId);
	
	// kullanici odada mi? -> SISMEMBER
	boolean isUserInRoom(UUID roomId, UUID userId);
	
	// kullaniciya mesaj gonderdiginde timestamp set edilir. Key: pulse:cooldown:{userId}, TTL: cooldown suresi
	void setUserCooldown(UUID userId, long ttlSeconds);
	
	// kullanici cooldown icinde mi? Redis EXISTS check
	boolean isUserInCooldown(UUID userId);
	
	// kullanici oy verir
	void saveVote(UUID roomId, UUID userId, String topic);
	
	// kullanici daha once oy vermis mi?
	boolean hasUserVoted(UUID roomId, UUID userId);
	
	// odadaki tum topic -> voteCount map
	Map<String, Integer> getVoteResults(UUID roomId);
	
	// odaya ait tum vote datalarini sil
	void clearVotes(UUID roomId);
	
	void initVoteTopic(UUID roomId, String topic);
}
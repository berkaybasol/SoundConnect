package com.berkayb.soundconnect.shared.realtime;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class WebSocketSessionRegistry {

	private final ConcurrentMap<String, SessionContext> sessions = new ConcurrentHashMap<>();

	public void register(String sessionId, UUID userId, long expiresAtEpochMillis) {
		register(sessionId, userId, expiresAtEpochMillis, 0L);
	}

	public void register(String sessionId, UUID userId, long expiresAtEpochMillis, long sessionVersion) {
		if (sessionId == null || sessionId.isBlank() || userId == null) {
			throw new IllegalArgumentException("WebSocket session identity is required");
		}
		sessions.put(sessionId, new SessionContext(userId, expiresAtEpochMillis, sessionVersion));
	}

	public Optional<SessionContext> find(String sessionId) {
		if (sessionId == null || sessionId.isBlank()) {
			return Optional.empty();
		}
		return Optional.ofNullable(sessions.get(sessionId));
	}

	public void remove(String sessionId) {
		if (sessionId != null) {
			sessions.remove(sessionId);
		}
	}

	public record SessionContext(UUID userId, long expiresAtEpochMillis, long sessionVersion) {
	}
}

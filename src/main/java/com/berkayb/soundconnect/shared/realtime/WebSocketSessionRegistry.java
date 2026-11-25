package com.berkayb.soundconnect.shared.realtime;

import com.berkayb.soundconnect.auth.passwordchange.AccountPasswordChangeService.PasswordChanged;
import com.berkayb.soundconnect.modules.user.service.AccountAccessChanged;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
@Slf4j
public class WebSocketSessionRegistry {
	private static final String TRANSPORT = WebSocketSessionRegistry.class.getName() + ".transport";
	private final ConcurrentMap<String, SessionContext> sessions = new ConcurrentHashMap<>();

	public WebSocketHandler trackTransport(WebSocketHandler delegate) {
		return new WebSocketHandlerDecorator(delegate) {
			@Override public void afterConnectionEstablished(WebSocketSession session) throws Exception {
				session.getAttributes().put(TRANSPORT, session);
				super.afterConnectionEstablished(session);
			}

			@Override public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
				try {
					super.afterConnectionClosed(session, status);
				} finally {
					remove(session.getId());
					session.getAttributes().remove(TRANSPORT);
				}
			}
		};
	}

	public void register(String sessionId, UUID userId, long expiresAtEpochMillis) {
		register(sessionId, userId, expiresAtEpochMillis, 0L);
	}

	public void register(String sessionId, UUID userId, long expiresAtEpochMillis, long sessionVersion) {
		register(sessionId, userId, expiresAtEpochMillis, sessionVersion, null);
	}

	void register(String sessionId, UUID userId, long expiresAtEpochMillis, long sessionVersion, Map<String,Object> attributes) {
		if (sessionId == null || sessionId.isBlank() || userId == null) {
			throw new IllegalArgumentException("WebSocket session identity is required");
		}
		WebSocketSession socket = attributes != null && attributes.get(TRANSPORT) instanceof WebSocketSession transport
				? transport : null;
		sessions.put(sessionId, new SessionContext(userId, expiresAtEpochMillis, sessionVersion, socket));
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

	@TransactionalEventListener
	public void accountAccessChanged(AccountAccessChanged decision) {
		if (decision.suspended()) closeOlderSessions(decision.userId(), decision.sessionVersion());
	}

	@TransactionalEventListener
	public void passwordChanged(PasswordChanged change) {
		closeOlderSessions(change.userId(), change.sessionVersion());
	}

	private void closeOlderSessions(UUID userId, long version) {
		sessions.forEach((id, session) -> {
			if (session.userId().equals(userId) && session.sessionVersion() < version) {
				closeRevoked(id, session);
			}
		});
	}

	void closeRevoked(String id, SessionContext session) {
		if (!sessions.remove(id, session) || session.socket() == null) return;
		try {
			session.socket().close(CloseStatus.POLICY_VIOLATION);
		} catch (IOException | RuntimeException failure) {
			log.debug("Revoked WebSocket transport could not be closed: {}", failure.getClass().getSimpleName());
		}
	}

	public record SessionContext(UUID userId, long expiresAtEpochMillis, long sessionVersion, WebSocketSession socket) {
	}
}

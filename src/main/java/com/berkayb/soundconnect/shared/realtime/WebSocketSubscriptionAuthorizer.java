package com.berkayb.soundconnect.shared.realtime;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.pulse.redis.PulseRedisService;
import com.berkayb.soundconnect.modules.tablegroup.enums.ParticipantStatus;
import com.berkayb.soundconnect.modules.tablegroup.enums.TableGroupStatus;
import com.berkayb.soundconnect.modules.tablegroup.repository.TableGroupRepository;
import com.berkayb.soundconnect.modules.tablegroup.chat.dto.response.TableGroupMessageResponseDto;
import com.berkayb.soundconnect.modules.tablegroup.game.service.TableGroupGameVisibility;
import com.berkayb.soundconnect.modules.user.block.UserBlockService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * STOMP subscription policy. Every destination is denied unless it matches one
 * of the explicit channel contracts and the authenticated user owns access.
 */
@Component
@RequiredArgsConstructor
public class WebSocketSubscriptionAuthorizer {

	private static final String UUID_PATTERN =
			"([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})";
	private static final Pattern USER_DESTINATION = Pattern.compile(
			"^(?:" + Pattern.quote(WebSocketChannels.TOPIC_NOTIFICATIONS)
					+ "|" + Pattern.quote(WebSocketChannels.TOPIC_DM) + ")\\."
					+ UUID_PATTERN + "(?:\\.badge)?$"
	);
	private static final Pattern TABLE_GROUP_DESTINATION = Pattern.compile(
			"^" + Pattern.quote(WebSocketChannels.TOPIC_TABLE_GROUP) + "\\." + UUID_PATTERN + "\\." + UUID_PATTERN + "$"
	);
	private static final Pattern PULSE_DESTINATION = Pattern.compile(
			"^" + Pattern.quote(WebSocketChannels.TOPIC_PULSE) + "\\."
					+ UUID_PATTERN + "(?:\\.(?:vote|state|presence))?$"
	);
	private final TableGroupRepository tableGroupRepository;
	private final PulseRedisService pulseRedisService;
	private final UserBlockService blocks;
	private final ObjectMapper json;

	/** A queued broker frame may arrive after a block committed on another node. */
	public boolean suppressStaleTableFrame(UUID viewer, String destination, Object payload) {
		if (!destination.startsWith(WebSocketChannels.TOPIC_TABLE_GROUP + ".")) return false;
		try {
			JsonNode body = switch (payload) {
				case byte[] bytes -> json.readTree(bytes);
				case String text -> json.readTree(text);
				default -> json.valueToTree(payload);
			};
			if (body == null || !body.isObject()) return true;
			if (body.has("type")) {
				String type = body.path("type").asText();
				if ("TABLE_VISIBILITY_CHANGED".equals(type)) return false;
				return !"BLOCKED_MEMBER_JOINED".equals(type)
						|| !blocks.status(viewer, UUID.fromString(body.path("userId").asText())).blockedByMe();
			}
			var message = json.treeToValue(body, TableGroupMessageResponseDto.class);
			var ids = message.game() == null ? new java.util.HashSet<UUID>()
					: TableGroupGameVisibility.identities(java.util.List.of(message.game()));
			if (message.senderId() != null) ids.add(message.senderId());
			return !blocks.blockedAmong(viewer, ids).isEmpty();
		} catch (RuntimeException | java.io.IOException unreadable) {
			return true;
		}
	}

	public void authorize(UserDetailsImpl principal, String destination) {
		if (principal == null || destination == null || destination.isBlank()) {
			throw denied(destination);
		}

		Matcher userMatcher = USER_DESTINATION.matcher(destination);
		if (userMatcher.matches()) {
			UUID destinationUserId = UUID.fromString(userMatcher.group(1));
			if (!principal.getId().equals(destinationUserId)) {
				throw denied(destination);
			}
			return;
		}

		Matcher tableGroupMatcher = TABLE_GROUP_DESTINATION.matcher(destination);
		if (tableGroupMatcher.matches()) {
			UUID tableGroupId = UUID.fromString(tableGroupMatcher.group(1));
			if (!principal.getId().equals(UUID.fromString(tableGroupMatcher.group(2)))
					|| !canAccessTableGroup(principal.getId(), tableGroupId)) {
				throw denied(destination);
			}
			return;
		}

		Matcher pulseMatcher = PULSE_DESTINATION.matcher(destination);
		if (pulseMatcher.matches()) {
			UUID roomId = UUID.fromString(pulseMatcher.group(1));
			if (!pulseRedisService.isUserInRoom(roomId, principal.getId())) {
				throw denied(destination);
			}
			return;
		}

		throw denied(destination);
	}

	public void authorizeSend(UserDetailsImpl principal, String destination) {
		if (principal == null || destination == null || destination.isBlank()) {
			throw denied(destination);
		}
		if ("/app/pulse/send".equals(destination) || "/app/pulse/vote".equals(destination)) {
			return;
		}
		throw denied(destination);
	}

	private boolean canAccessTableGroup(UUID userId, UUID tableGroupId) {
		return tableGroupRepository.countOpenAccess(
				tableGroupId,
				userId,
				TableGroupStatus.ACTIVE,
				ParticipantStatus.ACCEPTED,
				Instant.now()
		) > 0;
	}

	private AccessDeniedException denied(String destination) {
		return new AccessDeniedException("STOMP subscription is not allowed: " + destination);
	}
}

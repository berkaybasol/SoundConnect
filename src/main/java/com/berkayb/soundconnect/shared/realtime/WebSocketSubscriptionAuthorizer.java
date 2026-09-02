package com.berkayb.soundconnect.shared.realtime;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.pulse.redis.PulseRedisService;
import com.berkayb.soundconnect.modules.tablegroup.enums.ParticipantStatus;
import com.berkayb.soundconnect.modules.tablegroup.enums.TableGroupStatus;
import com.berkayb.soundconnect.modules.tablegroup.repository.TableGroupRepository;
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
			"^" + Pattern.quote(WebSocketChannels.TOPIC_TABLE_GROUP) + "\\." + UUID_PATTERN + "$"
	);
	private static final Pattern PULSE_DESTINATION = Pattern.compile(
			"^" + Pattern.quote(WebSocketChannels.TOPIC_PULSE) + "\\."
					+ UUID_PATTERN + "(?:\\.(?:vote|state|presence))?$"
	);
	private final TableGroupRepository tableGroupRepository;
	private final PulseRedisService pulseRedisService;

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
			if (!canAccessTableGroup(principal.getId(), tableGroupId)) {
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

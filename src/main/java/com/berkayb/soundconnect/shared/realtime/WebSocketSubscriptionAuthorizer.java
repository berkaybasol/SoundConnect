package com.berkayb.soundconnect.shared.realtime;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.pulse.redis.PulseRedisService;
import com.berkayb.soundconnect.modules.tablegroup.entity.TableGroup;
import com.berkayb.soundconnect.modules.tablegroup.enums.ParticipantStatus;
import com.berkayb.soundconnect.modules.tablegroup.enums.TableGroupStatus;
import com.berkayb.soundconnect.modules.tablegroup.repository.TableGroupRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
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
			"^/topic/(?:notifications|dm)/" + UUID_PATTERN + "(?:/badge)?$"
	);
	private static final Pattern TABLE_GROUP_DESTINATION = Pattern.compile(
			"^/topic/table_group/" + UUID_PATTERN + "$"
	);
	private static final Pattern PULSE_DESTINATION = Pattern.compile(
			"^/topic/pulse/" + UUID_PATTERN + "(?:/(?:vote|state|presence))?$"
	);
	private static final Pattern TABLE_GROUP_SEND_DESTINATION = Pattern.compile(
			"^/app/table-group/" + UUID_PATTERN + "/chat$"
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
		Matcher tableGroupMatcher = TABLE_GROUP_SEND_DESTINATION.matcher(destination);
		if (tableGroupMatcher.matches()
				&& canAccessTableGroup(principal.getId(), UUID.fromString(tableGroupMatcher.group(1)))) {
			return;
		}
		throw denied(destination);
	}

	private boolean canAccessTableGroup(UUID userId, UUID tableGroupId) {
		return tableGroupRepository.findById(tableGroupId)
				.filter(this::isOpenTableGroup)
				.filter(tableGroup -> isAcceptedParticipantOrOwner(tableGroup, userId))
				.isPresent();
	}

	private boolean isOpenTableGroup(TableGroup tableGroup) {
		return tableGroup.getStatus() == TableGroupStatus.ACTIVE
				&& (tableGroup.getExpiresAt() == null || tableGroup.getExpiresAt().isAfter(LocalDateTime.now()));
	}

	private boolean isAcceptedParticipantOrOwner(TableGroup tableGroup, UUID userId) {
		if (userId.equals(tableGroup.getOwnerId())) {
			return true;
		}
		return tableGroup.getParticipants() != null
				&& tableGroup.getParticipants().stream().anyMatch(participant ->
						userId.equals(participant.getUserId())
								&& participant.getStatus() == ParticipantStatus.ACCEPTED
				);
	}

	private AccessDeniedException denied(String destination) {
		return new AccessDeniedException("STOMP subscription is not allowed: " + destination);
	}
}

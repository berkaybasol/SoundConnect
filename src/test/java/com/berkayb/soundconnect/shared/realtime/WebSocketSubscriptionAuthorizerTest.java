package com.berkayb.soundconnect.shared.realtime;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.pulse.redis.PulseRedisService;
import com.berkayb.soundconnect.modules.tablegroup.enums.ParticipantStatus;
import com.berkayb.soundconnect.modules.tablegroup.enums.TableGroupStatus;
import com.berkayb.soundconnect.modules.tablegroup.repository.TableGroupRepository;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WebSocketSubscriptionAuthorizerTest {

	@Mock TableGroupRepository tableGroupRepository;
	@Mock PulseRedisService pulseRedisService;
	@InjectMocks WebSocketSubscriptionAuthorizer authorizer;

	@Test
	void userCanOnlySubscribeToOwnDmAndNotificationTopics() {
		UUID userId = UUID.randomUUID();
		UserDetailsImpl principal = principal(userId);

		assertThatCode(() -> authorizer.authorize(principal, WebSocketChannels.dm(userId)))
				.doesNotThrowAnyException();
		assertThatCode(() -> authorizer.authorize(principal, WebSocketChannels.notificationsBadge(userId)))
				.doesNotThrowAnyException();
		assertThatThrownBy(() -> authorizer.authorize(principal, WebSocketChannels.dm(UUID.randomUUID())))
				.isInstanceOf(AccessDeniedException.class);
		assertThatThrownBy(() -> authorizer.authorize(principal, "/topic/dm/" + userId))
				.as("legacy slash-separated broker topics must stay denied")
				.isInstanceOf(AccessDeniedException.class);
	}

	@Test
	void acceptedTableGroupParticipantCanSubscribeButPendingParticipantCannot() {
		UUID groupId = UUID.randomUUID();
		UUID userId = UUID.randomUUID();
		when(tableGroupRepository.countOpenAccess(
				org.mockito.ArgumentMatchers.eq(groupId),
				org.mockito.ArgumentMatchers.eq(userId),
				org.mockito.ArgumentMatchers.eq(TableGroupStatus.ACTIVE),
				org.mockito.ArgumentMatchers.eq(ParticipantStatus.ACCEPTED),
				org.mockito.ArgumentMatchers.any(Instant.class)
		)).thenReturn(1L, 0L);

		assertThatCode(() -> authorizer.authorize(
				principal(userId),
				WebSocketChannels.tableGroup(groupId)
		)).doesNotThrowAnyException();

		assertThatThrownBy(() -> authorizer.authorize(
				principal(userId),
				WebSocketChannels.tableGroup(groupId)
		)).isInstanceOf(AccessDeniedException.class);
	}

	@Test
	void pulseSubscriptionRequiresCurrentRedisMembership() {
		UUID roomId = UUID.randomUUID();
		UUID userId = UUID.randomUUID();
		when(pulseRedisService.isUserInRoom(roomId, userId)).thenReturn(true, false);

		assertThatCode(() -> authorizer.authorize(
				principal(userId),
				WebSocketChannels.pulseVote(roomId)
		)).doesNotThrowAnyException();
		assertThatThrownBy(() -> authorizer.authorize(
				principal(userId),
				WebSocketChannels.pulseRoom(roomId)
		)).isInstanceOf(AccessDeniedException.class);
	}

	@Test
	void unknownDestinationIsDeniedByDefault() {
		assertThatThrownBy(() -> authorizer.authorize(
				principal(UUID.randomUUID()),
				"/topic/future-unreviewed-channel/data"
		)).isInstanceOf(AccessDeniedException.class);
	}

	@Test
	void clientSendIsRestrictedToFirstReleasePulseCommands() {
		UUID userId = UUID.randomUUID();
		UUID groupId = UUID.randomUUID();
		assertThatCode(() -> authorizer.authorizeSend(
				principal(userId),
				"/app/pulse/send"
		)).doesNotThrowAnyException();
		assertThatThrownBy(() -> authorizer.authorizeSend(
				principal(userId),
				"/app/table-group/" + groupId + "/chat"
		)).isInstanceOf(AccessDeniedException.class);
		assertThatThrownBy(() -> authorizer.authorizeSend(
				principal(userId),
				WebSocketChannels.dm(userId)
		)).isInstanceOf(AccessDeniedException.class);
	}

	private UserDetailsImpl principal(UUID userId) {
		User user = User.builder()
				.id(userId)
				.username("user-" + userId)
				.email(userId + "@example.com")
				.password("encoded")
				.status(UserStatus.ACTIVE)
				.emailVerified(true)
				.build();
		return new UserDetailsImpl(user);
	}
}

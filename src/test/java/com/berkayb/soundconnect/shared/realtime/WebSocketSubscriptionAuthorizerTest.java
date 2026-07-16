package com.berkayb.soundconnect.shared.realtime;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.pulse.redis.PulseRedisService;
import com.berkayb.soundconnect.modules.tablegroup.entity.TableGroup;
import com.berkayb.soundconnect.modules.tablegroup.entity.TableGroupParticipant;
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

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
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

		assertThatCode(() -> authorizer.authorize(principal, "/topic/dm/" + userId))
				.doesNotThrowAnyException();
		assertThatCode(() -> authorizer.authorize(principal, "/topic/notifications/" + userId + "/badge"))
				.doesNotThrowAnyException();
		assertThatThrownBy(() -> authorizer.authorize(principal, "/topic/dm/" + UUID.randomUUID()))
				.isInstanceOf(AccessDeniedException.class);
	}

	@Test
	void acceptedTableGroupParticipantCanSubscribeButPendingParticipantCannot() {
		UUID groupId = UUID.randomUUID();
		UUID userId = UUID.randomUUID();
		TableGroup tableGroup = activeTableGroup(userId, ParticipantStatus.ACCEPTED);
		when(tableGroupRepository.findById(groupId)).thenReturn(Optional.of(tableGroup));

		assertThatCode(() -> authorizer.authorize(
				principal(userId),
				"/topic/table_group/" + groupId
		)).doesNotThrowAnyException();

		tableGroup.getParticipants().iterator().next().setStatus(ParticipantStatus.PENDING);
		assertThatThrownBy(() -> authorizer.authorize(
				principal(userId),
				"/topic/table_group/" + groupId
		)).isInstanceOf(AccessDeniedException.class);
	}

	@Test
	void pulseSubscriptionRequiresCurrentRedisMembership() {
		UUID roomId = UUID.randomUUID();
		UUID userId = UUID.randomUUID();
		when(pulseRedisService.isUserInRoom(roomId, userId)).thenReturn(true, false);

		assertThatCode(() -> authorizer.authorize(
				principal(userId),
				"/topic/pulse/" + roomId + "/vote"
		)).doesNotThrowAnyException();
		assertThatThrownBy(() -> authorizer.authorize(
				principal(userId),
				"/topic/pulse/" + roomId
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
	void clientSendIsRestrictedToExplicitApplicationDestinations() {
		UUID userId = UUID.randomUUID();
		UUID groupId = UUID.randomUUID();
		when(tableGroupRepository.findById(groupId))
				.thenReturn(Optional.of(activeTableGroup(userId, ParticipantStatus.ACCEPTED)));

		assertThatCode(() -> authorizer.authorizeSend(
				principal(userId),
				"/app/table-group/" + groupId + "/chat"
		)).doesNotThrowAnyException();
		assertThatCode(() -> authorizer.authorizeSend(
				principal(userId),
				"/app/pulse/send"
		)).doesNotThrowAnyException();
		assertThatThrownBy(() -> authorizer.authorizeSend(
				principal(userId),
				"/topic/dm/" + userId
		)).isInstanceOf(AccessDeniedException.class);
	}

	private TableGroup activeTableGroup(UUID userId, ParticipantStatus participantStatus) {
		TableGroupParticipant participant = TableGroupParticipant.builder()
				.userId(userId)
				.status(participantStatus)
				.build();
		return TableGroup.builder()
				.ownerId(UUID.randomUUID())
				.status(TableGroupStatus.ACTIVE)
				.expiresAt(LocalDateTime.now().plusHours(1))
				.participants(new HashSet<>(Set.of(participant)))
				.build();
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

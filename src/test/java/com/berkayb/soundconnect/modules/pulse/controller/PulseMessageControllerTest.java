package com.berkayb.soundconnect.modules.pulse.controller;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.enums.ListenerVisibilityMode;
import com.berkayb.soundconnect.modules.profile.shared.identity.GhostListenerIdentity;
import com.berkayb.soundconnect.modules.profile.shared.identity.GhostListenerIdentityBatchResolver;
import com.berkayb.soundconnect.modules.pulse.dto.request.PulseMessageSendRequestDto;
import com.berkayb.soundconnect.modules.pulse.event.PulseMessageEvent;
import com.berkayb.soundconnect.modules.pulse.redis.PulseRedisService;
import com.berkayb.soundconnect.modules.pulse.service.PulseRoomService;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.support.UserEntityFinder;
import com.berkayb.soundconnect.shared.realtime.WebSocketChannels;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.transaction.annotation.Transactional;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PulseMessageControllerTest {
	@Mock SimpMessagingTemplate messagingTemplate;
	@Mock PulseRedisService pulseRedisService;
	@Mock PulseRoomService pulseRoomService;
	@Mock UserEntityFinder userEntityFinder;
	@Mock GhostListenerIdentityBatchResolver ghostListenerIdentityBatchResolver;
	@InjectMocks PulseMessageController controller;

	@Test
	void openSocketUsesCurrentUsernameResolvedByImmutableUserId() {
		UUID userId = UUID.randomUUID();
		UUID roomId = UUID.randomUUID();
		UserDetailsImpl stalePrincipal = new UserDetailsImpl(User.builder()
				.id(userId)
				.username("oldname")
				.build());
		var authentication = new UsernamePasswordAuthenticationToken(stalePrincipal, null, List.of());
		User currentUser = User.builder()
				.id(userId)
				.username("newname")
				.profilePicture("profile.jpg")
				.build();
		when(userEntityFinder.getUser(userId)).thenReturn(currentUser);
		when(pulseRedisService.isUserInRoom(roomId, userId)).thenReturn(true);
		when(pulseRoomService.getCooldownSeconds()).thenReturn(5);
		when(ghostListenerIdentityBatchResolver.resolve(anyCollection())).thenReturn(Map.of());

		controller.handlePulseMessage(
				new PulseMessageSendRequestDto(roomId, " hello "),
				authentication
		);

		ArgumentCaptor<PulseMessageEvent> eventCaptor = ArgumentCaptor.forClass(PulseMessageEvent.class);
		verify(messagingTemplate).convertAndSend(
				org.mockito.ArgumentMatchers.eq(WebSocketChannels.pulseRoom(roomId)),
				eventCaptor.capture()
		);
		assertThat(eventCaptor.getValue().getUserId()).isEqualTo(userId);
		assertThat(eventCaptor.getValue().getUsername()).isEqualTo("newname");
		assertThat(eventCaptor.getValue().getProfileImageUrl()).isEqualTo("profile.jpg");
		assertThat(eventCaptor.getValue().getContent()).isEqualTo("hello");
		assertThat(eventCaptor.getValue().getVisibilityMode()).isNull();
	}

	@Test
	void ghostListenerBroadcastUsesOnlyCanonicalListenerIdentity() {
		UUID userId = UUID.randomUUID();
		UUID roomId = UUID.randomUUID();
		UserDetailsImpl principal = new UserDetailsImpl(User.builder()
				.id(userId)
				.username("stale-principal-name")
				.profilePicture("stale-principal-avatar.jpg")
				.build());
		var authentication = new UsernamePasswordAuthenticationToken(principal, null, List.of());
		when(pulseRedisService.isUserInRoom(roomId, userId)).thenReturn(true);
		when(pulseRoomService.getCooldownSeconds()).thenReturn(5);
		when(ghostListenerIdentityBatchResolver.resolve(anyCollection())).thenReturn(Map.of(
				userId,
				new GhostListenerIdentity(
						userId,
						"canonical_listener",
						"listener-avatar.jpg",
						ListenerVisibilityMode.GHOST
				)
		));

		controller.handlePulseMessage(
				new PulseMessageSendRequestDto(roomId, "hello"),
				authentication
		);

		ArgumentCaptor<PulseMessageEvent> eventCaptor = ArgumentCaptor.forClass(PulseMessageEvent.class);
		verify(messagingTemplate).convertAndSend(
				eq(WebSocketChannels.pulseRoom(roomId)),
				eventCaptor.capture()
		);
		assertThat(eventCaptor.getValue().getUsername()).isEqualTo("canonical_listener");
		assertThat(eventCaptor.getValue().getProfileImageUrl()).isEqualTo("listener-avatar.jpg");
		assertThat(eventCaptor.getValue().getVisibilityMode()).isEqualTo(ListenerVisibilityMode.GHOST);
		verifyNoInteractions(userEntityFinder);
	}

	@Test
	void identityResolverFailureDropsMessageWithoutLegacyFallbackOrCooldown() {
		UUID userId = UUID.randomUUID();
		UUID roomId = UUID.randomUUID();
		UserDetailsImpl principal = new UserDetailsImpl(User.builder()
				.id(userId)
				.username("legacy-name")
				.profilePicture("legacy-avatar.jpg")
				.build());
		var authentication = new UsernamePasswordAuthenticationToken(principal, null, List.of());
		when(pulseRedisService.isUserInRoom(roomId, userId)).thenReturn(true);
		when(ghostListenerIdentityBatchResolver.resolve(anyCollection()))
				.thenThrow(new IllegalStateException("identity store unavailable"));

		controller.handlePulseMessage(
				new PulseMessageSendRequestDto(roomId, "hello"),
				authentication
		);

		verifyNoInteractions(messagingTemplate, userEntityFinder);
		verify(pulseRedisService, never()).setUserCooldown(eq(userId), org.mockito.ArgumentMatchers.anyInt());
	}

	@Test
	void visibilityMarkerIsSerializedOnlyForGhost() throws Exception {
		ObjectMapper mapper = new ObjectMapper();
		PulseMessageEvent standard = PulseMessageEvent.builder()
				.visibilityMode(ListenerVisibilityMode.STANDARD)
				.build();
		PulseMessageEvent ghost = PulseMessageEvent.builder()
				.visibilityMode(ListenerVisibilityMode.GHOST)
				.build();

		assertThat(standard.getVisibilityMode()).isNull();
		assertThat(mapper.writeValueAsString(standard)).doesNotContain("visibilityMode");
		assertThat(mapper.writeValueAsString(ghost)).contains("\"visibilityMode\":\"GHOST\"");
	}

	@Test
	void pulseMessageKeepsVisibilityLockUntilBroadcastEventIsBuilt() throws Exception {
		Transactional transaction = PulseMessageController.class
				.getMethod("handlePulseMessage", PulseMessageSendRequestDto.class, Principal.class)
				.getAnnotation(Transactional.class);

		assertThat(transaction).isNotNull();
		assertThat(transaction.readOnly()).isFalse();
	}
}

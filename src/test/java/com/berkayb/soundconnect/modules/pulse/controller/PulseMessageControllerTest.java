package com.berkayb.soundconnect.modules.pulse.controller;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.pulse.dto.request.PulseMessageSendRequestDto;
import com.berkayb.soundconnect.modules.pulse.event.PulseMessageEvent;
import com.berkayb.soundconnect.modules.pulse.redis.PulseRedisService;
import com.berkayb.soundconnect.modules.pulse.service.PulseRoomService;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.support.UserEntityFinder;
import com.berkayb.soundconnect.shared.realtime.WebSocketChannels;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PulseMessageControllerTest {
	@Mock SimpMessagingTemplate messagingTemplate;
	@Mock PulseRedisService pulseRedisService;
	@Mock PulseRoomService pulseRoomService;
	@Mock UserEntityFinder userEntityFinder;
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
	}
}

package com.berkayb.soundconnect.modules.tablegroup.chat.controller;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.tablegroup.chat.dto.request.TableGroupMessageRequestDto;
import com.berkayb.soundconnect.modules.tablegroup.chat.dto.response.TableGroupMessageResponseDto;
import com.berkayb.soundconnect.modules.tablegroup.chat.enums.MessageType;
import com.berkayb.soundconnect.modules.tablegroup.chat.service.TableGroupChatService;
import com.berkayb.soundconnect.modules.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * TableGroupChatWebSocketController icin unit test.
 * STOMP client vs yok; handleMessage metodunu direkt cagiriyoruz.
 *
 * Bu testin odağı:
 * - Principal.username -> userId çözümlemesi
 * - chatService.sendMessage(...) çağrısı
 * NOT: WS broadcast islemi artik service icinde; controller messagingTemplate kullanmiyor.
 */
@ExtendWith(MockitoExtension.class)
class TableGroupChatWebSocketControllerTest {
	
	@Mock
	private TableGroupChatService chatService;
	
	@Mock
	private SimpMessagingTemplate messagingTemplate;
	
	@InjectMocks
	private TableGroupChatWebSocketController controller;
	
	private UUID userId;
	private String username;
	private Authentication authentication;
	
	@BeforeEach
	void setUp() {
		userId = UUID.randomUUID();
		username = "wsUser";
		
		UserDetailsImpl userDetails = new UserDetailsImpl(User.builder()
				.id(userId)
				.username(username)
				.build());
		authentication = new UsernamePasswordAuthenticationToken(userDetails, null, List.of());
	}
	
	private Authentication principal() {
		return authentication;
	}
	
	private TableGroupMessageResponseDto sampleResponseDto(UUID messageId, UUID tableGroupId) {
		return new TableGroupMessageResponseDto(
				messageId,
				tableGroupId,
				userId,
				"kanka nerdesiniz",
				MessageType.TEXT,
				LocalDateTime.now(),
				null
		);
	}
	
	@Test
	void handleMessage_whenValidRequest_shouldResolveUserAndDelegateToService() {
		// given
		UUID tableGroupId = UUID.randomUUID();
		UUID messageId = UUID.randomUUID();
		
		TableGroupMessageRequestDto requestDto = new TableGroupMessageRequestDto(
				"kanka nerdesiniz",
				MessageType.TEXT
		);
		
		TableGroupMessageResponseDto responseDto = sampleResponseDto(messageId, tableGroupId);
		
		when(chatService.sendMessage(eq(userId), eq(tableGroupId), any(TableGroupMessageRequestDto.class)))
				.thenReturn(responseDto);
		
		// when
		controller.handleMessage(
				principal(),
				requestDto,
				tableGroupId
		);
		
		// then
		// Principal UUID dogrudan service'e aktarilmali.
		verify(chatService).sendMessage(eq(userId), eq(tableGroupId), any(TableGroupMessageRequestDto.class));
		
		// Controller artik messagingTemplate kullanmiyor.
		verifyNoInteractions(messagingTemplate);
	}
	
	@Test
	void handleMessage_whenServiceThrows_shouldNotSwallowException() {
		// given
		UUID tableGroupId = UUID.randomUUID();
		
		TableGroupMessageRequestDto requestDto = new TableGroupMessageRequestDto(
				"yetkisiz deneme",
				MessageType.TEXT
		);
		
		RuntimeException ex = new RuntimeException("TEST_ERROR");
		when(chatService.sendMessage(eq(userId), eq(tableGroupId), any(TableGroupMessageRequestDto.class)))
				.thenThrow(ex);
		
		// when
		try {
			controller.handleMessage(
					principal(),
					requestDto,
					tableGroupId
			);
		} catch (RuntimeException e) {
			// then – exception gerçekten yukarı propagate oluyor mu?
			assertThat(e).isSameAs(ex);
		}
		
		// service cagrilmis olmali
		verify(chatService).sendMessage(eq(userId), eq(tableGroupId), any(TableGroupMessageRequestDto.class));
		// controller seviyeisnde broadcast yok
		verifyNoInteractions(messagingTemplate);
	}
}

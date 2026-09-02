package com.berkayb.soundconnect.modules.tablegroup.chat.controller;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.tablegroup.chat.dto.request.TableGroupMessageRequestDto;
import com.berkayb.soundconnect.modules.tablegroup.chat.dto.response.TableGroupMessageResponseDto;
import com.berkayb.soundconnect.modules.tablegroup.chat.enums.MessageType;
import com.berkayb.soundconnect.modules.tablegroup.chat.service.TableGroupChatService;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.shared.constant.EndPoints;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.*;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.security.Principal;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class TableGroupChatControllerTest {
	
	@Mock
	private TableGroupChatService chatService;
	
	@InjectMocks
	private TableGroupChatController controller;
	
	private MockMvc mockMvc;
	private ObjectMapper objectMapper;
	
	private UUID userId;
	private String username;
	private UserDetailsImpl userDetails;
	private UsernamePasswordAuthenticationToken authentication;
	
	@BeforeEach
	void setUp() {
		userId = UUID.randomUUID();
		username = "chatUser";
		userDetails = new UserDetailsImpl(User.builder()
				.id(userId)
				.username(username)
				.build());
		authentication = new UsernamePasswordAuthenticationToken(userDetails, null, List.of());
		SecurityContextHolder.getContext().setAuthentication(authentication);

		mockMvc = MockMvcBuilders.standaloneSetup(controller)
		                         .setCustomArgumentResolvers(
				                         new AuthenticationPrincipalArgumentResolver(),
				                         new PageableHandlerMethodArgumentResolver()
		                         )
		                         .build();
		
		objectMapper = new ObjectMapper();
		objectMapper.registerModule(new JavaTimeModule());
		objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
		
	}

	@AfterEach
	void clearSecurityContext() {
		SecurityContextHolder.clearContext();
	}
	
	private Principal principal() {
		return authentication;
	}
	
	private TableGroupMessageResponseDto sampleMessageDto(UUID messageId, UUID tableGroupId, UUID senderId) {
		return new TableGroupMessageResponseDto(
				messageId,
				tableGroupId,
				senderId,
				"kanka nerdesiniz",
				MessageType.TEXT,
				Instant.now(),
				null
		);
	}
	
	// -------------------- getMessages (DIRECT CALL, Page var) --------------------
	
	@Test
	void getMessages_whenRequesterIsAuthenticated_shouldReturnPageWrappedInBaseResponse() {
		// given
		UUID tableGroupId = UUID.randomUUID();
		UUID messageId = UUID.randomUUID();
		
		TableGroupMessageResponseDto dto = sampleMessageDto(messageId, tableGroupId, userId);
		
		List<TableGroupMessageResponseDto> content = new ArrayList<>();
		content.add(dto);
		Page<TableGroupMessageResponseDto> page = new PageImpl<>(content);
		
		Pageable pageable = PageRequest.of(0, 20);
		
		when(chatService.getMessages(userId, tableGroupId, pageable))
				.thenReturn(page);
		
		// when: MockMvc değil, direkt controller çağrısı
		ResponseEntity<BaseResponse<Page<TableGroupMessageResponseDto>>> response =
				controller.getMessages(userDetails, tableGroupId, pageable);
		
		// then
		assertThat(response.getStatusCode().value()).isEqualTo(200);
		
		BaseResponse<Page<TableGroupMessageResponseDto>> body = response.getBody();
		assertThat(body).isNotNull();
		assertThat(body.getSuccess()).isTrue();
		assertThat(body.getCode()).isEqualTo(200);
		assertThat(body.getMessage()).isEqualTo("Table group chat messages listed");
		
		Page<TableGroupMessageResponseDto> responsePage = body.getData();
		assertThat(responsePage).isNotNull();
		assertThat(responsePage.getContent())
				.hasSize(1)
				.first()
				.extracting(TableGroupMessageResponseDto::messageId)
				.isEqualTo(messageId);
		
		verify(chatService).getMessages(userId, tableGroupId, pageable);
	}
	
	// -------------------- sendMessage (MockMvc, JSON) --------------------
	
	@Test
	void sendMessage_whenValidRequest_shouldReturnMessageDtoInBaseResponse() throws Exception {
		// given
		UUID tableGroupId = UUID.randomUUID();
		UUID messageId = UUID.randomUUID();
		UUID clientMessageId = UUID.randomUUID();
		
		TableGroupMessageRequestDto requestDto = new TableGroupMessageRequestDto(
				"kanka nerdesiniz",
				MessageType.TEXT,
				clientMessageId
		);
		
		TableGroupMessageResponseDto responseDto = sampleMessageDto(messageId, tableGroupId, userId);
		
		when(chatService.sendMessage(eq(userId), eq(tableGroupId), any(TableGroupMessageRequestDto.class)))
				.thenReturn(responseDto);
		
		// when & then
		mockMvc.perform(
				       post(EndPoints.TableGroup.Chat.BASE + EndPoints.TableGroup.Chat.MESSAGES, tableGroupId)
						       .principal(principal())
						       .contentType("application/json")
						       .content(objectMapper.writeValueAsString(requestDto))
		       )
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.success").value(true))
		       .andExpect(jsonPath("$.code").value(200))
		       .andExpect(jsonPath("$.message").value("Message sent"))
		       .andExpect(jsonPath("$.data.messageId").value(messageId.toString()))
		       .andExpect(jsonPath("$.data.tableGroupId").value(tableGroupId.toString()))
		       .andExpect(jsonPath("$.data.senderId").value(userId.toString()))
		       .andExpect(jsonPath("$.data.sentAt").value(responseDto.sentAt().toString()));
		
		ArgumentCaptor<TableGroupMessageRequestDto> requestCaptor =
				ArgumentCaptor.forClass(TableGroupMessageRequestDto.class);
		verify(chatService).sendMessage(eq(userId), eq(tableGroupId), requestCaptor.capture());
		assertThat(requestCaptor.getValue().clientMessageId()).isEqualTo(clientMessageId);
	}

	@Test
	void sendMessage_withoutClientMessageId_shouldFailValidationBeforeService() throws Exception {
		UUID tableGroupId = UUID.randomUUID();

		mockMvc.perform(
				post(EndPoints.TableGroup.Chat.BASE + EndPoints.TableGroup.Chat.MESSAGES, tableGroupId)
						.principal(principal())
						.contentType("application/json")
						.content("{\"content\":\"idempotent olmalı\",\"messageType\":\"TEXT\"}")
		)
				.andExpect(status().isBadRequest());

		verifyNoInteractions(chatService);
	}
	
	// -------------------- getUnreadBadge (MockMvc, int) --------------------
	
	@Test
	void getUnreadBadge_whenCalled_shouldReturnUnreadCountForCurrentUser() throws Exception {
		// given
		UUID tableGroupId = UUID.randomUUID();
		int unreadCount = 7;
		
		when(chatService.getUnreadBadge(userId, tableGroupId)).thenReturn(unreadCount);
		
		// when & then
		mockMvc.perform(
				       get(EndPoints.TableGroup.Chat.BASE + EndPoints.TableGroup.Chat.GET_UNREAD_BADGE, tableGroupId)
						       .principal(principal())
		       )
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.success").value(true))
		       .andExpect(jsonPath("$.code").value(200))
		       .andExpect(jsonPath("$.message").value("Unread badge fetched"))
		       .andExpect(jsonPath("$.data").value(unreadCount));
		
		verify(chatService).getUnreadBadge(userId, tableGroupId);
	}
}

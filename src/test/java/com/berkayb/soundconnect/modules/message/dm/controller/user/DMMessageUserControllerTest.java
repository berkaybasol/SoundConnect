package com.berkayb.soundconnect.modules.message.dm.controller.user;

import com.berkayb.soundconnect.modules.message.dm.dto.request.DMMessageRequestDto;
import com.berkayb.soundconnect.modules.message.dm.dto.response.DMMessageResponseDto;
import com.berkayb.soundconnect.modules.message.dm.entity.DMConversation;
import com.berkayb.soundconnect.modules.message.dm.repository.DMConversationRepository;
import com.berkayb.soundconnect.modules.message.dm.service.DMMessageService;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.berkayb.soundconnect.shared.constant.EndPoints;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import jakarta.annotation.Resource;

import java.security.Principal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = DMMessageUserController.class)
@AutoConfigureMockMvc(addFilters = false)
@Tag("web")
class DMMessageUserControllerTest {
	
	// --- Eğer EndPoints farklıysa burayı güncelle ---
	static final String BASE = EndPoints.DM.USER_BASE;
	static final String PATH_LIST = EndPoints.DM.MESSAGE_LIST;
	static final String PATH_SEND = EndPoints.DM.MESSAGE_SEND;
	static final String PATH_MARK = EndPoints.DM.MESSAGE_MARK_READ;
	
	@Resource MockMvc mockMvc;
	@Resource ObjectMapper objectMapper;
	
	@MockitoBean
	DMMessageService messageService;
	@MockitoBean DMConversationRepository conversationRepository;
	@MockitoBean UserRepository userRepository;
	
	@MockitoBean
	com.berkayb.soundconnect.auth.security.JwtAuthenticationFilter jwtAuthenticationFilter;
	
	@MockitoBean
	com.berkayb.soundconnect.auth.security.JwtTokenProvider jwtTokenProvider;
	
	@Test
	@DisplayName("GET /messages/conversation/{id} → participant doğrulaması sonrası mesaj listesi dönmeli")
	void listByConversation_ok() throws Exception {
		String username = "berkay";
		UUID currentUserId = UUID.randomUUID();
		UUID otherId = UUID.randomUUID();
		UUID conversationId = UUID.randomUUID();
		
		Principal p = () -> username;
		
		// user lookup
		User u = new User();
		u.setId(currentUserId);
		u.setUsername(username);
		when(userRepository.findByUsername(username)).thenReturn(Optional.of(u));
		
		// ensureParticipant için conv mevcut ve currentUser participant
		DMConversation conv = DMConversation.builder()
		                                    .id(conversationId)
		                                    .userAId(currentUserId)
		                                    .userBId(otherId)
		                                    .build();
		when(conversationRepository.findById(conversationId)).thenReturn(Optional.of(conv));
		
		// service response
		var m1 = new DMMessageResponseDto(UUID.randomUUID(), conversationId, currentUserId, otherId,
		                                  "a", "text", LocalDateTime.now(), null, null);
		var m2 = new DMMessageResponseDto(UUID.randomUUID(), conversationId, otherId, currentUserId,
		                                  "b", "text", LocalDateTime.now(), null, null);
		when(messageService.getMessagesByConversationId(conversationId)).thenReturn(List.of(m1, m2));
		
		mockMvc.perform(get(BASE + PATH_LIST, conversationId).principal(p))
		       .andExpect(status().isOk())
		       .andExpect(content().contentType(MediaType.APPLICATION_JSON))
		       .andExpect(jsonPath("$.success").value(true))
		       .andExpect(jsonPath("$.code").value(200))
		       .andExpect(jsonPath("$.data", hasSize(2)))
		       .andExpect(jsonPath("$.data[0].content").value("a"))
		       .andExpect(jsonPath("$.data[1].content").value("b"));
	}
	
	@Test
	@DisplayName("POST /messages → servis send çağrısı yapılmalı, DTO dönmeli")
	void send_ok() throws Exception {
		String username = "berkay";
		UUID currentUserId = UUID.randomUUID();
		UUID conversationId = UUID.randomUUID();
		UUID recipientId = UUID.randomUUID();
		
		Principal p = () -> username;
		
		User u = new User();
		u.setId(currentUserId);
		u.setUsername(username);
		when(userRepository.findByUsername(username)).thenReturn(Optional.of(u));
		
		DMMessageRequestDto req = new DMMessageRequestDto(conversationId, recipientId, "hey", "text");
		
		DMMessageResponseDto resp = new DMMessageResponseDto(
				UUID.randomUUID(), conversationId, currentUserId, recipientId,
				"hey", "text", LocalDateTime.now(), null, null
		);
		when(messageService.sendMessage(org.mockito.ArgumentMatchers.any(DMMessageRequestDto.class), eq(currentUserId)))
				.thenReturn(resp);
		
		
		mockMvc.perform(post(BASE + PATH_SEND)
				                .principal(p)
				                .contentType(MediaType.APPLICATION_JSON)
				                .content(objectMapper.writeValueAsString(req)))
		       .andExpect(status().isOk())
		       .andExpect(content().contentType(MediaType.APPLICATION_JSON))
		       .andExpect(jsonPath("$.success").value(true))
		       .andExpect(jsonPath("$.data.content").value("hey"));
		
		// ArgCaptor ile gönderilen request’i de doğrulayalım
		ArgumentCaptor<DMMessageRequestDto> captor = ArgumentCaptor.forClass(DMMessageRequestDto.class);
		verify(messageService).sendMessage(captor.capture(), eq(currentUserId));
		DMMessageRequestDto captured = captor.getValue();
		org.assertj.core.api.Assertions.assertThat(captured.content()).isEqualTo("hey");
		org.assertj.core.api.Assertions.assertThat(captured.recipientId()).isEqualTo(recipientId);
		org.assertj.core.api.Assertions.assertThat(captured.conversationId()).isEqualTo(conversationId);
	}
	
	@Test
	@DisplayName("PATCH /messages/{id}/read → servis markMessageAsRead çağrısı yapılmalı")
	void markRead_ok() throws Exception {
		String username = "berkay";
		UUID currentUserId = UUID.randomUUID();
		UUID messageId = UUID.randomUUID();
		
		Principal p = () -> username;
		
		User u = new User();
		u.setId(currentUserId);
		u.setUsername(username);
		when(userRepository.findByUsername(username)).thenReturn(Optional.of(u));
		
		mockMvc.perform(patch(BASE + PATH_MARK, messageId).principal(p))
		       .andExpect(status().isOk())
		       .andExpect(content().contentType(MediaType.APPLICATION_JSON))
		       .andExpect(jsonPath("$.success").value(true))
		       .andExpect(jsonPath("$.message", containsString("marked as read")));
		
		verify(messageService).markMessageAsRead(messageId, currentUserId);
	}
}
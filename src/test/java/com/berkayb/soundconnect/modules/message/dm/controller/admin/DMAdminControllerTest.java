package com.berkayb.soundconnect.modules.message.dm.controller.admin;

import com.berkayb.soundconnect.modules.message.dm.dto.response.DMMessageResponseDto;
import com.berkayb.soundconnect.modules.message.dm.entity.DMConversation;
import com.berkayb.soundconnect.modules.message.dm.entity.DMMessage;
import com.berkayb.soundconnect.modules.message.dm.repository.DMConversationRepository;
import com.berkayb.soundconnect.modules.message.dm.repository.DMMessageRepository;
import com.berkayb.soundconnect.shared.constant.EndPoints;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;

import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = DMAdminController.class)
@AutoConfigureMockMvc(addFilters = false)
@Tag("web")
class DMAdminControllerTest {
	
	@Resource MockMvc mockMvc;
	@Resource ObjectMapper objectMapper;
	
	@MockitoBean
	DMConversationRepository conversationRepository;
	@MockitoBean DMMessageRepository messageRepository;
	
	// Security bean’leri mockla (context açılışında gerekebilir)
	@MockitoBean
	com.berkayb.soundconnect.auth.security.JwtAuthenticationFilter jwtAuthenticationFilter;
	@MockitoBean com.berkayb.soundconnect.auth.security.JwtTokenProvider jwtTokenProvider;
	
	// Endpoints
	static final String BASE = EndPoints.DM.ADMIN_BASE;
	static final String PATH_CONVS = EndPoints.DM.ADMIN_CONVERSATIONS;            // "/conversations"
	static final String PATH_CONV_BY_ID = EndPoints.DM.ADMIN_CONVERSATION_BY_ID;  // "/{conversationId}"
	static final String PATH_MSGS = EndPoints.DM.ADMIN_MESSAGES;                  // "/messages"
	static final String PATH_DELETE_MSG = EndPoints.DM.ADMIN_DELETE_MESSAGE;      // "/{conversationId}/messages/{messageId}"
	
	@Test
	@DisplayName("GET /admin/dm/conversations → tüm konuşmalar (admin preview) dönmeli")
	void listConversations_ok() throws Exception {
		UUID c1 = UUID.randomUUID();
		UUID c2 = UUID.randomUUID();
		UUID a = UUID.randomUUID();
		UUID b = UUID.randomUUID();
		
		var conv1 = DMConversation.builder().id(c1).userAId(a).userBId(b).lastMessageAt(LocalDateTime.now().minusMinutes(5)).build();
		var conv2 = DMConversation.builder().id(c2).userAId(b).userBId(a).lastMessageAt(LocalDateTime.now()).build();
		
		when(conversationRepository.findAll()).thenReturn(List.of(conv1, conv2));
		
		// Admin preview helper’ı son mesajı almak için repo’yu kullanıyor:
		var lastMsg1 = DMMessage.builder().id(UUID.randomUUID()).conversationId(c1)
		                        .senderId(a).recipientId(b).content("m1").messageType("text").build();
		var lastMsg2 = DMMessage.builder().id(UUID.randomUUID()).conversationId(c2)
		                        .senderId(b).recipientId(a).content("m2").messageType("text").build();
		
		when(messageRepository.findTopByConversationIdOrderByCreatedAtDesc(c1)).thenReturn(Optional.of(lastMsg1));
		when(messageRepository.findTopByConversationIdOrderByCreatedAtDesc(c2)).thenReturn(Optional.of(lastMsg2));
		
		mockMvc.perform(get(BASE + PATH_CONVS))
		       .andExpect(status().isOk())
		       .andExpect(content().contentType(MediaType.APPLICATION_JSON))
		       .andExpect(jsonPath("$.success").value(true))
		       .andExpect(jsonPath("$.data", hasSize(2)))
		       // sıralama lastMessageAt DESC (c2 önde)
		       .andExpect(jsonPath("$.data[0].conversationId").value(c2.toString()))
		       .andExpect(jsonPath("$.data[0].lastMessageContent").value("m2"))
		       .andExpect(jsonPath("$.data[1].conversationId").value(c1.toString()))
		       .andExpect(jsonPath("$.data[1].lastMessageContent").value("m1"));
	}
	
	@Test
	@DisplayName("GET /admin/dm/{conversationId} → konuşma detay")
	void getConversationById_ok() throws Exception {
		UUID convId = UUID.randomUUID();
		var conv = DMConversation.builder().id(convId).userAId(UUID.randomUUID()).userBId(UUID.randomUUID()).build();
		when(conversationRepository.findById(convId)).thenReturn(Optional.of(conv));
		
		mockMvc.perform(get(BASE + PATH_CONV_BY_ID, convId))
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.success").value(true))
		       .andExpect(jsonPath("$.data.id").value(convId.toString()));
	}
	
	@Test
	@DisplayName("DELETE /admin/dm/{conversationId} → konuşma silinsin")
	void deleteConversation_ok() throws Exception {
		UUID convId = UUID.randomUUID();
		when(conversationRepository.existsById(convId)).thenReturn(true);
		
		mockMvc.perform(delete(BASE + PATH_CONV_BY_ID, convId))
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.success").value(true))
		       .andExpect(jsonPath("$.message", containsString("deleted")));
		
		verify(conversationRepository).deleteById(convId);
	}
	
	@Test
	@DisplayName("GET /admin/dm/messages?conversationId=... → mesaj listesi (admin)")
	void getMessages_ok() throws Exception {
		UUID convId = UUID.randomUUID();
		when(conversationRepository.findById(convId)).thenReturn(Optional.of(
				DMConversation.builder().id(convId).userAId(UUID.randomUUID()).userBId(UUID.randomUUID()).build()
		));
		
		var m1 = DMMessage.builder().id(UUID.randomUUID()).conversationId(convId).senderId(UUID.randomUUID())
		                  .recipientId(UUID.randomUUID()).content("a").messageType("text").build();
		var m2 = DMMessage.builder().id(UUID.randomUUID()).conversationId(convId).senderId(UUID.randomUUID())
		                  .recipientId(UUID.randomUUID()).content("b").messageType("text").build();
		
		when(messageRepository.findByConversationIdOrderByCreatedAtAsc(convId)).thenReturn(List.of(m1, m2));
		
		mockMvc.perform(get(BASE + PATH_MSGS).param("conversationId", convId.toString()))
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.success").value(true))
		       .andExpect(jsonPath("$.data", hasSize(2)))
		       .andExpect(jsonPath("$.data[0].content").value("a"))
		       .andExpect(jsonPath("$.data[1].content").value("b"));
	}
	
	@Test
	@DisplayName("DELETE /admin/dm/{conversationId}/messages/{messageId} → mesaj silinsin")
	void deleteMessage_ok() throws Exception {
		UUID convId = UUID.randomUUID();
		UUID msgId = UUID.randomUUID();
		
		var msg = DMMessage.builder().id(msgId).conversationId(convId).senderId(UUID.randomUUID())
		                   .recipientId(UUID.randomUUID()).content("x").messageType("text").build();
		
		when(messageRepository.findById(msgId)).thenReturn(Optional.of(msg));
		
		mockMvc.perform(delete(BASE + PATH_DELETE_MSG, convId, msgId))
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.success").value(true))
		       .andExpect(jsonPath("$.message", containsString("deleted")));
		
		ArgumentCaptor<DMMessage> captor = ArgumentCaptor.forClass(DMMessage.class);
		verify(messageRepository).delete(captor.capture());
		DMMessage deleted = captor.getValue();
		org.assertj.core.api.Assertions.assertThat(deleted.getId()).isEqualTo(msgId);
	}
}
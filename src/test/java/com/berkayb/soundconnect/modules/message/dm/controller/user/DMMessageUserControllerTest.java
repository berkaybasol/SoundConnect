// src/test/java/com/berkayb/soundconnect/modules/message/dm/controller/user/DMMessageUserControllerTest.java
package com.berkayb.soundconnect.modules.message.dm.controller.user;

import com.berkayb.soundconnect.SoundConnectApplication;
import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.message.dm.dto.request.DMMessageRequestDto;
import com.berkayb.soundconnect.modules.message.dm.entity.DMConversation;
import com.berkayb.soundconnect.modules.message.dm.entity.DMMessage;
import com.berkayb.soundconnect.modules.message.dm.repository.DMConversationRepository;
import com.berkayb.soundconnect.modules.message.dm.repository.DMMessageRepository;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.AuthProvider;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.berkayb.soundconnect.shared.constant.EndPoints;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = SoundConnectApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Tag("web")
class DMMessageUserControllerTest {
	
	@Autowired MockMvc mockMvc;
	@Autowired UserRepository userRepo;
	@Autowired DMConversationRepository convRepo;
	@Autowired DMMessageRepository msgRepo;
	
	@MockitoBean RabbitTemplate rabbitTemplate;
	
	private final ObjectMapper om = new ObjectMapper();
	
	User a, b;
	DMConversation conv;
	
	@BeforeEach
	void setup() {
		msgRepo.deleteAll();
		convRepo.deleteAll();
		userRepo.deleteAll();
		
		a = userRepo.save(User.builder()
		                      .username("a_"+UUID.randomUUID())
		                      .email("a_"+UUID.randomUUID()+"@test.local")
		                      .password("x")
		                      .provider(AuthProvider.LOCAL)
		                      .emailVerified(true)
		                      .build());
		b = userRepo.save(User.builder()
		                      .username("b_"+UUID.randomUUID())
		                      .email("b_"+UUID.randomUUID()+"@test.local")
		                      .password("x")
		                      .provider(AuthProvider.LOCAL)
		                      .emailVerified(true)
		                      .build());
		
		conv = convRepo.save(DMConversation.builder().userAId(a.getId()).userBId(b.getId()).build());
		
		var principal = new UserDetailsImpl(a);
		var auth = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
		SecurityContextHolder.getContext().setAuthentication(auth);
	}
	
	@Test
	void listByConversation_ok() throws Exception {
		// participant kontrolü geçsin diye a bu konuşmada var
		msgRepo.save(DMMessage.builder()
		                      .conversationId(conv.getId())
		                      .senderId(a.getId()).recipientId(b.getId())
		                      .content("hello").messageType("text").build());
		
		mockMvc.perform(get(EndPoints.DM.USER_BASE + EndPoints.DM.MESSAGE_LIST, conv.getId())
				                .principal(() -> a.getUsername()))
		       .andDo(print())
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.data", hasSize(1)))
		       .andExpect(jsonPath("$.data[0].content").value("hello"));
	}
	
	@Test
	void send_ok() throws Exception {
		var body = om.writeValueAsString(new DMMessageRequestDto(
				conv.getId(), b.getId(), "hey", "text"
		));
		
		mockMvc.perform(post(EndPoints.DM.USER_BASE + EndPoints.DM.MESSAGE_SEND)
				                .principal(() -> a.getUsername())
				                .contentType(MediaType.APPLICATION_JSON)
				                .content(body))
		       .andDo(print())
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.data.content").value("hey"))
		       .andExpect(jsonPath("$.data.recipientId").value(b.getId().toString()));
		
		List<DMMessage> all = msgRepo.findAll();
		org.assertj.core.api.Assertions.assertThat(all).hasSize(1);
	}
	
	@Test
	void markRead_ok() throws Exception {
		DMMessage msg = msgRepo.save(DMMessage.builder()
		                                      .conversationId(conv.getId())
		                                      .senderId(a.getId()).recipientId(b.getId())
		                                      .content("to B").messageType("text").build());
		
		// principal’ı b yap (okuyan b olacak)
		var principalB = new UserDetailsImpl(b);
		var authB = new UsernamePasswordAuthenticationToken(principalB, null, principalB.getAuthorities());
		SecurityContextHolder.getContext().setAuthentication(authB);
		
		mockMvc.perform(patch(EndPoints.DM.USER_BASE + EndPoints.DM.MESSAGE_MARK_READ, msg.getId())
				                .principal(() -> b.getUsername()))
		       .andDo(print())
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.success").value(true));
		
		DMMessage refreshed = msgRepo.findById(msg.getId()).orElseThrow();
		org.assertj.core.api.Assertions.assertThat(refreshed.getReadAt()).isNotNull();
	}
}
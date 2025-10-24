// src/test/java/com/berkayb/soundconnect/modules/message/dm/controller/user/DMConversationUserControllerTest.java
package com.berkayb.soundconnect.modules.message.dm.controller.user;

import com.berkayb.soundconnect.SoundConnectApplication;
import com.berkayb.soundconnect.auth.otp.service.OtpService;
import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.message.dm.service.DMConversationService;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.AuthProvider;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.berkayb.soundconnect.shared.constant.EndPoints;
import com.berkayb.soundconnect.shared.mail.adapter.MailSenderClient;
import com.berkayb.soundconnect.shared.mail.helper.MailJobHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = SoundConnectApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Tag("web")
class DMConversationUserControllerTest {
	
	@Autowired MockMvc mockMvc;
	
	@Autowired UserRepository userRepo;
	@Autowired DMConversationService conversationService;
	
	// MailProducerImpl yüzünden gerekecek
	@MockitoBean RabbitTemplate rabbitTemplate;
	@MockitoBean
	RedisConnectionFactory redisConnectionFactory;
	@MockitoBean
	RedisTemplate<String, String> redisTemplate;
	@MockitoBean
	OtpService otpService;
	
	@MockitoBean
	StringRedisTemplate stringRedisTemplate;
	
	@MockitoBean
	MailJobHelper mailJobHelper;
	
	@MockitoBean
	MailSenderClient mailSenderClient;
	
	@MockitoBean
	org.springframework.amqp.support.converter.Jackson2JsonMessageConverter jackson2JsonMessageConverter;
	
	// Bazı config'ler ConnectionFactory isterse güvence:
	@MockitoBean(name = "rabbitConnectionFactory")
	org.springframework.amqp.rabbit.connection.CachingConnectionFactory rabbitConnectionFactory;
	
	
	// İstersen tüketiciyi de körle (gerekmeden geçmesi lazım ama garanti):
	@MockitoBean
	com.berkayb.soundconnect.shared.mail.consumer.DlqMailJobConsumer dlqMailJobConsumer;
	
	User me, other;
	
	@BeforeEach
	void setup() {
		userRepo.deleteAll();
		
		me = userRepo.save(User.builder()
		                       .username("me_"+UUID.randomUUID())
		                       .email("me_"+UUID.randomUUID()+"@test.local")
		                       .password("x")
		                       .provider(AuthProvider.LOCAL)
		                       .emailVerified(true)
		                       .build());
		other = userRepo.save(User.builder()
		                          .username("other_"+UUID.randomUUID())
		                          .email("other_"+UUID.randomUUID()+"@test.local")
		                          .password("x")
		                          .provider(AuthProvider.LOCAL)
		                          .emailVerified(true)
		                          .build());
		
		var principal = new UserDetailsImpl(me);
		var auth = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
		SecurityContextHolder.getContext().setAuthentication(auth);
	}
	
	@Test
	void getOrCreateBetween_ok() throws Exception {
		mockMvc.perform(post(EndPoints.DM.USER_BASE + EndPoints.DM.CONVERSATION_BETWEEN)
				                .param("otherUserId", other.getId().toString())
				                .principal(() -> me.getUsername()))
		       .andDo(print())
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.success").value(true))
		       .andExpect(jsonPath("$.data").isNotEmpty());
	}
	
	@Test
	void myConversations_ok() throws Exception {
		// hazır olması için önce conversation yarat
		conversationService.getOrCreateConversation(me.getId(), other.getId());
		
		mockMvc.perform(get(EndPoints.DM.USER_BASE + EndPoints.DM.CONVERSATION_LIST)
				                .principal(() -> me.getUsername()))
		       .andDo(print())
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.success").value(true))
		       .andExpect(jsonPath("$.data", hasSize(greaterThanOrEqualTo(1))));
	}
}
// src/test/java/com/berkayb/soundconnect/modules/message/dm/service/DMConversationServiceImplTest.java
package com.berkayb.soundconnect.modules.message.dm.service;

import com.berkayb.soundconnect.SoundConnectApplication;
import com.berkayb.soundconnect.modules.message.dm.dto.request.DMMessageRequestDto;
import com.berkayb.soundconnect.modules.message.dm.dto.response.DMConversationPreviewResponseDto;
import com.berkayb.soundconnect.modules.message.dm.entity.DMConversation;
import com.berkayb.soundconnect.modules.message.dm.repository.DMConversationRepository;
import com.berkayb.soundconnect.modules.message.dm.repository.DMMessageRepository;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.AuthProvider;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(classes = SoundConnectApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Tag("service")
class DMConversationServiceImplTest {
	
	@MockitoBean RabbitTemplate rabbitTemplate;
	
	@org.springframework.beans.factory.annotation.Autowired
	DMConversationService conversationService;
	@org.springframework.beans.factory.annotation.Autowired
	DMMessageService messageService;
	@org.springframework.beans.factory.annotation.Autowired
	DMConversationRepository convRepo;
	@org.springframework.beans.factory.annotation.Autowired
	DMMessageRepository msgRepo;
	@org.springframework.beans.factory.annotation.Autowired
	UserRepository userRepo;
	
	User a, b;
	
	@BeforeEach
	void setup() {
		msgRepo.deleteAll();
		convRepo.deleteAll();
		userRepo.deleteAll();
		
		a = userRepo.save(User.builder()
		                      .username("a_" + UUID.randomUUID())
		                      .email("a_" + UUID.randomUUID() + "@test.local")
		                      .password("x")
		                      .provider(AuthProvider.LOCAL)
		                      .emailVerified(true)
		                      .build());
		b = userRepo.save(User.builder()
		                      .username("b_" + UUID.randomUUID())
		                      .email("b_" + UUID.randomUUID() + "@test.local")
		                      .password("x")
		                      .provider(AuthProvider.LOCAL)
		                      .emailVerified(true)
		                      .build());
	}
	
	@Test
	void getOrCreateConversation_should_create_then_return_same() {
		UUID id1 = conversationService.getOrCreateConversation(a.getId(), b.getId());
		UUID id2 = conversationService.getOrCreateConversation(b.getId(), a.getId());
		assertThat(id1).isEqualTo(id2);
	}
	
	@Test
	void getOrCreateConversation_self_should_throw() {
		assertThatThrownBy(() -> conversationService.getOrCreateConversation(a.getId(), a.getId()))
				.isInstanceOf(SoundConnectException.class);
	}
	
	@Test
	void getAllConversationsForUser_should_return_preview_with_lastMessage() {
		DMConversation conv = convRepo.save(DMConversation.builder()
		                                                  .userAId(a.getId()).userBId(b.getId()).build());
		
		messageService.sendMessage(new DMMessageRequestDto(conv.getId(), b.getId(), "hey", "text"), a.getId());
		
		List<DMConversationPreviewResponseDto> list = conversationService.getAllConversationsForUser(a.getId());
		assertThat(list).hasSize(1);
		DMConversationPreviewResponseDto p = list.get(0);
		assertThat(p.otherUserId()).isEqualTo(b.getId());
		assertThat(p.lastMessageContent()).isEqualTo("hey");
		assertThat(p.lastMessageSenderId()).isEqualTo(a.getId());
	}
}
// src/test/java/com/berkayb/soundconnect/modules/message/dm/service/DMMessageServiceImplTest.java
package com.berkayb.soundconnect.modules.message.dm.service;

import com.berkayb.soundconnect.SoundConnectApplication;
import com.berkayb.soundconnect.modules.message.dm.dto.request.DMMessageRequestDto;
import com.berkayb.soundconnect.modules.message.dm.dto.response.DMMessageResponseDto;
import com.berkayb.soundconnect.modules.message.dm.entity.DMConversation;
import com.berkayb.soundconnect.modules.message.dm.entity.DMMessage;
import com.berkayb.soundconnect.modules.message.dm.repository.DMConversationRepository;
import com.berkayb.soundconnect.modules.message.dm.repository.DMMessageRepository;
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
class DMMessageServiceImplTest {
	
	@MockitoBean RabbitTemplate rabbitTemplate;
	
	@org.springframework.beans.factory.annotation.Autowired
	DMMessageService service;
	@org.springframework.beans.factory.annotation.Autowired
	DMConversationRepository convRepo;
	@org.springframework.beans.factory.annotation.Autowired
	DMMessageRepository msgRepo;
	
	UUID uA, uB;
	DMConversation conv;
	
	@BeforeEach
	void setup() {
		msgRepo.deleteAll();
		convRepo.deleteAll();
		
		uA = UUID.randomUUID();
		uB = UUID.randomUUID();
		conv = convRepo.save(DMConversation.builder().userAId(uA).userBId(uB).build());
	}
	
	@Test
	void sendMessage_and_getMessages_should_work() {
		DMMessageResponseDto r1 = service.sendMessage(new DMMessageRequestDto(
				conv.getId(), uB, "hello", "text"
		), uA);
		assertThat(r1).isNotNull();
		assertThat(r1.conversationId()).isEqualTo(conv.getId());
		assertThat(r1.senderId()).isEqualTo(uA);
		assertThat(r1.recipientId()).isEqualTo(uB);
		assertThat(r1.content()).isEqualTo("hello");
		
		DMMessageResponseDto r2 = service.sendMessage(new DMMessageRequestDto(
				conv.getId(), uA, "hi", "text"
		), uB);
		
		List<DMMessageResponseDto> list = service.getMessagesByConversationId(conv.getId());
		assertThat(list).hasSize(2);
		assertThat(list.get(0).content()).isEqualTo("hello");
		assertThat(list.get(1).content()).isEqualTo("hi");
		
		DMConversation refreshed = convRepo.findById(conv.getId()).orElseThrow();
		assertThat(refreshed.getLastMessageAt()).isNotNull();
	}
	
	@Test
	void sendMessage_nonParticipant_should_throw() {
		UUID stranger = UUID.randomUUID();
		assertThatThrownBy(() -> service.sendMessage(
				new DMMessageRequestDto(conv.getId(), uB, "x", "text"), stranger
		)).isInstanceOf(SoundConnectException.class);
	}
	
	@Test
	void sendMessage_to_self_should_throw() {
		assertThatThrownBy(() -> service.sendMessage(
				new DMMessageRequestDto(conv.getId(), uA, "x", "text"), uA
		)).isInstanceOf(SoundConnectException.class);
	}
	
	@Test
	void markMessageAsRead_onlyRecipient_can_read() {
		DMMessageResponseDto sent = service.sendMessage(new DMMessageRequestDto(
				conv.getId(), uB, "to B", "text"
		), uA);
		
		// wrong user
		assertThatThrownBy(() -> service.markMessageAsRead(sent.messageId(), uA))
				.isInstanceOf(SoundConnectException.class);
		
		// correct recipient
		service.markMessageAsRead(sent.messageId(), uB);
		DMMessage inDb = msgRepo.findById(sent.messageId()).orElseThrow();
		assertThat(inDb.getReadAt()).isNotNull();
		
		DMConversation refreshed = convRepo.findById(conv.getId()).orElseThrow();
		assertThat(refreshed.getLastReadMessageId()).isEqualTo(sent.messageId());
	}
	
	@Test
	void getMessagesByConversationId_notFound_should_throw() {
		assertThatThrownBy(() -> service.getMessagesByConversationId(UUID.randomUUID()))
				.isInstanceOf(SoundConnectException.class);
	}
}
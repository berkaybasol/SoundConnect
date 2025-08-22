// src/test/java/com/berkayb/soundconnect/modules/message/dm/controller/admin/DMAdminControllerTest.java
package com.berkayb.soundconnect.modules.message.dm.controller.admin;

import com.berkayb.soundconnect.SoundConnectApplication;
import com.berkayb.soundconnect.modules.message.dm.entity.DMConversation;
import com.berkayb.soundconnect.modules.message.dm.entity.DMMessage;
import com.berkayb.soundconnect.modules.message.dm.repository.DMConversationRepository;
import com.berkayb.soundconnect.modules.message.dm.repository.DMMessageRepository;
import com.berkayb.soundconnect.shared.constant.EndPoints;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = SoundConnectApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Tag("web")
class DMAdminControllerTest {
	
	@Autowired MockMvc mockMvc;
	
	@Autowired DMConversationRepository convRepo;
	@Autowired DMMessageRepository msgRepo;
	
	@MockitoBean RabbitTemplate rabbitTemplate;
	
	DMConversation conv;
	DMMessage m1;
	
	@BeforeEach
	void setup() {
		msgRepo.deleteAll();
		convRepo.deleteAll();
		
		UUID a = UUID.randomUUID();
		UUID b = UUID.randomUUID();
		conv = convRepo.save(DMConversation.builder().userAId(a).userBId(b).build());
		m1 = msgRepo.save(DMMessage.builder()
		                           .conversationId(conv.getId())
		                           .senderId(a).recipientId(b)
		                           .content("hello").messageType("text").build());
	}
	
	@Test
	void getAllConversations_ok() throws Exception {
		mockMvc.perform(get(EndPoints.DM.ADMIN_BASE + EndPoints.DM.ADMIN_CONVERSATIONS))
		       .andDo(print())
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.success").value(true))
		       .andExpect(jsonPath("$.data", hasSize(greaterThanOrEqualTo(1))));
	}
	
	@Test
	void getConversationById_ok() throws Exception {
		mockMvc.perform(get(EndPoints.DM.ADMIN_BASE + EndPoints.DM.ADMIN_CONVERSATION_BY_ID, conv.getId()))
		       .andDo(print())
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.data.id").value(conv.getId().toString()));
	}
	
	@Test
	void getMessages_ok() throws Exception {
		mockMvc.perform(get(EndPoints.DM.ADMIN_BASE + EndPoints.DM.ADMIN_MESSAGES)
				                .param("conversationId", conv.getId().toString()))
		       .andDo(print())
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.data", hasSize(1)))
		       .andExpect(jsonPath("$.data[0].content").value("hello"));
	}
	
	@Test
	void deleteMessage_ok() throws Exception {
		mockMvc.perform(delete(EndPoints.DM.ADMIN_BASE + EndPoints.DM.ADMIN_DELETE_MESSAGE, conv.getId(), m1.getId()))
		       .andDo(print())
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.success").value(true));
		
		org.assertj.core.api.Assertions.assertThat(msgRepo.findById(m1.getId())).isEmpty();
	}
	
	@Test
	void deleteConversation_ok() throws Exception {
		mockMvc.perform(delete(EndPoints.DM.ADMIN_BASE + EndPoints.DM.ADMIN_CONVERSATION_BY_ID, conv.getId()))
		       .andDo(print())
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.success").value(true));
		
		org.assertj.core.api.Assertions.assertThat(convRepo.findById(conv.getId())).isEmpty();
	}
}
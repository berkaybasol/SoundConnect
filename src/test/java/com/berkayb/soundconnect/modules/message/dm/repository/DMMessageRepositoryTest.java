package com.berkayb.soundconnect.modules.message.dm.repository;

import com.berkayb.soundconnect.modules.message.dm.entity.DMConversation;
import com.berkayb.soundconnect.modules.message.dm.entity.DMMessage;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnableJpaRepositories(basePackages = "com.berkayb.soundconnect")
@EntityScan(basePackages = "com.berkayb.soundconnect")
@TestPropertySource(properties = {
		"spring.datasource.url=jdbc:h2:mem:sc-${random.uuid};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
		"spring.jpa.hibernate.ddl-auto=create-drop"
})
@Tag("repo")
class DMMessageRepositoryTest {
	
	@org.springframework.context.annotation.Configuration
	@org.springframework.data.jpa.repository.config.EnableJpaAuditing
	static class AuditingTestConfig {}

	
	@org.springframework.beans.factory.annotation.Autowired
	DMMessageRepository messageRepo;
	@org.springframework.beans.factory.annotation.Autowired
	DMConversationRepository convRepo;
	
	@Test
	void findByConversation_and_lastMessageQueries_should_work() throws Exception {
		UUID a = UUID.randomUUID();
		UUID b = UUID.randomUUID();
		DMConversation conv = convRepo.save(DMConversation.builder().userAId(a).userBId(b).build());
		
		DMMessage m1 = messageRepo.save(DMMessage.builder()
		                                         .conversationId(conv.getId())
		                                         .senderId(a).recipientId(b)
		                                         .content("hello").messageType("text").build());
		messageRepo.flush();
		
		// saniye sınırını atla ki created_at kesin farklı olsun
		Thread.sleep(1200);
		
		DMMessage m2 = messageRepo.save(DMMessage.builder()
		                                         .conversationId(conv.getId())
		                                         .senderId(b).recipientId(a)
		                                         .content("hi").messageType("text").build());
		messageRepo.flush();
		
		List<DMMessage> asc = messageRepo.findByConversationIdOrderByCreatedAtAsc(conv.getId());
		assertThat(asc).hasSize(2);
		assertThat(asc.get(0).getId()).isEqualTo(m1.getId());
		assertThat(asc.get(1).getId()).isEqualTo(m2.getId());
		
		Optional<DMMessage> last = messageRepo.findTopByConversationIdOrderByCreatedAtDesc(conv.getId());
		assertThat(last).isPresent();
		assertThat(last.get().getId()).isEqualTo(m2.getId());
		// güvenlik için created_at gerçekten büyük mü?
		assertThat(last.get().getCreatedAt().truncatedTo(ChronoUnit.SECONDS))
				.isAfterOrEqualTo(m1.getCreatedAt().truncatedTo(ChronoUnit.SECONDS));
	}
	
	@Test
	void unreadQueries_should_work() throws Exception {
		UUID a = UUID.randomUUID();
		UUID b = UUID.randomUUID();
		DMConversation conv = convRepo.save(DMConversation.builder().userAId(a).userBId(b).build());
		
		messageRepo.save(DMMessage.builder()
		                          .conversationId(conv.getId())
		                          .senderId(a).recipientId(b)
		                          .content("to b").messageType("text").build());
		messageRepo.flush();
		
		Thread.sleep(1200);
		
		DMMessage readMsg = messageRepo.save(DMMessage.builder()
		                                              .conversationId(conv.getId())
		                                              .senderId(a).recipientId(b)
		                                              .content("read").messageType("text").build());
		readMsg.setReadAt(java.time.LocalDateTime.now());
		messageRepo.save(readMsg);
		messageRepo.flush();
		
		List<DMMessage> unreadForB = messageRepo.findByRecipientIdAndReadAtIsNull(b);
		assertThat(unreadForB).hasSize(1);
		
		List<DMMessage> unreadInConvForB =
				messageRepo.findByConversationIdAndRecipientIdAndReadAtIsNull(conv.getId(), b);
		assertThat(unreadInConvForB).hasSize(1);
	}
}
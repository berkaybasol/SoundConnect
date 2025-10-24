package com.berkayb.soundconnect.modules.message.dm.repository;

import com.berkayb.soundconnect.modules.message.dm.entity.DMConversation;
import com.berkayb.soundconnect.modules.message.dm.entity.DMMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(DMMessageRepositoryIT.JpaAuditConfig.class)
@Tag("it")
class DMMessageRepositoryIT {
	
	// -------- Testcontainers: Postgres 15 (ya da 16 da olur) --------
	@Container
	static final PostgreSQLContainer<?> POSTGRES =
			new PostgreSQLContainer<>("postgres:15-alpine")
					.withDatabaseName("soundconnect_test")
					.withUsername("test")
					.withPassword("test");
	
	@DynamicPropertySource
	static void datasourceProps(DynamicPropertyRegistry r) {
		r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		r.add("spring.datasource.username", POSTGRES::getUsername);
		r.add("spring.datasource.password", POSTGRES::getPassword);
		r.add("spring.jpa.hibernate.ddl-auto", () -> "update"); // entity şemanı kurması için
		r.add("spring.jpa.properties.hibernate.jdbc.time_zone", () -> "UTC");
		r.add("spring.jpa.show-sql", () -> "false");
	}
	
	@Autowired
	DMConversationRepository conversationRepository;
	
	@Autowired
	DMMessageRepository messageRepository;
	
	UUID userA;
	UUID userB;
	DMConversation conversation;
	
	@BeforeEach
	void setUp() {
		userA = UUID.randomUUID();
		userB = UUID.randomUUID();
		
		conversation = DMConversation.builder()
		                             .userAId(userA)
		                             .userBId(userB)
		                             .build();
		conversation = conversationRepository.save(conversation);
	}
	
	@Test
	@DisplayName("Mesajlar createdAt'e göre ASC gelmeli ve son mesaj DESC ile doğru bulunmalı")
	void orderingAndTopByCreatedAt() throws Exception {
		// given
		DMMessage m1 = DMMessage.builder()
		                        .conversationId(conversation.getId())
		                        .senderId(userA)
		                        .recipientId(userB)
		                        .content("hello")
		                        .messageType("text")
		                        .build();
		messageRepository.save(m1);
		
		// Küçük bir zaman farkı oluşturalım ki createdAt sıralaması net olsun
		Thread.sleep(5);
		
		DMMessage m2 = DMMessage.builder()
		                        .conversationId(conversation.getId())
		                        .senderId(userB)
		                        .recipientId(userA)
		                        .content("hi back")
		                        .messageType("text")
		                        .build();
		messageRepository.save(m2);
		
		// when
		List<DMMessage> ascList = messageRepository
				.findByConversationIdOrderByCreatedAtAsc(conversation.getId());
		
		Optional<DMMessage> last = messageRepository
				.findTopByConversationIdOrderByCreatedAtDesc(conversation.getId());
		
		// then
		assertThat(ascList).hasSize(2);
		assertThat(ascList.get(0).getContent()).isEqualTo("hello");
		assertThat(ascList.get(1).getContent()).isEqualTo("hi back");
		
		assertThat(last).isPresent();
		assertThat(last.get().getContent()).isEqualTo("hi back");
	}
	
	@Test
	@DisplayName("Okunmamış mesaj filtreleri recipient bazında doğru çalışmalı")
	void unreadFilters() {
		// given
		DMMessage unreadToB = DMMessage.builder()
		                               .conversationId(conversation.getId())
		                               .senderId(userA)
		                               .recipientId(userB)
		                               .content("for B - unread")
		                               .messageType("text")
		                               .build();
		messageRepository.save(unreadToB);
		
		DMMessage unreadToBAgain = DMMessage.builder()
		                                    .conversationId(conversation.getId())
		                                    .senderId(userA)
		                                    .recipientId(userB)
		                                    .content("for B - unread 2")
		                                    .messageType("text")
		                                    .build();
		messageRepository.save(unreadToBAgain);
		
		DMMessage readToA = DMMessage.builder()
		                             .conversationId(conversation.getId())
		                             .senderId(userB)
		                             .recipientId(userA)
		                             .content("for A - read")
		                             .messageType("text")
		                             .build();
		readToA.setReadAt(java.time.LocalDateTime.now());
		messageRepository.save(readToA);
		
		// when
		List<DMMessage> unreadForBAll = messageRepository.findByRecipientIdAndReadAtIsNull(userB);
		List<DMMessage> unreadForBInThisConv =
				messageRepository.findByConversationIdAndRecipientIdAndReadAtIsNull(conversation.getId(), userB);
		
		List<DMMessage> unreadForAAll = messageRepository.findByRecipientIdAndReadAtIsNull(userA);
		
		// then
		assertThat(unreadForBAll).hasSize(2);
		assertThat(unreadForBInThisConv).hasSize(2);
		
		assertThat(unreadForAAll).isEmpty(); // A için tek mesaj readAt set edildi
	}
	
	// --- Testte JPA auditing'i etkinleştiriyoruz ---
	@EnableJpaAuditing
	static class JpaAuditConfig {
		@Bean
		@Primary
		AuditorAware<UUID> auditorAware() {
			// Audit alanları için sahte bir auditor
			return () -> Optional.of(UUID.randomUUID());
		}
	}
}
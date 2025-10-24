package com.berkayb.soundconnect.modules.message.dm.repository;

import com.berkayb.soundconnect.modules.message.dm.entity.DMConversation;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(DMConversationRepositoryIT.JpaAuditConfig.class)
@Tag("it")
class DMConversationRepositoryIT {
	
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
		r.add("spring.jpa.hibernate.ddl-auto", () -> "update");
		r.add("spring.jpa.properties.hibernate.jdbc.time_zone", () -> "UTC");
		r.add("spring.jpa.show-sql", () -> "false");
	}
	
	@Autowired
	DMConversationRepository conversationRepository;
	
	UUID u1, u2, u3;
	DMConversation c12, c13, c23;
	
	@BeforeEach
	void setUp() {
		u1 = UUID.randomUUID();
		u2 = UUID.randomUUID();
		u3 = UUID.randomUUID();
		
		// u1-u2 konuşması (daha eski)
		c12 = DMConversation.builder()
		                    .userAId(u1)
		                    .userBId(u2)
		                    .lastMessageAt(LocalDateTime.now().minusMinutes(10))
		                    .build();
		c12 = conversationRepository.save(c12);
		
		// u1-u3 konuşması (daha yeni)
		c13 = DMConversation.builder()
		                    .userAId(u1)
		                    .userBId(u3)
		                    .lastMessageAt(LocalDateTime.now().minusMinutes(5))
		                    .build();
		c13 = conversationRepository.save(c13);
		
		// u2-u3 konuşması (u1 ile ilgili değil, lastMessageAt null)
		c23 = DMConversation.builder()
		                    .userAId(u2)
		                    .userBId(u3)
		                    .build();
		c23 = conversationRepository.save(c23);
	}
	
	@Test
	@DisplayName("findByUserAIdOrUserBId: Kullanıcının dahil olduğu konuşmalar listelenmeli")
	void findByUserMembership() {
		List<DMConversation> forU1 = conversationRepository.findByUserAIdOrUserBId(u1, u1);
		
		assertThat(forU1).hasSize(2);
		assertThat(forU1)
				.extracting(DMConversation::getId)
				.containsExactlyInAnyOrder(c12.getId(), c13.getId());
	}
	
	@Test
	@DisplayName("findConversationBetweenUsers: İki kullanıcı arasındaki konuşma her iki yönde de bulunmalı")
	void findBetweenUsersBidirectional() {
		Optional<DMConversation> direct = conversationRepository.findConversationBetweenUsers(u1, u2);
		Optional<DMConversation> reverse = conversationRepository.findConversationBetweenUsers(u2, u1);
		
		assertThat(direct).isPresent();
		assertThat(reverse).isPresent();
		
		assertThat(direct.get().getId()).isEqualTo(c12.getId());
		assertThat(reverse.get().getId()).isEqualTo(c12.getId());
	}
	
	@Test
	@DisplayName("findByUserAIdOrUserBIdOrderByLastMessageAtDesc: Son mesaja göre azalan sıralama")
	void orderByLastMessageAtDesc() {
		List<DMConversation> ordered = conversationRepository
				.findByUserAIdOrUserBIdOrderByLastMessageAtDesc(u1, u1);
		
		// u1'e ait olanlar c13 (daha yeni) ve c12 (daha eski). c23 zaten u1 ile ilgili değil.
		assertThat(ordered).hasSize(2);
		assertThat(ordered.get(0).getId()).isEqualTo(c13.getId());
		assertThat(ordered.get(1).getId()).isEqualTo(c12.getId());
	}
	
	// --- JPA auditing'i test context'te açıyoruz ---
	@EnableJpaAuditing
	static class JpaAuditConfig {
		@Bean
		@Primary
		AuditorAware<UUID> auditorAware() {
			return () -> java.util.Optional.of(UUID.randomUUID());
		}
	}
}
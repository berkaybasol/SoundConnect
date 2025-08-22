// src/test/java/com/berkayb/soundconnect/modules/message/dm/repository/DMConversationRepositoryTest.java
package com.berkayb.soundconnect.modules.message.dm.repository;

import com.berkayb.soundconnect.modules.message.dm.entity.DMConversation;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
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
class DMConversationRepositoryTest {
	
	@org.springframework.beans.factory.annotation.Autowired
	DMConversationRepository repo;
	
	@Test
	void findConversationBetweenUsers_should_work_both_orders() {
		UUID u1 = UUID.randomUUID();
		UUID u2 = UUID.randomUUID();
		UUID u3 = UUID.randomUUID();
		
		DMConversation c1 = repo.save(DMConversation.builder()
		                                            .userAId(u1).userBId(u2)
		                                            .lastMessageAt(LocalDateTime.now().minusMinutes(10))
		                                            .build());
		
		repo.save(DMConversation.builder()
		                        .userAId(u1).userBId(u3)
		                        .lastMessageAt(LocalDateTime.now().minusMinutes(5))
		                        .build());
		
		Optional<DMConversation> a = repo.findConversationBetweenUsers(u1, u2);
		Optional<DMConversation> b = repo.findConversationBetweenUsers(u2, u1);
		
		assertThat(a).isPresent();
		assertThat(b).isPresent();
		assertThat(a.get().getId()).isEqualTo(c1.getId());
		assertThat(b.get().getId()).isEqualTo(c1.getId());
	}
	
	@Test
	void findByUserAIdOrUserBId_and_orderByLastMessageAtDesc_should_work() {
		UUID u1 = UUID.randomUUID();
		UUID u2 = UUID.randomUUID();
		UUID u3 = UUID.randomUUID();
		
		DMConversation older = repo.save(DMConversation.builder()
		                                               .userAId(u1).userBId(u2)
		                                               .lastMessageAt(LocalDateTime.now().minusHours(1))
		                                               .build());
		DMConversation newer = repo.save(DMConversation.builder()
		                                               .userAId(u3).userBId(u1)
		                                               .lastMessageAt(LocalDateTime.now().minusMinutes(1))
		                                               .build());
		
		List<DMConversation> list = repo.findByUserAIdOrUserBIdOrderByLastMessageAtDesc(u1, u1);
		assertThat(list).hasSize(2);
		assertThat(list.get(0).getId()).isEqualTo(newer.getId());
		assertThat(list.get(1).getId()).isEqualTo(older.getId());
	}
}
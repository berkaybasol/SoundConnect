package com.berkayb.soundconnect.modules.collab.repository;

import com.berkayb.soundconnect.modules.collab.entity.Collab;
import com.berkayb.soundconnect.modules.collab.enums.CollabCategory;
import com.berkayb.soundconnect.modules.collab.enums.CollabRole;
import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.AuthProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class CollabRepositoryTest {
	
	@Autowired
	CollabRepository collabRepository;
	
	@Autowired
	TestEntityManager em;
	
	private User createUser(String username) {
		User u = User.builder()
		             .username(username)
		             .password("pass123")
		             .email(username + "@mail.com")
		             .provider(AuthProvider.LOCAL)
		             .emailVerified(false)
		             .build();
		em.persist(u);
		return u;
	}
	
	private City createCity(String name) {
		City c = City.builder()
		             .name(name)
		             .build();
		em.persist(c);
		return c;
	}
	
	@Test
	@DisplayName("findByIdAndOwner_Id → owner doğruysa döner")
	void findByIdAndOwner_ok() {
		
		User owner = createUser("ali");
		City city = createCity("Ankara");
		
		Collab collab = Collab.builder()
		                      .owner(owner)
		                      .ownerRole(CollabRole.MUSICIAN)
		                      .city(city)
		                      .category(CollabCategory.GIG)
		                      .title("Test ilan")
		                      .description("desc")
		                      .daily(false)
		                      .build();
		
		em.persist(collab);
		em.flush();
		
		var found = collabRepository.findByIdAndOwner_Id(collab.getId(), owner.getId());
		
		assertThat(found).isPresent();
	}
	
	@Test
	@DisplayName("findByIdAndOwner_Id → owner yanlışsa boş döner")
	void findByIdAndOwner_wrong() {
		
		User owner = createUser("ali");
		User other = createUser("veli");
		City city = createCity("Ankara");
		
		Collab collab = Collab.builder()
		                      .owner(owner)
		                      .ownerRole(CollabRole.MUSICIAN)
		                      .city(city)
		                      .category(CollabCategory.GIG)
		                      .title("Test ilan")
		                      .daily(false)
		                      .build();
		
		em.persist(collab);
		em.flush();
		
		var found = collabRepository.findByIdAndOwner_Id(collab.getId(), other.getId());
		
		assertThat(found).isEmpty();
	}
	
	@Test
	@DisplayName("findByIdAndDailyTrue → daily ilanı bulur")
	void findByIdAndDailyTrue_ok() {
		
		User owner = createUser("ali");
		City city = createCity("Ankara");
		
		Collab collab = Collab.builder()
		                      .owner(owner)
		                      .ownerRole(CollabRole.MUSICIAN)
		                      .city(city)
		                      .category(CollabCategory.RECORDING)
		                      .title("Daily ilan")
		                      .daily(true)
		                      .expirationTime(LocalDateTime.now().plusHours(2))
		                      .build();
		
		em.persist(collab);
		em.flush();
		
		var found = collabRepository.findByIdAndDailyTrue(collab.getId());
		
		assertThat(found).isPresent();
		assertThat(found.get().isDaily()).isTrue();
	}
	
	@Test
	@DisplayName("findByIdAndDailyTrue → daily değilse boş döner")
	void findByIdAndDailyTrue_notDaily() {
		
		User owner = createUser("ali");
		City city = createCity("Ankara");
		
		Collab collab = Collab.builder()
		                      .owner(owner)
		                      .ownerRole(CollabRole.MUSICIAN)
		                      .city(city)
		                      .category(CollabCategory.GIG)
		                      .title("Normal ilan")
		                      .daily(false)
		                      .build();
		
		em.persist(collab);
		em.flush();
		
		var found = collabRepository.findByIdAndDailyTrue(collab.getId());
		
		assertThat(found).isEmpty();
	}
}
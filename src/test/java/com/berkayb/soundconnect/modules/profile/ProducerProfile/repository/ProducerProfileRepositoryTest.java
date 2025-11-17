package com.berkayb.soundconnect.modules.profile.ProducerProfile.repository;

import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.location.repository.CityRepository;
import com.berkayb.soundconnect.modules.profile.ProducerProfile.entity.ProducerProfile;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.AuthProvider;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@EnableJpaRepositories(basePackages = "com.berkayb.soundconnect")
@EntityScan(basePackages = "com.berkayb.soundconnect")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
		"spring.datasource.url=jdbc:h2:mem:sc-${random.uuid};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
		"spring.jpa.hibernate.ddl-auto=create-drop"
})
@Tag("repo")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)

class ProducerProfileRepositoryTest {
	
	@Autowired ProducerProfileRepository producerRepo;
	@Autowired UserRepository userRepo;
	@Autowired CityRepository cityRepo;
	
	City city;
	
	@BeforeEach
	void setup() {
		// child -> parent
		producerRepo.deleteAll();
		userRepo.deleteAll();
		cityRepo.deleteAll();
		
		city = cityRepo.save(City.builder().name("C_" + UUID.randomUUID()).build());
	}
	
	private User seedUser(String uname) {
		return userRepo.save(User.builder()
		                         .username(uname + "_" + UUID.randomUUID())
		                         .email(uname + "_" + UUID.randomUUID() + "@t.local")
		                         .password("secret")
		                         .provider(AuthProvider.LOCAL)
		                         .emailVerified(true)
		                         .city(city)
		                         .build());
	}
	
	@Test
	void findByUserId_should_return_profile_when_exists() {
		User user = seedUser("bob");
		
		ProducerProfile profile = producerRepo.save(ProducerProfile.builder()
		                                                           .user(user)
		                                                           .name("ProdBob")
		                                                           .description("desc")
		                                                           .profilePicture("pp.png")
		                                                           .address("addr")
		                                                           .phone("555")
		                                                           .website("site.com")
		                                                           .instagramUrl("ig")
		                                                           .youtubeUrl("yt")
		                                                           .build());
		
		Optional<ProducerProfile> found = producerRepo.findByUserId(user.getId());
		assertThat(found).isPresent();
		assertThat(found.get().getId()).isEqualTo(profile.getId());
		assertThat(found.get().getWebsite()).isEqualTo("site.com");
	}
	
	@Test
	void findByName_should_work() {
		User user = seedUser("kate");
		
		producerRepo.save(ProducerProfile.builder()
		                                 .user(user)
		                                 .name("FindMe")
		                                 .description("d")
		                                 .profilePicture("p.png")
		                                 .address("a")
		                                 .phone("1")
		                                 .website("site.com")
		                                 .instagramUrl("ig")
		                                 .youtubeUrl("yt")
		                                 .build());
		
		var found = producerRepo.findByName("FindMe");
		assertThat(found).isPresent();
		assertThat(found.get().getName()).isEqualTo("FindMe");
	}
	
	@Test
	void findByUserId_should_return_empty_when_not_exists() {
		Optional<ProducerProfile> found = producerRepo.findByUserId(UUID.randomUUID());
		assertThat(found).isEmpty();
	}
}
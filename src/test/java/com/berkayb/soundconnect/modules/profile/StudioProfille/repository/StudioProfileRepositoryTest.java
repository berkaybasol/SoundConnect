package com.berkayb.soundconnect.modules.profile.StudioProfile.repository;

import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.location.repository.CityRepository;
import com.berkayb.soundconnect.modules.profile.StudioProfile.entity.StudioProfile;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.AuthProvider;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@EnableJpaRepositories(basePackages = "com.berkayb.soundconnect")
@EntityScan(basePackages = "com.berkayb.soundconnect")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
		"spring.datasource.url=jdbc:h2:mem:sc-studio-repo-${random.uuid};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
		"spring.jpa.hibernate.ddl-auto=create-drop",
		"spring.rabbitmq.listener.simple.auto-startup=false",
		"spring.rabbitmq.listener.direct.auto-startup=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Tag("repo")
class StudioProfileRepositoryTest {
	
	@Autowired StudioProfileRepository repository;
	@Autowired UserRepository userRepo;
	@Autowired CityRepository cityRepo;
	
	City city;
	
	@BeforeEach
	void setup() {
		// child -> parent
		repository.deleteAll();
		userRepo.deleteAll();
		cityRepo.deleteAll();
		
		city = cityRepo.save(City.builder().name("C_" + UUID.randomUUID()).build());
	}
	
	private User seedUser(String uname) {
		return userRepo.save(User.builder()
		                         .username(uname + "_" + UUID.randomUUID())
		                         .email(uname + "_" + UUID.randomUUID() + "@t.local")
		                         .password("pwd")
		                         .provider(AuthProvider.LOCAL)
		                         .emailVerified(true)
		                         .city(city)
		                         .build());
	}
	
	@Test
	@DisplayName("findByUserId should return profile when exists")
	void findByUserId_should_return_profile_when_exists() {
		User user = seedUser("studioUser");
		
		StudioProfile profile = StudioProfile.builder()
		                                     .user(user)
		                                     .description("desc")
		                                     .profilePicture("pp.png")
		                                     .facilities(Set.of("Piano"))
		                                     .build();
		repository.saveAndFlush(profile);
		
		Optional<StudioProfile> found = repository.findByUserId(user.getId());
		assertThat(found).isPresent();
		assertThat(found.get().getDescription()).isEqualTo("desc");
		assertThat(found.get().getFacilities()).containsExactly("Piano");
	}
	
	@Test
	@DisplayName("findByUserId should return empty when not exists")
	void findByUserId_should_return_empty_when_not_exists() {
		Optional<StudioProfile> found = repository.findByUserId(UUID.randomUUID());
		assertThat(found).isEmpty();
	}
}
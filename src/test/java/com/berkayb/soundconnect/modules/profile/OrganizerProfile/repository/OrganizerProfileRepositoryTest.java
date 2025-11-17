package com.berkayb.soundconnect.modules.profile.OrganizerProfile.repository;

import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.location.repository.CityRepository;
import com.berkayb.soundconnect.modules.profile.OrganizerProfile.entity.OrganizerProfile;
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

class OrganizerProfileRepositoryTest {
	
	@Autowired OrganizerProfileRepository organizerRepo;
	@Autowired UserRepository userRepo;
	@Autowired CityRepository cityRepo;
	
	City city;
	
	@BeforeEach
	void setup() {
		// child -> parent
		organizerRepo.deleteAll();
		userRepo.deleteAll();
		cityRepo.deleteAll();
		
		city = cityRepo.save(City.builder().name("C_" + UUID.randomUUID()).build());
	}
	
	@Test
	void findByUserId_should_return_profile_when_exists() {
		User user = userRepo.save(User.builder()
		                              .username("bob_" + UUID.randomUUID())
		                              .email("bob_" + UUID.randomUUID() + "@t.local")
		                              .password("secret")
		                              .provider(AuthProvider.LOCAL)
		                              .emailVerified(true)
		                              .city(city)
		                              .build());
		
		OrganizerProfile profile = organizerRepo.save(OrganizerProfile.builder()
		                                                              .user(user)
		                                                              .name("OrgBob")
		                                                              .description("desc")
		                                                              .profilePicture("pp.png")
		                                                              .address("addr")
		                                                              .phone("555")
		                                                              .instagramUrl("ig")
		                                                              .youtubeUrl("yt")
		                                                              .build());
		
		Optional<OrganizerProfile> found = organizerRepo.findByUserId(user.getId());
		assertThat(found).isPresent();
		assertThat(found.get().getId()).isEqualTo(profile.getId());
		assertThat(found.get().getName()).isEqualTo("OrgBob");
	}
	
	@Test
	void findOrganizerProfileByName_should_work() {
		User user = userRepo.save(User.builder()
		                              .username("kate_" + UUID.randomUUID())
		                              .email("kate_" + UUID.randomUUID() + "@t.local")
		                              .password("pw")
		                              .provider(AuthProvider.LOCAL)
		                              .emailVerified(true)
		                              .city(city)
		                              .build());
		
		organizerRepo.save(OrganizerProfile.builder()
		                                   .user(user)
		                                   .name("FindMe")
		                                   .description("d")
		                                   .profilePicture("p.png")
		                                   .address("a")
		                                   .phone("1")
		                                   .instagramUrl("ig")
		                                   .youtubeUrl("yt")
		                                   .build());
		
		var found = organizerRepo.findOrganizerProfileByName("FindMe");
		assertThat(found).isPresent();
		assertThat(found.get().getName()).isEqualTo("FindMe");
	}
	
	@Test
	void findByUserId_should_return_empty_when_not_exists() {
		Optional<OrganizerProfile> found = organizerRepo.findByUserId(UUID.randomUUID());
		assertThat(found).isEmpty();
	}
}
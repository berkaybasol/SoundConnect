package com.berkayb.soundconnect.modules.profile.ListenerProfile.repository;

import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.location.entity.District;
import com.berkayb.soundconnect.modules.location.entity.Neighborhood;
import com.berkayb.soundconnect.modules.location.repository.CityRepository;
import com.berkayb.soundconnect.modules.location.repository.DistrictRepository;
import com.berkayb.soundconnect.modules.location.repository.NeighborhoodRepository;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.entity.ListenerProfile;
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
class ListenerProfileRepositoryTest {
	
	@Autowired ListenerProfileRepository listenerRepo;
	@Autowired UserRepository userRepo;
	
	// location gerekli alanları seed’lemek için
	@Autowired CityRepository cityRepo;
	@Autowired DistrictRepository districtRepo;
	@Autowired NeighborhoodRepository neighborhoodRepo;
	
	City city;
	
	@BeforeEach
	void setup() {
		// child -> parent temizliği
		listenerRepo.deleteAll();
		userRepo.deleteAll();
		neighborhoodRepo.deleteAll();
		districtRepo.deleteAll();
		cityRepo.deleteAll();
		
		city = cityRepo.save(City.builder().name("C_" + UUID.randomUUID()).build());
		// District/Neighborhood entity’leriniz user için zorunlu değilse oluşturmaya gerek yok;
		// ama FK cascade’leri sebebiyle yukarıda temizliği dahil ettik.
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
		
		ListenerProfile profile = listenerRepo.save(ListenerProfile.builder()
		                                                           .user(user)
		                                                           .description("desc")
		                                                           .profilePicture("pp.png")
		                                                           .build());
		
		Optional<ListenerProfile> found = listenerRepo.findByUserId(user.getId());
		assertThat(found).isPresent();
		assertThat(found.get().getId()).isEqualTo(profile.getId());
	}
	
	@Test
	void findByUserId_should_return_empty_when_not_exists() {
		Optional<ListenerProfile> found = listenerRepo.findByUserId(UUID.randomUUID());
		assertThat(found).isEmpty();
	}
}
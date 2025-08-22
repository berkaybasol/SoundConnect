package com.berkayb.soundconnect.modules.profile.VenueProfile.repository;

import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.location.entity.District;
import com.berkayb.soundconnect.modules.location.entity.Neighborhood;
import com.berkayb.soundconnect.modules.location.repository.CityRepository;
import com.berkayb.soundconnect.modules.location.repository.DistrictRepository;
import com.berkayb.soundconnect.modules.location.repository.NeighborhoodRepository;
import com.berkayb.soundconnect.modules.profile.VenueProfile.entity.VenueProfile;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.AuthProvider;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.berkayb.soundconnect.modules.venue.entity.Venue;
import com.berkayb.soundconnect.modules.venue.enums.VenueStatus;
import com.berkayb.soundconnect.modules.venue.repository.VenueRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
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
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnableJpaRepositories(basePackages = "com.berkayb.soundconnect")
@EntityScan(basePackages = "com.berkayb.soundconnect")
// her test run'ında benzersiz H2 veritabanı + ddl
@TestPropertySource(properties = {
		"spring.datasource.url=jdbc:h2:mem:sc-${random.uuid};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
		"spring.jpa.hibernate.ddl-auto=create-drop"
})
@Tag("repo")
class VenueProfileRepositoryTest {
	
	@org.springframework.beans.factory.annotation.Autowired
	VenueProfileRepository venueProfileRepository;
	@org.springframework.beans.factory.annotation.Autowired
	VenueRepository venueRepository;
	@org.springframework.beans.factory.annotation.Autowired
	UserRepository userRepository;
	@org.springframework.beans.factory.annotation.Autowired
	CityRepository cityRepository;
	@org.springframework.beans.factory.annotation.Autowired
	DistrictRepository districtRepository;
	@org.springframework.beans.factory.annotation.Autowired
	NeighborhoodRepository neighborhoodRepository;
	
	City city;
	District district;
	Neighborhood neighborhood;
	User owner;
	Venue venue;
	
	@BeforeEach
	void setUp() {
		// FK sırası
		venueProfileRepository.deleteAll();
		venueRepository.deleteAll();
		userRepository.deleteAll();
		neighborhoodRepository.deleteAll();
		districtRepository.deleteAll();
		cityRepository.deleteAll();
		
		city = cityRepository.save(City.builder()
		                               .name("City_" + UUID.randomUUID())
		                               .build());
		
		district = districtRepository.save(District.builder()
		                                           .name("District_" + UUID.randomUUID())
		                                           .city(city)
		                                           .build());
		
		neighborhood = neighborhoodRepository.save(Neighborhood.builder()
		                                                       .name("Neighborhood_" + UUID.randomUUID())
		                                                       .district(district)
		                                                       .build());
		
		owner = userRepository.save(User.builder()
		                                .username("owner_" + UUID.randomUUID())
		                                .email("owner_"+UUID.randomUUID()+"@t.local") // <-- email zorunlu
		                                .password("pass")
		                                .provider(AuthProvider.LOCAL)
		                                .emailVerified(true)
		                                .city(city)
		                                .build());
		
		venue = venueRepository.save(Venue.builder()
		                                  .name("My Venue")
		                                  .address("Addr 1")
		                                  .city(city)
		                                  .district(district)
		                                  .neighborhood(neighborhood)
		                                  .owner(owner)
		                                  .phone("5551112233")
		                                  .status(VenueStatus.APPROVED)
		                                  .build());
	}
	
	@Test
	void findByVenueId_varsa_profile_doner() {
		// given
		VenueProfile profile = venueProfileRepository.save(VenueProfile.builder()
		                                                               .venue(venue)
		                                                               .bio("hello")
		                                                               .instagramUrl("insta")
		                                                               .youtubeUrl("yt")
		                                                               .websiteUrl("web")
		                                                               .profilePicture("pic")
		                                                               .build());
		
		// when
		Optional<VenueProfile> found = venueProfileRepository.findByVenueId(venue.getId());
		
		// then
		assertThat(found).isPresent();
		assertThat(found.get().getId()).isEqualTo(profile.getId());
		assertThat(found.get().getVenue().getId()).isEqualTo(venue.getId());
		assertThat(found.get().getBio()).isEqualTo("hello");
	}
	
	@Test
	void findByVenueId_yoksa_bos_doner() {
		Optional<VenueProfile> found = venueProfileRepository.findByVenueId(UUID.randomUUID());
		assertThat(found).isEmpty();
	}
}
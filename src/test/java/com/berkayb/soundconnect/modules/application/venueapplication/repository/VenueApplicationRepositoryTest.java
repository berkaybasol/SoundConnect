package com.berkayb.soundconnect.modules.application.venueapplication.repository;

import com.berkayb.soundconnect.modules.application.venueapplication.entity.VenueApplication;
import com.berkayb.soundconnect.modules.application.venueapplication.enums.ApplicationStatus;
import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.location.entity.District;
import com.berkayb.soundconnect.modules.location.entity.Neighborhood;
import com.berkayb.soundconnect.modules.location.repository.CityRepository;
import com.berkayb.soundconnect.modules.location.repository.DistrictRepository;
import com.berkayb.soundconnect.modules.location.repository.NeighborhoodRepository;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.AuthProvider;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@EnableJpaRepositories(basePackages = "com.berkayb.soundconnect")
@EntityScan(basePackages = "com.berkayb.soundconnect")
@TestPropertySource(properties = {
		"spring.datasource.url=jdbc:h2:mem:sc-${random.uuid};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
		"spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Tag("repo")
class VenueApplicationRepositoryTest {
	
	@org.springframework.beans.factory.annotation.Autowired VenueApplicationRepository repo;
	@org.springframework.beans.factory.annotation.Autowired UserRepository userRepo;
	@org.springframework.beans.factory.annotation.Autowired CityRepository cityRepo;
	@org.springframework.beans.factory.annotation.Autowired DistrictRepository districtRepo;
	@org.springframework.beans.factory.annotation.Autowired NeighborhoodRepository neighborhoodRepo;
	
	City city;
	District district;
	Neighborhood neighborhood;
	User u1, u2;
	
	@BeforeEach
	void setup() {
		// FK sırasına göre temizle
		repo.deleteAll();
		userRepo.deleteAll();
		neighborhoodRepo.deleteAll();
		districtRepo.deleteAll();
		cityRepo.deleteAll();
		
		// location
		city = cityRepo.save(City.builder().name("C_" + UUID.randomUUID()).build());
		district = districtRepo.save(District.builder().name("D_" + UUID.randomUUID()).city(city).build());
		neighborhood = neighborhoodRepo.save(Neighborhood.builder().name("N_" + UUID.randomUUID()).district(district).build());
		
		// users (email zorunlu!)
		u1 = userRepo.save(User.builder()
		                       .username("u1_" + UUID.randomUUID())
		                       .email("u1_"+UUID.randomUUID()+"@t.local")
		                       .password("pw")
		                       .provider(AuthProvider.LOCAL)
		                       .emailVerified(true)
		                       .city(city)
		                       .build());
		u2 = userRepo.save(User.builder()
		                       .username("u2_" + UUID.randomUUID())
		                       .email("u2_"+UUID.randomUUID()+"@t.local")
		                       .password("pw")
		                       .provider(AuthProvider.LOCAL)
		                       .emailVerified(true)
		                       .city(city)
		                       .build());
		
		// seed applications
		repo.save(VenueApplication.builder()
		                          .applicant(u1).venueName("V1").venueAddress("A1").phone("1")
		                          .status(ApplicationStatus.PENDING)
		                          .applicationDate(LocalDateTime.now())
		                          .city(city).district(district).neighborhood(neighborhood)
		                          .build());
		
		repo.save(VenueApplication.builder()
		                          .applicant(u1).venueName("V2").venueAddress("A2").phone("2")
		                          .status(ApplicationStatus.APPROVED)
		                          .applicationDate(LocalDateTime.now())
		                          .decisionDate(LocalDateTime.now())
		                          .city(city).district(district).neighborhood(neighborhood)
		                          .build());
		
		repo.save(VenueApplication.builder()
		                          .applicant(u2).venueName("V3").venueAddress("A3").phone("3")
		                          .status(ApplicationStatus.PENDING)
		                          .applicationDate(LocalDateTime.now())
		                          .city(city).district(district).neighborhood(neighborhood)
		                          .build());
	}
	
	@Test
	void findAllByApplicant_should_return_apps_of_that_user() {
		List<VenueApplication> list = repo.findAllByApplicant(u1);
		assertThat(list).hasSize(2);
		assertThat(list.stream().allMatch(a -> a.getApplicant().getId().equals(u1.getId()))).isTrue();
	}
	
	@Test
	void findByApplicantAndStatus_should_return_only_matching() {
		var opt = repo.findByApplicantAndStatus(u1, ApplicationStatus.PENDING);
		assertThat(opt).isPresent();
		assertThat(opt.get().getApplicant().getId()).isEqualTo(u1.getId());
		assertThat(opt.get().getStatus()).isEqualTo(ApplicationStatus.PENDING);
		
		var notFound = repo.findByApplicantAndStatus(u1, ApplicationStatus.REJECTED);
		assertThat(notFound).isNotPresent();
	}
	
	@Test
	void findAllByStatus_should_return_all_with_that_status() {
		List<VenueApplication> list = repo.findAllByStatus(ApplicationStatus.PENDING);
		assertThat(list).hasSize(2);
		assertThat(list.stream().allMatch(a -> a.getStatus() == ApplicationStatus.PENDING)).isTrue();
	}
}
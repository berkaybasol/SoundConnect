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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnableJpaRepositories(basePackages = "com.berkayb.soundconnect")
@EntityScan(basePackages = "com.berkayb.soundconnect")
class VenueApplicationRepositoryTest {
	
	@Autowired VenueApplicationRepository venueAppRepo;
	@Autowired UserRepository userRepo;
	@Autowired CityRepository cityRepo;
	@Autowired DistrictRepository districtRepo;
	@Autowired NeighborhoodRepository neighborhoodRepo;
	
	City city;
	District district;
	Neighborhood neighborhood;
	User userA;
	User userB;
	VenueApplication appA1_pending;
	VenueApplication appA2_approved;
	
	@BeforeEach
	void setUp() {
		// FK sırası: önce child tablolar
		venueAppRepo.deleteAll();
		userRepo.deleteAll();
		neighborhoodRepo.deleteAll();
		districtRepo.deleteAll();
		cityRepo.deleteAll();
		
		city = cityRepo.save(City.builder().name("TestCity").build());
		district = districtRepo.save(District.builder().name("TestDistrict").city(city).build());
		neighborhood = neighborhoodRepo.save(Neighborhood.builder().name("TestNeighborhood").district(district).build());
		
		userA = userRepo.save(User.builder()
		                          .username("userA")
		                          .password("pwd")
		                          .provider(AuthProvider.LOCAL)
		                          .emailVerified(true)
		                          .city(city)
		                          .build());
		
		userB = userRepo.save(User.builder()
		                          .username("userB")
		                          .password("pwd")
		                          .provider(AuthProvider.LOCAL)
		                          .emailVerified(true)
		                          .city(city)
		                          .build());
		
		appA1_pending = venueAppRepo.save(VenueApplication.builder()
		                                                  .applicant(userA)
		                                                  .venueName("A1 Venue")
		                                                  .venueAddress("A1 Addr")
		                                                  .phone("111")
		                                                  .status(ApplicationStatus.PENDING)
		                                                  .applicationDate(LocalDateTime.now())
		                                                  .decisionDate(null)
		                                                  .city(city)
		                                                  .district(district)
		                                                  .neighborhood(neighborhood)
		                                                  .build());
		
		appA2_approved = venueAppRepo.save(VenueApplication.builder()
		                                                   .applicant(userA)
		                                                   .venueName("A2 Venue")
		                                                   .venueAddress("A2 Addr")
		                                                   .phone("222")
		                                                   .status(ApplicationStatus.APPROVED)
		                                                   .applicationDate(LocalDateTime.now().minusDays(1))
		                                                   .decisionDate(LocalDateTime.now())
		                                                   .city(city)
		                                                   .district(district)
		                                                   .neighborhood(neighborhood)
		                                                   .build());
		
		// extra: userB için bir pending örnek (liste testinde sayıyı artırmak istersen)
		venueAppRepo.save(VenueApplication.builder()
		                                  .applicant(userB)
		                                  .venueName("B1 Venue")
		                                  .venueAddress("B1 Addr")
		                                  .phone("333")
		                                  .status(ApplicationStatus.PENDING)
		                                  .applicationDate(LocalDateTime.now())
		                                  .decisionDate(null)
		                                  .city(city)
		                                  .district(district)
		                                  .neighborhood(neighborhood)
		                                  .build());
	}
	
	@Test
	void findAllByApplicant_should_return_apps_of_that_user() {
		List<VenueApplication> list = venueAppRepo.findAllByApplicant(userA);
		assertThat(list).hasSize(2);
		assertThat(list).allMatch(a -> a.getApplicant().getId().equals(userA.getId()));
	}
	
	@Test
	void findByApplicantAndStatus_should_return_only_matching() {
		var opt = venueAppRepo.findByApplicantAndStatus(userA, ApplicationStatus.PENDING);
		assertThat(opt).isPresent();
		assertThat(opt.get().getId()).isEqualTo(appA1_pending.getId());
		
		var optAbsent = venueAppRepo.findByApplicantAndStatus(userA, ApplicationStatus.REJECTED);
		assertThat(optAbsent).isEmpty();
	}
	
	@Test
	void findAllByStatus_should_return_all_with_that_status() {
		List<VenueApplication> pendings = venueAppRepo.findAllByStatus(ApplicationStatus.PENDING);
		assertThat(pendings).isNotEmpty();
		assertThat(pendings).extracting(VenueApplication::getStatus)
		                    .allMatch(s -> s == ApplicationStatus.PENDING);
	}
}
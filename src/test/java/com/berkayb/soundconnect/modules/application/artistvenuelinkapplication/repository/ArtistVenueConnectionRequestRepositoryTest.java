package com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.repository;

import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.entity.ArtistVenueConnectionRequest;
import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.enums.RequestStatus;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.entity.MusicianProfile;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.repository.MusicianProfileRepository;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.AuthProvider;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.location.entity.District;
import com.berkayb.soundconnect.modules.location.entity.Neighborhood;
import com.berkayb.soundconnect.modules.location.repository.CityRepository;
import com.berkayb.soundconnect.modules.location.repository.DistrictRepository;
import com.berkayb.soundconnect.modules.location.repository.NeighborhoodRepository;
import com.berkayb.soundconnect.modules.venue.entity.Venue;
import com.berkayb.soundconnect.modules.venue.enums.VenueStatus;
import com.berkayb.soundconnect.modules.venue.repository.VenueRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnableJpaRepositories(basePackages = "com.berkayb.soundconnect")
@EntityScan(basePackages = "com.berkayb.soundconnect")
@TestMethodOrder(OrderAnnotation.class)
class ArtistVenueConnectionRequestRepositoryTest {
	
	// Repos
	@org.springframework.beans.factory.annotation.Autowired
	ArtistVenueConnectionRequestRepository requestRepo;
	@org.springframework.beans.factory.annotation.Autowired
	MusicianProfileRepository musicianProfileRepo;
	@org.springframework.beans.factory.annotation.Autowired
	VenueRepository venueRepo;
	@org.springframework.beans.factory.annotation.Autowired
	UserRepository userRepo;
	@org.springframework.beans.factory.annotation.Autowired
	CityRepository cityRepo;
	@org.springframework.beans.factory.annotation.Autowired
	DistrictRepository districtRepo;
	@org.springframework.beans.factory.annotation.Autowired
	NeighborhoodRepository neighborhoodRepo;
	
	// Seeded refs
	private UUID musicianProfileId;
	private UUID venueId;
	
	@BeforeEach
	void setup() {
		// temizle - bağımlılık sırayla
		requestRepo.deleteAll();
		musicianProfileRepo.deleteAll();
		venueRepo.deleteAll();
		userRepo.deleteAll();
		neighborhoodRepo.deleteAll();
		districtRepo.deleteAll();
		cityRepo.deleteAll();
		
		// location
		City city = cityRepo.save(City.builder().name("C_" + UUID.randomUUID()).build());
		District district = districtRepo.save(District.builder().name("D1").city(city).build());
		Neighborhood nhood = neighborhoodRepo.save(Neighborhood.builder().name("N1").district(district).build());
		
		// users
		User owner = userRepo.save(User.builder()
		                               .username("owner_" + UUID.randomUUID())
		                               .password("pw")
		                               .provider(AuthProvider.LOCAL)
		                               .emailVerified(true)
		                               .city(city)
		                               .build());
		
		User musicianUser = userRepo.save(User.builder()
		                                      .username("artist_" + UUID.randomUUID())
		                                      .password("pw")
		                                      .provider(AuthProvider.LOCAL)
		                                      .emailVerified(true)
		                                      .city(city)
		                                      .build());
		
		// venue
		Venue venue = venueRepo.save(Venue.builder()
		                                  .name("Nice Venue")
		                                  .address("Addr 1")
		                                  .city(city)
		                                  .district(district)
		                                  .neighborhood(nhood)
		                                  .owner(owner)
		                                  .phone("000")
		                                  .status(VenueStatus.APPROVED)
		                                  .build());
		
		// musician profile (minimum alanlarla)
		MusicianProfile mp = musicianProfileRepo.save(MusicianProfile.builder()
		                                                             .user(musicianUser)
		                                                             .stageName("Stage_" + UUID.randomUUID())
		                                                             .build());
		
		musicianProfileId = mp.getId();
		venueId = venue.getId();
		
		// requests
		requestRepo.save(ArtistVenueConnectionRequest.builder()
		                                             .musicianProfile(mp)
		                                             .venue(venue)
		                                             .status(RequestStatus.PENDING)
		                                             .message("hey")
		                                             .build());
		
		requestRepo.save(ArtistVenueConnectionRequest.builder()
		                                             .musicianProfile(mp)
		                                             .venue(venue)
		                                             .status(RequestStatus.ACCEPTED)
		                                             .message("accepted-before")
		                                             .build());
	}
	
	@Test
	void existsByMusicianProfileIdAndVenueIdAndStatus_should_work() {
		boolean existsPending = requestRepo.existsByMusicianProfileIdAndVenueIdAndStatus(
				musicianProfileId, venueId, RequestStatus.PENDING);
		boolean existsRejected = requestRepo.existsByMusicianProfileIdAndVenueIdAndStatus(
				musicianProfileId, venueId, RequestStatus.REJECTED);
		
		assertThat(existsPending).isTrue();
		assertThat(existsRejected).isFalse();
	}
	
	@Test
	void findAllByMusicianProfileId_should_return_list() {
		List<ArtistVenueConnectionRequest> list = requestRepo.findAllByMusicianProfileId(musicianProfileId);
		assertThat(list).isNotEmpty();
		assertThat(list.stream().allMatch(r -> r.getMusicianProfile().getId().equals(musicianProfileId))).isTrue();
	}
	
	@Test
	void findAllByVenueId_should_return_list() {
		List<ArtistVenueConnectionRequest> list = requestRepo.findAllByVenueId(venueId);
		assertThat(list).isNotEmpty();
		assertThat(list.stream().allMatch(r -> r.getVenue().getId().equals(venueId))).isTrue();
	}
}
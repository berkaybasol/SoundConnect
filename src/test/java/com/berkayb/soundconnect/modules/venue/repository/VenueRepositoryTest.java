package com.berkayb.soundconnect.modules.venue.repository;

import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.location.entity.District;
import com.berkayb.soundconnect.modules.location.entity.Neighborhood;
import com.berkayb.soundconnect.modules.location.repository.CityRepository;
import com.berkayb.soundconnect.modules.location.repository.DistrictRepository;
import com.berkayb.soundconnect.modules.location.repository.NeighborhoodRepository;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.berkayb.soundconnect.modules.venue.entity.Venue;
import com.berkayb.soundconnect.modules.venue.enums.VenueStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@Tag("repo")
class VenueRepositoryTest {
	
	@Autowired VenueRepository venueRepository;
	@Autowired UserRepository userRepository;
	@Autowired CityRepository cityRepository;
	@Autowired DistrictRepository districtRepository;
	@Autowired NeighborhoodRepository neighborhoodRepository;
	
	private City city;
	private District district;
	private Neighborhood neighborhood;
	private User ownerA;
	private User ownerB;
	
	@BeforeEach
	void setUp() {
		// FK sırasına dikkat
		venueRepository.deleteAll();
		userRepository.deleteAll();
		neighborhoodRepository.deleteAll();
		districtRepository.deleteAll();
		cityRepository.deleteAll();
		
		city = cityRepository.save(City.builder().name("City_" + UUID.randomUUID()).build());
		district = districtRepository.save(District.builder().name("Dist").city(city).build());
		neighborhood = neighborhoodRepository.save(Neighborhood.builder().name("Nbh").district(district).build());
		
		ownerA = userRepository.save(User.builder()
		                                 .username("ownerA_" + UUID.randomUUID())
		                                 .password("x").email("a@test.com")
		                                 .city(city)
		                                 .build());
		
		ownerB = userRepository.save(User.builder()
		                                 .username("ownerB_" + UUID.randomUUID())
		                                 .password("x").email("b@test.com")
		                                 .city(city)
		                                 .build());
	}
	
	private Venue newVenue(String name, User owner) {
		return Venue.builder()
		            .name(name)
		            .address("Addr 1")
		            .city(city)
		            .district(district)
		            .neighborhood(neighborhood)
		            .owner(owner)
		            .phone("555-0000")
		            .status(VenueStatus.APPROVED)
		            .build();
	}
	
	@Test
	void findByIdAndOwnerId_found() {
		var saved = venueRepository.save(newVenue("V1", ownerA));
		
		var found = venueRepository.findByIdAndOwnerId(saved.getId(), ownerA.getId());
		
		assertThat(found).isPresent();
		assertThat(found.get().getName()).isEqualTo("V1");
	}
	
	@Test
	void findByIdAndOwnerId_wrongOwner_returnsEmpty() {
		var saved = venueRepository.save(newVenue("V2", ownerA));
		
		var found = venueRepository.findByIdAndOwnerId(saved.getId(), ownerB.getId());
		
		assertThat(found).isEmpty();
	}
	
	@Test
	void findAllByOwnerId_returnsOnlyOwnersVenues() {
		venueRepository.save(newVenue("A1", ownerA));
		venueRepository.save(newVenue("A2", ownerA));
		venueRepository.save(newVenue("B1", ownerB));
		
		var listA = venueRepository.findAllByOwnerId(ownerA.getId());
		var listB = venueRepository.findAllByOwnerId(ownerB.getId());
		
		assertThat(listA).hasSize(2)
		                 .extracting(Venue::getName)
		                 .containsExactlyInAnyOrder("A1", "A2");
		
		assertThat(listB).hasSize(1)
		                 .extracting(Venue::getName)
		                 .containsExactly("B1");
	}
}
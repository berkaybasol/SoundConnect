package com.berkayb.soundconnect.modules.venue.repository;

import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.location.entity.District;
import com.berkayb.soundconnect.modules.location.entity.Neighborhood;
import com.berkayb.soundconnect.modules.location.repository.CityRepository;
import com.berkayb.soundconnect.modules.location.repository.DistrictRepository;
import com.berkayb.soundconnect.modules.location.repository.NeighborhoodRepository;
import com.berkayb.soundconnect.modules.profile.VenueProfile.entity.VenueProfile;
import com.berkayb.soundconnect.modules.profile.VenueProfile.repository.VenueProfileRepository;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.berkayb.soundconnect.modules.venue.entity.Venue;
import com.berkayb.soundconnect.modules.venue.enums.VenueStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
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
	@Autowired VenueProfileRepository venueProfileRepository;
	
	private City city;
	private District district;
	private Neighborhood neighborhood;
	private User ownerA;
	private User ownerB;
	
	@BeforeEach
	void setUp() {
		// FK sırasına dikkat
		venueProfileRepository.deleteAll();
		venueRepository.deleteAll();
		userRepository.deleteAll();
		neighborhoodRepository.deleteAll();
		districtRepository.deleteAll();
		cityRepository.deleteAll();
		
		city = cityRepository.save(City.builder().name("City_" + UUID.randomUUID()).build());
		district = districtRepository.save(District.builder().name("Dist").city(city).build());
		neighborhood = neighborhoodRepository.save(Neighborhood.builder().name("Nbh").district(district).build());
		
		ownerA = userRepository.save(User.builder()
		                                 .username("ownerA_" + UUID.randomUUID().toString().substring(0, 12))
		                                 .password("x").email("a@test.com")
		                                 .city(city)
		                                 .build());
		
		ownerB = userRepository.save(User.builder()
		                                 .username("ownerB_" + UUID.randomUUID().toString().substring(0, 12))
		                                 .password("x").email("b@test.com")
		                                 .city(city)
		                                 .build());
	}
	
	private Venue newVenue(String name, User owner) {
		return newVenue(
				name, "Addr 1", VenueStatus.APPROVED,
				city, district, neighborhood, owner
		);
	}

	private Venue newVenue(
			String name,
			String address,
			VenueStatus status,
			City venueCity,
			District venueDistrict,
			Neighborhood venueNeighborhood,
			User owner
	) {
		return Venue.builder()
				.name(name)
				.address(address)
				.city(venueCity)
				.district(venueDistrict)
				.neighborhood(venueNeighborhood)
				.owner(owner)
				.phone("555-0000")
				.status(status)
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

	@Test
	void ownerUsernameSearchStopsMatchingTheOldNameAfterRename() {
		ownerA.setUsername("oldvenueuser");
		userRepository.saveAndFlush(ownerA);
		Venue venue = venueRepository.save(newVenue("Independent Venue", ownerA));

		assertThat(venueRepository.searchByNameOrOwnerUsername(
				"oldvenueuser", "oldvenueuser", PageRequest.of(0, 10)
		)).extracting(Venue::getId).containsExactly(venue.getId());

		ownerA.setUsername("newvenueuser");
		userRepository.saveAndFlush(ownerA);

		assertThat(venueRepository.searchByNameOrOwnerUsername(
				"oldvenueuser", "oldvenueuser", PageRequest.of(0, 10)
		)).isEmpty();
		assertThat(venueRepository.searchByNameOrOwnerUsername(
				"newvenueuser", "newvenueuser", PageRequest.of(0, 10)
		)).extracting(Venue::getId).containsExactly(venue.getId());
	}

	@Test
	void nameSearchTreatsPercentAndUnderscoreAsLiteralCharacters() {
		Venue literal = venueRepository.save(newVenue("100%_Live", ownerA));
		venueRepository.save(newVenue("100X Live", ownerB));

		assertThat(venueRepository.searchByName("%_", PageRequest.of(0, 10)))
				.extracting(Venue::getId)
				.containsExactly(literal.getId());
	}

	@Test
	void tableGroupVenueOptions_shouldReturnOnlyApprovedConsistentLocationsInStableRankOrder() {
		City alphaCity = cityRepository.save(City.builder().name("Alpha City").build());
		District alphaDistrict = districtRepository.save(
				District.builder().name("Alpha District").city(alphaCity).build());
		Neighborhood alphaNeighborhood = neighborhoodRepository.save(
				Neighborhood.builder().name("Alpha Neighborhood").district(alphaDistrict).build());
		City betaCity = cityRepository.save(City.builder().name("Beta City").build());
		District betaDistrict = districtRepository.save(
				District.builder().name("Beta District").city(betaCity).build());
		Neighborhood betaNeighborhood = neighborhoodRepository.save(
				Neighborhood.builder().name("Beta Neighborhood").district(betaDistrict).build());

		Venue exactAlpha = newVenue(
				"Sound Hub", "Z address", VenueStatus.APPROVED,
				alphaCity, alphaDistrict, alphaNeighborhood, ownerA);
		Venue exactBeta = newVenue(
				"Sound Hub", "A address", VenueStatus.APPROVED,
				betaCity, betaDistrict, betaNeighborhood, ownerB);
		Venue prefix = newVenue(
				"Sound Hub Live", "Prefix address", VenueStatus.APPROVED,
				alphaCity, alphaDistrict, alphaNeighborhood, ownerA);
		Venue contains = newVenue(
				"The Sound Hub", "Contains address", VenueStatus.APPROVED,
				alphaCity, alphaDistrict, alphaNeighborhood, ownerB);
		Venue pending = newVenue(
				"Sound Hub", "Pending address", VenueStatus.PENDING,
				alphaCity, alphaDistrict, alphaNeighborhood, ownerA);
		Venue inconsistent = newVenue(
				"Sound Hub", "Inconsistent address", VenueStatus.APPROVED,
				alphaCity, betaDistrict, betaNeighborhood, ownerB);
		venueRepository.saveAllAndFlush(List.of(
				exactAlpha, exactBeta, prefix, contains, pending, inconsistent));
		UUID exactAlphaProfilePictureId = UUID.randomUUID();
		venueProfileRepository.saveAllAndFlush(List.of(
				VenueProfile.builder()
						.venue(exactAlpha)
						.profilePictureMediaId(exactAlphaProfilePictureId)
						.build(),
				VenueProfile.builder()
						.venue(exactBeta)
						.profilePictureMediaId(null)
						.build()
		));

		var results = venueRepository.searchTableGroupVenueOptions(
				"sOuNd HuB", VenueStatus.APPROVED, PageRequest.of(0, 10));

		assertThat(results)
				.extracting(option -> option.getId())
				.containsExactly(
						exactAlpha.getId(),
						exactBeta.getId(),
						prefix.getId(),
						contains.getId()
				)
				.doesNotContain(pending.getId(), inconsistent.getId());
		assertThat(results.getFirst().getName()).isEqualTo("Sound Hub");
		assertThat(results.getFirst().getProfilePictureMediaId())
				.isEqualTo(exactAlphaProfilePictureId);
		assertThat(results.get(1).getProfilePictureMediaId()).isNull();
		assertThat(results.get(2).getProfilePictureMediaId()).isNull();
		assertThat(results.getFirst().getCityId()).isEqualTo(alphaCity.getId());
		assertThat(results.getFirst().getDistrictId()).isEqualTo(alphaDistrict.getId());
		assertThat(results.getFirst().getNeighborhoodId()).isEqualTo(alphaNeighborhood.getId());
		assertThat(venueRepository.searchTableGroupVenueOptions(
				"sound hub", VenueStatus.APPROVED, PageRequest.of(0, 2)))
				.extracting(option -> option.getId())
				.containsExactly(exactAlpha.getId(), exactBeta.getId());
	}

	@Test
	void tableGroupVenueOptions_shouldTreatPercentAndUnderscoreAsLiteralCharacters() {
		Venue literal = venueRepository.save(newVenue("100%_Live", ownerA));
		venueRepository.save(newVenue("100X Live", ownerB));

		assertThat(venueRepository.searchTableGroupVenueOptions(
				"%_", VenueStatus.APPROVED, PageRequest.of(0, 10)))
				.extracting(option -> option.getId())
				.containsExactly(literal.getId());
	}
}

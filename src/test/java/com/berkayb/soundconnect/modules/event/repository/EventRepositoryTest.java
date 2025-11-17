package com.berkayb.soundconnect.modules.event.repository;

import com.berkayb.soundconnect.modules.event.entity.Event;
import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.location.entity.District;
import com.berkayb.soundconnect.modules.location.entity.Neighborhood;
import com.berkayb.soundconnect.modules.venue.entity.Venue;
import com.berkayb.soundconnect.modules.venue.enums.VenueStatus; // enum paketine göre düzenle
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ExtendWith(SpringExtension.class)
class EventRepositoryTest {
	
	@Autowired
	private EventRepository eventRepository;
	
	@Autowired
	private TestEntityManager entityManager;
	
	private City city;
	private District district;
	private Neighborhood neighborhood;
	private Venue venue;
	private Venue otherVenue;
	private Event eventToday;
	private Event eventAnotherVenue;
	
	@BeforeEach
	void setUp() {
		
		// -----------------------------
		// CITY
		// -----------------------------
		city = City.builder()
		           .name("Ankara")
		           .build();
		entityManager.persist(city);
		
		// -----------------------------
		// DISTRICT
		// -----------------------------
		district = District.builder()
		                   .name("Çankaya")
		                   .city(city)
		                   .build();
		entityManager.persist(district);
		
		// -----------------------------
		// NEIGHBORHOOD
		// -----------------------------
		neighborhood = Neighborhood.builder()
		                           .name("Kızılay")
		                           .district(district)
		                           .build();
		entityManager.persist(neighborhood);
		
		// -----------------------------
		// VENUE #1
		// -----------------------------
		venue = Venue.builder()
		             .name("Test Venue")
		             .address("Adres 1")                     // zorunlu
		             .phone("05000000000")                  // zorunlu
		             .status(VenueStatus.APPROVED)            // zorunlu
		             .description("Desc")
		             .musicStartTime(null)   // zorunlu olabilir
		             .website("test.com")
		             .city(city)
		             .district(district)
		             .neighborhood(neighborhood)
		             .build();
		entityManager.persist(venue);
		
		// -----------------------------
		// VENUE #2
		// -----------------------------
		otherVenue = Venue.builder()
		                  .name("Other Venue")
		                  .address("Adres 2")
		                  .phone("05000000001")
		                  .status(VenueStatus.APPROVED)
		                  .description("Desc2")
		                  .musicStartTime(null)
		                  .website("example.com")
		                  .city(city)
		                  .district(district)
		                  .neighborhood(neighborhood)
		                  .build();
		entityManager.persist(otherVenue);
		
		// -----------------------------
		// EVENT #1 (venue 1)
		// -----------------------------
		eventToday = Event.builder()
		                  .title("Bugünkü Etkinlik")
		                  .eventDate(LocalDate.now())
		                  .startTime(LocalTime.NOON)
		                  .venue(venue)
		                  .build();
		entityManager.persist(eventToday);
		
		// -----------------------------
		// EVENT #2 (venue 2)
		// -----------------------------
		eventAnotherVenue = Event.builder()
		                         .title("Diğer Mekan Etkinliği")
		                         .eventDate(LocalDate.now())
		                         .startTime(LocalTime.NOON)
		                         .venue(otherVenue)
		                         .build();
		entityManager.persist(eventAnotherVenue);
		
		entityManager.flush();
	}
	
	// -------------------------------------------------------
	// DATE
	// -------------------------------------------------------
	@Test
	void findByEventDate_shouldReturnEventsForGivenDate() {
		
		List<Event> events = eventRepository.findByEventDate(LocalDate.now());
		
		assertThat(events).hasSize(2);
	}
	
	// -------------------------------------------------------
	// CITY
	// -------------------------------------------------------
	@Test
	void findByVenue_City_Id_shouldReturnCityEvents() {
		
		List<Event> events = eventRepository.findByVenue_City_Id(city.getId());
		
		assertThat(events).hasSize(2);
	}
	
	// -------------------------------------------------------
	// DISTRICT
	// -------------------------------------------------------
	@Test
	void findByVenue_District_Id_shouldReturnDistrictEvents() {
		
		List<Event> events = eventRepository.findByVenue_District_Id(district.getId());
		
		assertThat(events).hasSize(2);
	}
	
	// -------------------------------------------------------
	// NEIGHBORHOOD
	// -------------------------------------------------------
	@Test
	void findByVenue_Neighborhood_Id_shouldReturnNeighborhoodEvents() {
		
		List<Event> events = eventRepository.findByVenue_Neighborhood_Id(neighborhood.getId());
		
		assertThat(events).hasSize(2);
	}
	
	// -------------------------------------------------------
	// VENUE
	// -------------------------------------------------------
	@Test
	void findByVenue_shouldReturnEventsOfSpecificVenue() {
		
		List<Event> events = eventRepository.findByVenue(venue);
		
		assertThat(events).hasSize(1);
		assertThat(events.get(0).getTitle()).isEqualTo("Bugünkü Etkinlik");
	}
}
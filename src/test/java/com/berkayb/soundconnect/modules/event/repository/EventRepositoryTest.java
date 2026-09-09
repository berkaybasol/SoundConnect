package com.berkayb.soundconnect.modules.event.repository;

import com.berkayb.soundconnect.modules.event.entity.Event;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.location.entity.District;
import com.berkayb.soundconnect.modules.location.entity.Neighborhood;
import com.berkayb.soundconnect.modules.venue.entity.Venue;
import com.berkayb.soundconnect.modules.venue.enums.VenueStatus; // enum paketine göre düzenle
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.hibernate.SessionFactory;
import com.berkayb.soundconnect.modules.event.mapper.EventMapper;
import com.berkayb.soundconnect.modules.event.support.EventShareUrlBuilder;
import com.berkayb.soundconnect.modules.media.service.MediaAssetService;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.sql.SQLException;
import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Uses the real JSONB-capable database and never imports the application datasource. */
@DataJpaTest(properties = {
		"spring.config.location=classpath:/application-test.yml", "spring.config.import=",
		"spring.jpa.hibernate.ddl-auto=create-drop",
		"spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
		"spring.jpa.properties.hibernate.generate_statistics=true",
		"spring.jpa.properties.hibernate.hbm2ddl.halt_on_error=true"
})
@ActiveProfiles("test")
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = EventRepositoryTest.RepositoryConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class EventRepositoryTest {
	@Container
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine")
			.withDatabaseName("event_repository_test").withUsername("event_repository_test")
			.withPassword("event_repository_test").withReuse(false);

	@Autowired
	private DataSource dataSource;
	
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
	void setUp() throws SQLException {
		assertThat(POSTGRES.isRunning()).isTrue();
		try (var connection = dataSource.getConnection()) {
			assertThat(connection.getMetaData().getURL()).isEqualTo(POSTGRES.getJdbcUrl());
			assertThat(connection.getCatalog()).isEqualTo(POSTGRES.getDatabaseName());
		}
		User owner = User.builder().username("event_owner").email("eventowner@example.test").password("unused")
				.status(UserStatus.ACTIVE).emailVerified(true).build();
		entityManager.persist(owner);
		
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
		             .owner(owner)
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
		                  .owner(owner)
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

	@ParameterizedTest
	@ValueSource(strings = {"pendingVenue", "inactiveOwner", "unverifiedOwner", "cityMismatch", "neighborhoodMismatch", "missingOwner"})
	void everyPublicReadHidesIneligibleEventsWhileOwnerCanStillManageThem(String condition) {
		switch (condition) {
			case "pendingVenue" -> venue.setStatus(VenueStatus.PENDING);
			case "inactiveOwner" -> venue.getOwner().setStatus(UserStatus.INACTIVE);
			case "unverifiedOwner" -> venue.getOwner().setEmailVerified(false);
			case "cityMismatch" -> {
				City otherCity = entityManager.persist(City.builder().name("Other city").build());
				venue.setCity(otherCity);
			}
			case "neighborhoodMismatch" -> {
				District otherDistrict = entityManager.persist(District.builder().name("Other district").city(city).build());
				venue.setNeighborhood(entityManager.persist(Neighborhood.builder().name("Other neighborhood").district(otherDistrict).build()));
			}
			case "missingOwner" -> venue.setOwner(null);
			default -> throw new IllegalArgumentException(condition);
		}
		entityManager.flush();
		LocalDate date = eventToday.getEventDate();

		assertThat(eventRepository.findPublicById(eventToday.getId())).isEmpty();
		for (List<Event> events : List.of(eventRepository.findByEventDate(date),
				eventRepository.findByVenue_City_Id(venue.getCity().getId()),
				eventRepository.findByVenue_District_Id(district.getId()),
				eventRepository.findByVenue_Neighborhood_Id(neighborhood.getId()),
				eventRepository.findByVenue(venue), eventRepository.findPublicByVenueBetween(venue, date, date))) {
			assertThat(events).extracting(Event::getId).doesNotContain(eventToday.getId());
		}
		assertThat(eventRepository.findByVenueOrderByEventDateAscStartTimeAsc(venue))
				.extracting(Event::getId).contains(eventToday.getId());
	}

	@Test
	void publicDetailAndWeeklyReadKeepEligibleEvents() {
		assertThat(eventRepository.findPublicById(eventToday.getId())).contains(eventToday);
		assertThat(eventRepository.findPublicByVenueBetween(venue, eventToday.getEventDate(), eventToday.getEventDate()))
				.containsExactly(eventToday);
	}

	@Test
	void publicCardMappingFetchesVenueAndLocationInTheEventQueryWithoutOsiv() {
		LocalDate date = eventToday.getEventDate();
		entityManager.clear();
		var statistics = entityManager.getEntityManager().getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
		statistics.clear();

		var events = eventRepository.findByEventDate(date);
		entityManager.clear();
		var mapper = new EventMapper(mock(MediaAssetService.class), new EventShareUrlBuilder("https://soundconnect.test"));
		var cards = mapper.toDtos(events);

		assertThat(cards).hasSize(2).allSatisfy(card -> {
			assertThat(card.venueName()).isNotBlank();
			assertThat(card.venueCity()).isEqualTo("Ankara");
			assertThat(card.venueDistrict()).isEqualTo("Çankaya");
			assertThat(card.venueNeighborhood()).isEqualTo("Kızılay");
		});
		assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
	}

	@Configuration(proxyBeanMethods = false)
	@EnableJpaAuditing
	@EnableJpaRepositories(basePackageClasses = EventRepository.class)
	@EntityScan(basePackages = "com.berkayb.soundconnect")
	static class RepositoryConfiguration {
		@Bean
		DataSource dataSource() {
			if (!POSTGRES.isRunning()) throw new IllegalStateException("Disposable PostgreSQL must be running");
			return new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
		}
	}
}

package com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.repository;

import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.entity.ArtistVenueConnectionRequest;
import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.enums.RequestStatus;
import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.enums.RequestByType;
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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The datasource is constructed from this container, never external/local configuration. */
@DataJpaTest(properties = {
		"spring.config.location=classpath:/application-test.yml",
		"spring.config.import=",
		"spring.jpa.hibernate.ddl-auto=create-drop",
		"spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect"
})
@ActiveProfiles("test")
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = ArtistVenueConnectionRequestRepositoryTest.RepositoryTestConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Tag("repo")
class ArtistVenueConnectionRequestRepositoryTest {
	@Container
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine")
			.withDatabaseName("artist_venue_repository_test")
			.withUsername("repository_test").withPassword("repository_test").withReuse(false);

	@org.springframework.beans.factory.annotation.Autowired
	DataSource dataSource;
	
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
	void setup() throws SQLException {
		// Check the actual connection before any fixture write. Each test rolls back;
		// broad repository deletes are unnecessary even in the disposable database.
		assertThat(POSTGRES.isRunning()).isTrue();
		try (var connection = dataSource.getConnection()) {
			assertThat(connection.getMetaData().getURL()).isEqualTo(POSTGRES.getJdbcUrl());
			assertThat(connection.getCatalog()).isEqualTo(POSTGRES.getDatabaseName());
		}
		
		// location
		City city = cityRepo.save(City.builder().name("C_" + UUID.randomUUID()).build());
		District district = districtRepo.save(District.builder().name("D1").city(city).build());
		Neighborhood nhood = neighborhoodRepo.save(Neighborhood.builder().name("N1").district(district).build());
		
		// users
		User owner = userRepo.save(User.builder()
		                               .username("owner_" + UUID.randomUUID().toString().substring(0, 12))
		                               .email("owner_" + UUID.randomUUID() + "@sc.test") // -> eklendi
		                               .password("pw")
		                               .provider(AuthProvider.LOCAL)
		                               .emailVerified(true)
		                               .city(city)
		                               .build());
		
		User musicianUser = userRepo.save(User.builder()
		                                      .username("artist_" + UUID.randomUUID().toString().substring(0, 12))
		                                      .email("artist_" + UUID.randomUUID() + "@sc.test") // -> eklendi
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
		                                             .requestByType(RequestByType.ARTIST)
		                                             .status(RequestStatus.PENDING)
		                                             .message("hey")
		                                             .build());
		
		requestRepo.save(ArtistVenueConnectionRequest.builder()
		                                             .musicianProfile(mp)
		                                             .venue(venue)
		                                             .requestByType(RequestByType.VENUE)
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

	@Configuration(proxyBeanMethods = false)
	@EnableJpaAuditing
	@EnableJpaRepositories(basePackageClasses = {
			ArtistVenueConnectionRequestRepository.class, MusicianProfileRepository.class,
			VenueRepository.class, UserRepository.class, CityRepository.class
	})
	@EntityScan(basePackages = "com.berkayb.soundconnect")
	static class RepositoryTestConfiguration {
		@Bean
		DataSource dataSource() {
			if (!POSTGRES.isRunning()) throw new IllegalStateException("Disposable PostgreSQL must be running");
			// Deliberately not @ConfigurationProperties: environment variables, local
			// secrets and Hikari JDBC overrides cannot redirect schema/fixture writes.
			return new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
		}
	}
}

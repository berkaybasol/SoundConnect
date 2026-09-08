package com.berkayb.soundconnect.modules.event.discovery;

import com.berkayb.soundconnect.modules.event.entity.Event;
import com.berkayb.soundconnect.modules.event.enums.EventOrigin;
import com.berkayb.soundconnect.modules.event.enums.EventPerformerApprovalStatus;
import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.location.entity.District;
import com.berkayb.soundconnect.modules.location.entity.Neighborhood;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity.Band;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.entity.MusicianProfile;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.modules.venue.entity.Venue;
import com.berkayb.soundconnect.modules.venue.enums.VenueStatus;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** Explicit disposable datasource: tests cannot accidentally connect to the user's application database. */
@DataJpaTest(properties = {
        "spring.config.location=classpath:/application-test.yml", "spring.config.import=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
        "spring.jpa.properties.hibernate.generate_statistics=true"
})
@ActiveProfiles("test")
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = EventDiscoveryRepositoryTest.RepositoryConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class EventDiscoveryRepositoryTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine")
            .withDatabaseName("event_discovery_test").withUsername("discovery_test")
            .withPassword("discovery_test").withReuse(false);

    @Autowired DataSource dataSource;
    @Autowired EntityManager em;
    @Autowired EventDiscoveryRepository repository;
    City city;
    District district;
    Neighborhood neighborhood;
    Venue venue;
    LocalDate date = LocalDate.of(2026, 9, 8);

    @BeforeEach
    void setUp() throws SQLException {
        assertThat(POSTGRES.isRunning()).isTrue();
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getURL()).isEqualTo(POSTGRES.getJdbcUrl());
            assertThat(connection.getCatalog()).isEqualTo(POSTGRES.getDatabaseName());
        }
        city = persist(City.builder().name("Ankara").build());
        district = persist(District.builder().name("Çankaya").city(city).build());
        neighborhood = persist(Neighborhood.builder().name("Çayyolu").district(district).build());
        venue = newVenue();
    }

    @Test
    void selectedDayIncludesFinishedEventsButNeitherAdjacentDay() {
        var midnight = event(venue, date, LocalTime.MIDNIGHT);
        midnight.setEndTime(LocalTime.of(1, 0));
        var lastMinute = event(venue, date, LocalTime.of(23, 59));
        event(venue, date.minusDays(1), LocalTime.of(23, 59));
        event(venue, date.plusDays(1), LocalTime.MIDNIGHT);
        em.flush();
        assertThat(repository.findEvents(date, city.getId(), null, null, PageRequest.of(0, 20)).getContent())
                .extracting(EventDiscoveryRow::id).containsExactly(midnight.getId(), lastMinute.getId());
    }

    @Test
    void onlyApprovedVenuesWithActiveVerifiedOwnersAppear() {
        var visible = event(venue, date, LocalTime.NOON);
        var pendingVenue = newVenue();
        pendingVenue.setStatus(VenueStatus.PENDING);
        event(pendingVenue, date, LocalTime.NOON);
        var inactiveVenue = newVenue();
        inactiveVenue.getOwner().setStatus(UserStatus.INACTIVE);
        event(inactiveVenue, date, LocalTime.NOON);
        var unverifiedVenue = newVenue();
        unverifiedVenue.getOwner().setEmailVerified(false);
        event(unverifiedVenue, date, LocalTime.NOON);
        em.flush();
        assertThat(repository.findEvents(date, city.getId(), null, null, PageRequest.of(0, 20)).getContent())
                .extracting(EventDiscoveryRow::id).containsExactly(visible.getId());
    }

    @Test
    void conjunctiveFiltersNeverEscapeSelectedCityOrLocationHierarchy() {
        var visible = event(venue, date, LocalTime.NOON);
        var foreignCity = persist(City.builder().name("İstanbul").build());
        var foreignDistrict = persist(District.builder().name("Kadıköy").city(foreignCity).build());
        var foreignNeighborhood = persist(Neighborhood.builder().name("Moda").district(foreignDistrict).build());
        var foreign = newVenue();
        foreign.setCity(foreignCity); foreign.setDistrict(foreignDistrict); foreign.setNeighborhood(foreignNeighborhood);
        event(foreign, date, LocalTime.NOON);
        var corruptLocation = newVenue();
        corruptLocation.setDistrict(foreignDistrict); corruptLocation.setNeighborhood(foreignNeighborhood);
        event(corruptLocation, date, LocalTime.NOON);
        em.flush();
        assertThat(repository.findEvents(date, city.getId(), null, null, PageRequest.of(0, 20)).getContent())
                .extracting(EventDiscoveryRow::id).containsExactly(visible.getId());
        assertThat(repository.findEvents(date, city.getId(), foreignDistrict.getId(), foreignNeighborhood.getId(), PageRequest.of(0, 20))).isEmpty();
        assertThat(repository.findEvents(date, city.getId(), district.getId(), foreignNeighborhood.getId(), PageRequest.of(0, 20))).isEmpty();
        assertThat(repository.findEvents(date, UUID.randomUUID(), null, null, PageRequest.of(0, 20))).isEmpty();
        assertThat(repository.findEvents(date, city.getId(), district.getId(), neighborhood.getId(), PageRequest.of(0, 20)).getContent())
                .extracting(EventDiscoveryRow::id).containsExactly(visible.getId());
    }

    @Test
    void consentSafeIdsArePreservedAndLegacyMusicianOriginIsExcluded() {
        var musician = persist(MusicianProfile.builder().user(user()).stageName("Stage").build());
        var band = persist(Band.builder().name("Şahbaz").build());
        var linkedMusician = event(venue, date, LocalTime.of(10, 0));
        linkedMusician.setMusicianProfile(musician);
        linkedMusician.setPerformerApprovalStatus(EventPerformerApprovalStatus.APPROVED);
        var linkedBand = event(venue, date, LocalTime.of(11, 0));
        linkedBand.setBand(band);
        linkedBand.setPerformerApprovalStatus(EventPerformerApprovalStatus.APPROVED);
        var pending = event(venue, date, LocalTime.of(12, 0));
        pending.setManualPerformerName("Invited band");
        pending.setPerformerApprovalStatus(EventPerformerApprovalStatus.PENDING);
        var rejected = event(venue, date, LocalTime.of(13, 0));
        rejected.setManualPerformerName("Rejected musician");
        rejected.setPerformerApprovalStatus(EventPerformerApprovalStatus.REJECTED);
        // Origin is immutable: set it before persistence, unlike the mutable fields above.
        persist(Event.builder().title("Legacy musician event").venue(venue).eventDate(date).startTime(LocalTime.NOON)
                .eventOrigin(EventOrigin.MUSICIAN).organizerUserId(musician.getUser().getId()).musicianProfile(musician)
                .performerApprovalStatus(EventPerformerApprovalStatus.APPROVED).profileCalendarApproved(true).build());
        em.flush();
        var rows = repository.findEvents(date, city.getId(), null, null, PageRequest.of(0, 20)).getContent();
        assertThat(rows).hasSize(4);
        assertThat(rows.getFirst().musicianProfileId()).isEqualTo(musician.getId());
        assertThat(rows.get(1).bandId()).isEqualTo(band.getId());
        for (var row : rows.subList(2, 4)) {
            assertThat(row.bandId()).isNull();
            assertThat(row.musicianProfileId()).isNull();
            assertThat(row.manualPerformerName()).isNotBlank();
        }
    }

    @Test
    void pagingHasStableTieBreaksAccurateCountsAndNoEntityGraphLoads() {
        for (int i = 0; i < 23; i++) event(venue, date, LocalTime.NOON);
        em.flush(); em.clear();
        var statistics = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        var first = repository.findEvents(date, city.getId(), null, null, PageRequest.of(0, 10));
        assertThat(first.getContent()).hasSize(10);
        assertThat(first.getTotalElements()).isEqualTo(23);
        assertThat(first.getTotalPages()).isEqualTo(3);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
        assertThat(statistics.getEntityLoadCount()).isZero();
        assertThat(statistics.getCollectionLoadCount()).isZero();
        var second = repository.findEvents(date, city.getId(), null, null, PageRequest.of(1, 10));
        assertThat(first.getContent()).doesNotContainAnyElementsOf(second.getContent());
        assertThat(repository.findEvents(date, city.getId(), null, null, PageRequest.of(0, 10)).getContent())
                .isEqualTo(first.getContent());
        var third = repository.findEvents(date, city.getId(), null, null, PageRequest.of(2, 10));
        assertThat(third.getContent()).hasSize(3);
        assertThat(third.isLast()).isTrue();
        var beyond = repository.findEvents(date, city.getId(), null, null, PageRequest.of(1000, 10));
        assertThat(beyond.getContent()).isEmpty();
        assertThat(beyond.getTotalElements()).isEqualTo(23);
        assertThat(beyond.isLast()).isTrue();
    }

    @Test
    void tenThousandEventsKeepFirstMiddleAndLastPagesBoundedWithStableOrdering() {
        var secondVenue = newVenue();
        // The datasource is verified against the disposable container in setUp before any fixture write.
        // One SQL insert avoids building/hydrating 10,000 entities in the test process.
        em.flush();
        int inserted = em.createNativeQuery("""
                insert into tbl_event (
                    id, title, event_date, start_time, venue_id, organizer_user_id,
                    event_origin, venue_approval_status, venue_calendar_approved,
                    performer_approval_status, profile_calendar_approved, profile_publication_version)
                select cast('00000000-0000-0000-0000-' || lpad(n::text, 12, '0') as uuid),
                    'Fixture ' || n, cast(:eventDate as date),
                    time '00:00:00' + ((n - 1) / 1000) * interval '1 minute',
                    case when n % 2 = 0 then cast(:firstVenue as uuid) else cast(:secondVenue as uuid) end,
                    case when n % 2 = 0 then cast(:firstOwner as uuid) else cast(:secondOwner as uuid) end,
                    'VENUE', 'APPROVED', true, 'NOT_REQUIRED', false, 0
                from generate_series(1, 10000) as fixture(n)
                """)
                .setParameter("eventDate", date)
                .setParameter("firstVenue", venue.getId())
                .setParameter("secondVenue", secondVenue.getId())
                .setParameter("firstOwner", venue.getOwner().getId())
                .setParameter("secondOwner", secondVenue.getOwner().getId())
                .executeUpdate();
        assertThat(inserted).isEqualTo(10_000);
        em.flush(); em.clear();
        var statistics = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        for (int page : new int[] {0, 250, 499}) {
            statistics.clear();
            var result = repository.findEvents(date, city.getId(), null, null, PageRequest.of(page, 20));
            assertThat(result.getContent()).hasSize(20);
            assertThat(result.getNumber()).isEqualTo(page);
            assertThat(result.getTotalElements()).isEqualTo(10_000);
            assertThat(result.getTotalPages()).isEqualTo(500);
            assertThat(result.isLast()).isEqualTo(page == 499);
            assertThat(result.getContent()).extracting(EventDiscoveryRow::id)
                    .containsExactlyElementsOf(IntStream.rangeClosed(page * 20 + 1, page * 20 + 20)
                            .mapToObj(n -> UUID.fromString("00000000-0000-0000-0000-" + String.format("%012d", n)))
                            .toList());
            assertThat(result.getContent()).extracting(EventDiscoveryRow::startTime)
                    .containsOnly(LocalTime.MIDNIGHT.plusMinutes(page * 20 / 1000));
            assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
            assertThat(statistics.getEntityLoadCount()).isZero();
            assertThat(statistics.getCollectionLoadCount()).isZero();
        }
    }

    private Event event(Venue target, LocalDate day, LocalTime start) {
        return persist(Event.builder().title("Event " + UUID.randomUUID()).venue(target).eventDate(day).startTime(start).build());
    }

    private Venue newVenue() {
        return persist(Venue.builder().name("Venue " + UUID.randomUUID()).owner(user()).status(VenueStatus.APPROVED)
                .address("Address").city(city).district(district).neighborhood(neighborhood).build());
    }

    private User user() {
        return persist(User.builder().username("user" + UUID.randomUUID().toString().replace("-", "").substring(0, 10))
                .email(UUID.randomUUID() + "@test.invalid").password("unused-test-password")
                .status(UserStatus.ACTIVE).emailVerified(true).build());
    }

    private <T> T persist(T entity) { em.persist(entity); return entity; }

    @Configuration(proxyBeanMethods = false)
    @EnableJpaAuditing
    @EnableJpaRepositories(basePackageClasses = EventDiscoveryRepository.class)
    @EntityScan(basePackages = "com.berkayb.soundconnect")
    static class RepositoryConfiguration {
        @Bean DataSource dataSource() {
            if (!POSTGRES.isRunning()) throw new IllegalStateException("Disposable PostgreSQL must be running");
            return new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        }
    }
}

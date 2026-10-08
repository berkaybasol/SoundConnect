package com.berkayb.soundconnect.modules.event.management;

import com.berkayb.soundconnect.modules.event.entity.Event;
import com.berkayb.soundconnect.modules.event.repository.EventRepository;
import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.location.entity.District;
import com.berkayb.soundconnect.modules.location.entity.Neighborhood;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.venue.entity.Venue;
import com.berkayb.soundconnect.modules.venue.enums.VenueStatus;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import javax.sql.DataSource;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.TimeZone;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {"spring.config.location=classpath:/application-test.yml", "spring.config.import=",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.properties.hibernate.jdbc.time_zone=UTC",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
        "spring.jpa.properties.hibernate.hbm2ddl.halt_on_error=true"})
@ActiveProfiles("test")
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = EventManagementHibernatePostgresTest.Config.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class EventManagementHibernatePostgresTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine")
            .withDatabaseName("event_history_hibernate_test").withUsername("history_test").withPassword("history_test").withReuse(false);
    private static TimeZone originalZone;
    @BeforeAll static void useProductionJvmAndJdbcMismatch() {
        originalZone = TimeZone.getDefault(); TimeZone.setDefault(TimeZone.getTimeZone("Europe/Istanbul"));
    }
    @AfterAll static void restoreZone() { TimeZone.setDefault(originalZone); }
    @Autowired TestEntityManager em;
    @Autowired EventRepository events;
    @Autowired EventManagementRepository management;
    @Autowired EventManagementTimeStorage timeStorage;
    @Autowired DataSource dataSource;
    private Venue venue;
    private final LocalDate date = LocalDate.of(2026, 9, 21);

    @BeforeEach void fixture() {
        assertThat(POSTGRES.isRunning()).isTrue();
        var owner = em.persist(User.builder().username("history_owner").email("history@example.test").password("unused").build());
        var city = em.persist(City.builder().name("Ankara").build());
        var district = em.persist(District.builder().name("Çankaya").city(city).build());
        var neighborhood = em.persist(Neighborhood.builder().name("Kızılay").district(district).build());
        venue = em.persist(Venue.builder().owner(owner).name("History venue").address("Test").phone("05000000000")
                .city(city).district(district).neighborhood(neighborhood).status(VenueStatus.APPROVED).build());
    }

    @Test void nativeCutoffMatchesRealHibernateReadDespiteUtcStoredTime() {
        var event = persist(date, LocalTime.of(20, 0), LocalTime.of(22, 0));
        em.flush(); em.clear();
        assertThat(new JdbcTemplate(dataSource).queryForObject("select start_time::text from tbl_event where id = ?", String.class, event))
                .isEqualTo("18:00:00");
        assertThat(timeStorage.sqlInterval()).isEqualTo("7200 seconds");
        assertThat(events.findById(event).orElseThrow().getStartTime()).isEqualTo(LocalTime.of(20, 0));
        assertThat(management.upcomingIds(venue.getId(), date.atTime(21, 0))).containsExactly(event);
        assertThat(management.pastCount(venue.getId(), date.atTime(21, 0))).isZero();
        assertThat(management.pastPositions(venue.getId(), date.atTime(22, 1), null, 21)).extracting(EventHistoryCursor::id).containsExactly(event);
    }

    @Test void midnightWrappedTimesUseLogicalOrderAndCursorAfterEarlierDeletion() {
        UUID early = persist(date, LocalTime.of(1, 30), LocalTime.of(2, 30));
        UUID later = persist(date, LocalTime.of(3, 30), LocalTime.of(4, 30));
        em.flush(); em.clear();
        var first = management.pastPositions(venue.getId(), date.atTime(12, 0), null, 1);
        assertThat(first).extracting(EventHistoryCursor::id).containsExactly(later);
        assertThat(first.getFirst().startTime()).isEqualTo(LocalTime.of(3, 30));
        events.deleteById(later); em.flush();
        assertThat(management.pastPositions(venue.getId(), date.atTime(12, 0), first.getFirst(), 21))
                .extracting(EventHistoryCursor::id).containsExactly(early);
    }

    @Test void midnightFallbackIsStillUpcomingUntilItsLogicalEnd() {
        UUID event = persist(date.minusDays(1), LocalTime.of(23, 30), null);
        em.flush(); em.clear();
        assertThat(management.upcomingIds(venue.getId(), date.atTime(0, 30))).containsExactly(event);
        assertThat(management.pastCount(venue.getId(), date.atTime(0, 30))).isZero();
        assertThat(management.pastCount(venue.getId(), date.atTime(0, 31))).isEqualTo(1);
    }

    @Test void timeAdapterUsesReferenceDateAndSupportsUtcJvmWithoutAHardcodedShift() {
        TimeZone current = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            assertThat(EventManagementTimeStorage.offsetSeconds(TimeZone.getTimeZone("UTC"))).isZero();
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/Istanbul"));
            assertThat(EventManagementTimeStorage.offsetSeconds(TimeZone.getTimeZone("UTC"))).isEqualTo(7200);
            assertThat(EventManagementTimeStorage.offsetSeconds(null)).isZero();
        } finally { TimeZone.setDefault(current); }
    }

    private UUID persist(LocalDate day, LocalTime start, LocalTime end) {
        return em.persist(Event.builder().title("History").eventDate(day).startTime(start).endTime(end).venue(venue).build()).getId();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableJpaAuditing
    @EnableJpaRepositories(basePackageClasses = EventRepository.class)
    @EntityScan(basePackages = "com.berkayb.soundconnect")
    @Import({EventManagementRepository.class, EventManagementTimeStorage.class})
    static class Config {
        @Bean DataSource dataSource() {
            if (!POSTGRES.isRunning()) throw new IllegalStateException("Disposable PostgreSQL must be running");
            return new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        }
        @Bean NamedParameterJdbcTemplate managementJdbc(DataSource source) { return new NamedParameterJdbcTemplate(source); }
    }
}

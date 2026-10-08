package com.berkayb.soundconnect.modules.event.plan;

import com.berkayb.soundconnect.modules.event.mapper.EventMapper;
import com.berkayb.soundconnect.modules.event.performer.outbox.EventPerformerNotificationOutboxPublisher;
import com.berkayb.soundconnect.modules.event.performer.service.EventPerformerRequestService;
import com.berkayb.soundconnect.modules.event.repository.EventRepository;
import com.berkayb.soundconnect.modules.event.support.EventScheduleClock;
import com.berkayb.soundconnect.modules.event.support.EventShareUrlBuilder;
import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.location.entity.District;
import com.berkayb.soundconnect.modules.location.entity.Neighborhood;
import com.berkayb.soundconnect.modules.role.entity.Role;
import com.berkayb.soundconnect.modules.media.repository.MediaAssetRepository;
import com.berkayb.soundconnect.modules.media.repository.MediaAssetReferenceRepository;
import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.repository.MusicianProfileRepository;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.entity.MusicianProfile;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.repository.BandRepository;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity.Band;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity.BandMember;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.enums.BandMemberShipStatus;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.enums.BandRole;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.support.BandRepresentationPolicy;
import com.berkayb.soundconnect.modules.profile.VenueProfile.repository.VenueProfileRepository;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.modules.venue.entity.Venue;
import com.berkayb.soundconnect.modules.venue.enums.VenueStatus;
import com.berkayb.soundconnect.modules.venue.repository.VenueRepository;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.doThrow;
import static org.mockito.ArgumentMatchers.anyList;

/** Uses only an explicitly wired, disposable database; no application datasource is imported. */
@DataJpaTest(properties = {
        "spring.config.location=classpath:/application-test.yml", "spring.config.import=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
        "spring.jpa.properties.hibernate.hbm2ddl.halt_on_error=true"
})
@ActiveProfiles("test")
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = EventPlanPostgresTest.Config.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(45)
class EventPlanPostgresTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine")
            .withDatabaseName("event_plan_isolated_test").withUsername("plan_test")
            .withPassword("plan_test").withReuse(false);

    @Autowired DataSource dataSource;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager transactions;
    @Autowired EventPlanService service;
    @Autowired TestClock clock;
    @Autowired MediaAssetReferenceRepository mediaReferences;
    @Autowired EventPerformerNotificationOutboxPublisher notifications;
    private JdbcTemplate jdbc;
    private UUID ownerId;
    private UUID venueId;
    private static boolean migrationInstalled;
    private final LocalDate today = LocalDate.of(2026, 9, 21);

    @BeforeEach
    void fixture() throws Exception {
        assertThat(POSTGRES.isRunning()).isTrue();
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getURL()).isEqualTo(POSTGRES.getJdbcUrl());
            assertThat(connection.getCatalog()).isEqualTo(POSTGRES.getDatabaseName());
        }
        jdbc = new JdbcTemplate(dataSource);
        reset(notificationMock());
        if (!migrationInstalled) {
            // Replace only the new aggregate tables so all service tests use the actual migration's constraints.
            jdbc.execute("drop table event_plan_occurrences");
            jdbc.execute("drop table event_plans");
            jdbc.execute(Files.readString(Path.of("scripts/db/2026-09-21-event-plans.sql")));
            migrationInstalled = true;
        }
        clock.now.set(Instant.parse("2026-09-21T08:00:00Z"));
        tx(() -> {
            var role = em.createQuery("select r from Role r where r.name='ROLE_VENUE'", Role.class)
                    .getResultStream().findFirst().orElseGet(() -> persist(Role.builder().name("ROLE_VENUE").build()));
            var owner = persist(User.builder().username("plan" + UUID.randomUUID().toString().replace("-", "").substring(0, 15))
                    .email(UUID.randomUUID() + "@test.invalid").password("unused-test-password")
                    .roles(Set.of(role)).status(UserStatus.ACTIVE).emailVerified(true).build());
            ownerId = owner.getId();
            var city = persist(City.builder().name("Plan city " + UUID.randomUUID()).build());
            var district = persist(District.builder().name("Plan district " + UUID.randomUUID()).city(city).build());
            var neighborhood = persist(Neighborhood.builder().name("Plan neighborhood " + UUID.randomUUID()).district(district).build());
            venueId = persist(Venue.builder().name("Plan venue " + UUID.randomUUID()).owner(owner)
                    .status(VenueStatus.APPROVED).address("Isolated test address")
                    .city(city).district(district).neighborhood(neighborhood).build()).getId();
            return null;
        });
    }

    @Test
    void fractionalTimesAreRejectedBeforePersistenceWhileWholeSecondsRoundTripExactly() {
        for (var template : List.of(
                new EventPlanTemplate("Precise schedule", null, LocalTime.of(20, 0, 0, 123456789),
                        LocalTime.of(22, 0), null, null, null, "Performer"),
                new EventPlanTemplate("Precise schedule", null, LocalTime.of(20, 0),
                        LocalTime.of(22, 0, 0, 999999999), null, null, null, "Performer"))) {
            var invalid = new EventPlanDefinition(venueId, today, null, List.of(1, 4), List.of(), template);
            assertThatThrownBy(() -> service.create(ownerId, new EventPlanCreateRequest(UUID.randomUUID(), invalid)))
                    .isInstanceOfSatisfying(SoundConnectException.class,
                            error -> assertThat(error.getErrorType()).isEqualTo(ErrorType.INVALID_PARAMETER));
        }
        assertThat(jdbc.queryForObject("select count(*) from event_plans where organizer_user_id=?", Long.class, ownerId)).isZero();
        var precise = new EventPlanTemplate("Precise schedule", null, LocalTime.of(20, 0, 17),
                LocalTime.of(22, 0, 53), null, null, null, "Performer");
        var requested = new EventPlanDefinition(venueId, today, null, List.of(1, 4), List.of(), precise);
        var created = service.create(ownerId, new EventPlanCreateRequest(UUID.randomUUID(), requested));
        assertThat(service.getOwner(ownerId, created.id()).definition()).isEqualTo(requested);
    }

    @Test
    void quotaAndOwnerOrderingUseEffectiveCompletionWithoutWaitingForTheScheduler() {
        var endingToday = definition(today, today, List.of(today.getDayOfWeek().getValue()));
        var finished = new ArrayList<EventPlanResponse>();
        for (int index = 0; index < 50; index++) {
            finished.add(service.create(ownerId, new EventPlanCreateRequest(UUID.randomUUID(), endingToday)));
        }
        var tomorrow = today.plusDays(1);
        var upcoming = definition(tomorrow, tomorrow, List.of(tomorrow.getDayOfWeek().getValue()));
        assertPlanLimit(() -> service.create(ownerId, new EventPlanCreateRequest(UUID.randomUUID(), upcoming)));

        // The final scheduled start is exclusive. These records still say ACTIVE in
        // PostgreSQL, but cannot consume the account's active-program allowance.
        clock.now.set(Instant.parse("2026-09-21T17:00:00Z"));
        var active = new ArrayList<EventPlanResponse>();
        for (int index = 0; index < 50; index++) {
            active.add(service.create(ownerId, new EventPlanCreateRequest(UUID.randomUUID(), upcoming)));
        }
        assertThat(jdbc.queryForObject("select count(*) from event_plans where organizer_user_id=? and status='ACTIVE'",
                Long.class, ownerId)).isEqualTo(100);
        assertThat(service.getOwnerPlans(ownerId, venueId, 0, 50).content())
                .extracting(EventPlanResponse::id).containsExactlyInAnyOrderElementsOf(active.stream().map(EventPlanResponse::id).toList());
        assertThat(service.getOwnerPlans(ownerId, venueId, 1, 50).content())
                .allMatch(plan -> plan.status() == EventPlanStatus.COMPLETED);
        assertPlanLimit(() -> service.create(ownerId, new EventPlanCreateRequest(UUID.randomUUID(), upcoming)));
        assertPlanLimit(() -> service.update(ownerId, finished.getFirst().id(), new EventPlanUpdateRequest(0L, upcoming)));

        service.stop(ownerId, active.getFirst().id(), new EventPlanStopRequest(0L, false));
        var extended = service.update(ownerId, finished.getFirst().id(), new EventPlanUpdateRequest(0L, upcoming));
        assertThat(extended.status()).isEqualTo(EventPlanStatus.ACTIVE);
        assertPlanLimit(() -> service.create(ownerId, new EventPlanCreateRequest(UUID.randomUUID(), upcoming)));
    }

    private void assertPlanLimit(Runnable attempt) {
        assertThatThrownBy(attempt::run).isInstanceOfSatisfying(SoundConnectException.class,
                error -> assertThat(error.getErrorType()).isEqualTo(ErrorType.EVENT_PLAN_LIMIT_REACHED));
    }

    @Test
    void sameRequestIsIdempotentButDifferentPayloadCannotReuseItsIdentity() {
        UUID requestId = UUID.randomUUID();
        var definition = definition(today, null, List.of(1, 4));
        var first = service.create(ownerId, new EventPlanCreateRequest(requestId, definition));
        var before = generated(first.id());
        var retry = service.create(ownerId, new EventPlanCreateRequest(requestId, definition));
        assertThat(retry.id()).isEqualTo(first.id());
        assertThat(generated(first.id())).isEqualTo(before);
        assertThatThrownBy(() -> service.create(ownerId, new EventPlanCreateRequest(requestId,
                definition(today, null, List.of(2, 5))))).isInstanceOf(SoundConnectException.class);
        assertThat(generated(first.id())).isEqualTo(before);
    }

    @Test
    void ownerPagesPutActivePlansBeforeHistoryWithStableUpdatedOrderAndVenueIsolation() {
        // Far-future starts keep this pagination fixture independent of event generation.
        var definition = definition(today.plusDays(60), null, List.of(1, 4));
        var plans = new ArrayList<EventPlanResponse>();
        for (int index = 0; index < 6; index++) {
            plans.add(service.create(ownerId, new EventPlanCreateRequest(UUID.randomUUID(), definition)));
        }
        String[] statuses = {"ACTIVE", "ACTIVE", "ACTIVE", "STOPPED", "COMPLETED", "STOPPED"};
        String[] updatedTimes = {"2026-09-21 09:00:00", "2026-09-21 10:00:00", "2026-09-21 10:00:00",
                "2026-09-21 14:00:00", "2026-09-21 13:00:00", "2026-09-21 12:00:00"};
        for (int index = 0; index < plans.size(); index++) {
            jdbc.update("update event_plans set status=?,updated_at=? where id=?",
                    statuses[index], java.sql.Timestamp.valueOf(updatedTimes[index]), plans.get(index).id());
        }
        UUID otherVenue = tx(() -> {
            Venue source = em.find(Venue.class, venueId);
            return persist(Venue.builder().name("Other venue " + UUID.randomUUID()).owner(source.getOwner())
                    .status(VenueStatus.APPROVED).address("Isolated test address")
                    .city(source.getCity()).district(source.getDistrict()).neighborhood(source.getNeighborhood()).build()).getId();
        });
        var foreignDefinition = new EventPlanDefinition(otherVenue, definition.startDate(), null,
                definition.weekdays(), definition.excludedDates(), definition.template());
        var foreign = service.create(ownerId, new EventPlanCreateRequest(UUID.randomUUID(), foreignDefinition));
        jdbc.update("update event_plans set updated_at=? where id=?",
                java.sql.Timestamp.valueOf("2026-09-21 15:00:00"), foreign.id());
        var expired = service.create(ownerId, new EventPlanCreateRequest(UUID.randomUUID(),
                definition(today, today, List.of(today.getDayOfWeek().getValue()))));
        jdbc.update("update event_plans set updated_at=? where id=?",
                java.sql.Timestamp.valueOf("2026-09-21 16:00:00"), expired.id());
        clock.now.set(Instant.parse("2026-09-21T18:00:00Z")); // Today's last event has started; no scheduler pass yet.
        assertThat(jdbc.queryForObject("select status from event_plans where id=?", String.class, expired.id()))
                .isEqualTo("ACTIVE");

        var tiedIds = List.of(plans.get(1).id(), plans.get(2).id()).stream()
                .sorted(Comparator.comparing(UUID::toString).reversed()).toList();
        var expected = new ArrayList<>(tiedIds);
        expected.add(plans.get(0).id());
        expected.add(expired.id());
        expected.add(plans.get(3).id());
        expected.add(plans.get(4).id());
        expected.add(plans.get(5).id());
        var seen = new ArrayList<UUID>();
        for (int page = 0; page < 4; page++) {
            var result = service.getOwnerPlans(ownerId, venueId, page, 2);
            assertThat(result.totalElements()).isEqualTo(7);
            assertThat(result.totalPages()).isEqualTo(4);
            assertThat(result.page()).isEqualTo(page);
            assertThat(result.first()).isEqualTo(page == 0);
            assertThat(result.last()).isEqualTo(page == 3);
            assertThat(result.content()).extracting(EventPlanResponse::id)
                    .containsExactlyElementsOf(expected.subList(page * 2, Math.min(page * 2 + 2, expected.size())));
            if (page == 1) assertThat(result.content().get(1).status()).isEqualTo(EventPlanStatus.COMPLETED);
            seen.addAll(result.content().stream().map(EventPlanResponse::id).toList());
        }
        assertThat(seen).doesNotHaveDuplicates().doesNotContain(foreign.id());
        assertThat(service.getOwnerPlans(ownerId, venueId, 0, 2).content()).extracting(EventPlanResponse::id)
                .containsExactlyElementsOf(tiedIds);
        assertThat(service.getOwnerPlans(ownerId, otherVenue, 0, 2).content()).extracting(EventPlanResponse::id)
                .containsExactly(foreign.id());
        // An empty effective-active set must still return all history in updated order.
        jdbc.update("update event_plans set status='STOPPED' where venue_id=?", venueId);
        assertThat(service.getOwnerPlans(ownerId, venueId, 0, 2).content()).extracting(EventPlanResponse::id)
                .containsExactly(expired.id(), plans.get(3).id());
    }

    @Test
    void updatePreviewUsesTheLedgerAndActualEventWindowWithoutChangingAnyPersistentState() {
        clock.now.set(Instant.parse("2026-09-14T05:00:00Z"));
        var earlyTemplate = new EventPlanTemplate("Morning program", null, LocalTime.of(10,0),
                LocalTime.of(22,0), null, null, null, "Manual performer");
        var definition = new EventPlanDefinition(venueId, today.minusDays(7), null,
                List.of(1,2,3,4,5,6,7), List.of(), earlyTemplate);
        var plan = service.create(ownerId, new EventPlanCreateRequest(UUID.randomUUID(), definition));
        // Both dates are before the later preview window and must not leak into its preserved list.
        plan = service.override(ownerId, plan.id(), today.minusDays(2),
                new EventPlanOverrideRequest(plan.version(), today.minusDays(1), earlyTemplate));
        // Only the actual date intersects the preview window.
        plan = service.override(ownerId, plan.id(), today.minusDays(1),
                new EventPlanOverrideRequest(plan.version(), today.plusDays(7), earlyTemplate));
        plan = service.override(ownerId, plan.id(), today.plusDays(1),
                new EventPlanOverrideRequest(plan.version(), today.plusDays(2), earlyTemplate));
        plan = service.skip(ownerId, plan.id(), today.plusDays(3), plan.version());
        UUID deletedEvent = generated(plan.id()).get(today.plusDays(4));
        jdbc.update("delete from tbl_event where id=?", deletedEvent);
        // Only the scheduled date intersects the preview window.
        plan = service.override(ownerId, plan.id(), today.plusDays(20),
                new EventPlanOverrideRequest(plan.version(), today.plusDays(40), earlyTemplate));
        clock.now.set(Instant.parse("2026-09-21T08:00:00Z")); // 11:00 Istanbul: today's original 10:00 event has started.
        service.generate(plan.id());
        String storedPlan = jdbc.queryForObject("select row_to_json(p)::text from event_plans p where id=?", String.class, plan.id());
        var storedLedger = jdbc.queryForList("select row_to_json(o)::text from event_plan_occurrences o where plan_id=? order by scheduled_date",
                String.class, plan.id());
        var storedEvents = jdbc.queryForList("select row_to_json(e)::text from tbl_event e join event_plan_occurrences o on o.event_id=e.id where o.plan_id=? order by e.id",
                String.class, plan.id());
        var changed = new EventPlanDefinition(venueId, today, null, List.of(1,2,3,4,5,6,7), List.of(),
                new EventPlanTemplate("Evening program", null, LocalTime.of(20,0), LocalTime.of(22,0), null, null, null, "Manual performer"));

        var preview = service.previewUpdate(ownerId, plan.id(), new EventPlanUpdateRequest(plan.version(), changed));

        assertThat(preview.dates()).hasSize(23).contains(today.plusDays(2), today.plusDays(7))
                .doesNotContain(today, today.plusDays(1), today.plusDays(3), today.plusDays(4), today.plusDays(20));
        assertThat(preview.preservedDates()).containsExactly(
                new EventPlanPreservedDate(today.minusDays(1), today.plusDays(7), EventPlanPreservationStatus.OVERRIDDEN),
                new EventPlanPreservedDate(today, today, EventPlanPreservationStatus.STARTED),
                new EventPlanPreservedDate(today.plusDays(1), today.plusDays(2), EventPlanPreservationStatus.OVERRIDDEN),
                new EventPlanPreservedDate(today.plusDays(3), today.plusDays(3), EventPlanPreservationStatus.SKIPPED),
                new EventPlanPreservedDate(today.plusDays(4), today.plusDays(4), EventPlanPreservationStatus.CANCELLED),
                new EventPlanPreservedDate(today.plusDays(20), today.plusDays(40), EventPlanPreservationStatus.OVERRIDDEN));
        assertThat(jdbc.queryForObject("select row_to_json(p)::text from event_plans p where id=?", String.class, plan.id())).isEqualTo(storedPlan);
        assertThat(jdbc.queryForList("select row_to_json(o)::text from event_plan_occurrences o where plan_id=? order by scheduled_date",
                String.class, plan.id())).isEqualTo(storedLedger);
        assertThat(jdbc.queryForList("select row_to_json(e)::text from tbl_event e join event_plan_occurrences o on o.event_id=e.id where o.plan_id=? order by e.id",
                String.class, plan.id())).isEqualTo(storedEvents);
        assertThat(service.preview(ownerId, changed).dates()).hasSize(28).contains(today, today.plusDays(1), today.plusDays(3));
    }

    @Test
    void concurrentRollingGenerationKeepsOneIdentityPerDateAndExtendsOnlyTheHorizon() throws Exception {
        var plan = service.create(ownerId, new EventPlanCreateRequest(UUID.randomUUID(),
                definition(today, null, List.of(1, 2, 3, 4, 5, 6, 7))));
        var initial = generated(plan.id());
        assertThat(initial).hasSize(28);
        assertThat(initial.keySet()).containsExactly(today.datesUntil(today.plusDays(28)).toArray(LocalDate[]::new));
        assertThat(initial.values()).doesNotHaveDuplicates().doesNotContain(plan.id());
        clock.now.set(Instant.parse("2026-09-28T08:00:00Z"));
        var release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(4)) {
            var runs = new ArrayList<java.util.concurrent.Future<?>>();
            for (int index = 0; index < 4; index++) {
                runs.add(executor.submit(() -> {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Start latch timed out");
                    service.generate(plan.id());
                    return null;
                }));
            }
            release.countDown();
            for (var run : runs) run.get(25, TimeUnit.SECONDS);
        }
        var expanded = generated(plan.id());
        assertThat(expanded).hasSize(35).containsAllEntriesOf(initial);
        assertThat(expanded.values()).doesNotHaveDuplicates();
        assertThat(expanded.keySet()).doesNotContain(today.minusDays(1), today.plusDays(35));
    }

    @Test
    void deletionAndSkipLeavePermanentOccurrenceTombstones() {
        var plan = service.create(ownerId, new EventPlanCreateRequest(UUID.randomUUID(),
                definition(today, today.plusDays(6), List.of(1, 2, 3, 4, 5, 6, 7))));
        var initial = generated(plan.id());
        UUID deletedEvent = initial.get(today.plusDays(1));
        // Exercises the real database FK, including deletes originating outside the plan service.
        jdbc.update("delete from tbl_event where id=?", deletedEvent);
        service.skip(ownerId, plan.id(), today.plusDays(2), plan.version());
        service.generate(plan.id());
        service.generate(plan.id());
        assertThat(jdbc.queryForObject("select count(*) from event_plan_occurrences where plan_id=?", Long.class, plan.id())).isEqualTo(7);
        assertThat(jdbc.queryForObject("select event_id from event_plan_occurrences where plan_id=? and scheduled_date=?",
                UUID.class, plan.id(), today.plusDays(1))).isNull();
        assertThat(jdbc.queryForObject("select status from event_plan_occurrences where plan_id=? and scheduled_date=?",
                String.class, plan.id(), today.plusDays(2))).isEqualTo("SKIPPED");
        assertThat(generated(plan.id())).doesNotContainKeys(today.plusDays(1), today.plusDays(2));
        assertThat(generated(plan.id()).get(today.plusDays(3))).isEqualTo(initial.get(today.plusDays(3)));
    }

    @Test
    void editingFutureSchedulePreservesRetainedDatesAndDoesNotResurrectRemovedDates() {
        var plan = service.create(ownerId, new EventPlanCreateRequest(UUID.randomUUID(),
                definition(today, today.plusDays(20), List.of(1, 4))));
        var initial = generated(plan.id());
        var updated = service.update(ownerId, plan.id(), new EventPlanUpdateRequest(plan.version(),
                definition(today, today.plusDays(20), List.of(1, 5))));
        var changed = generated(plan.id());
        assertThat(changed.get(today)).isEqualTo(initial.get(today));
        assertThat(changed.get(today.plusDays(7))).isEqualTo(initial.get(today.plusDays(7)));
        assertThat(changed).doesNotContainKey(today.plusDays(3));
        assertThat(changed).containsKey(today.plusDays(4));
        assertThat(jdbc.queryForObject("select status from event_plan_occurrences where plan_id=? and scheduled_date=?",
                String.class, plan.id(), today.plusDays(3))).isEqualTo("CANCELLED");
        service.update(ownerId, plan.id(), new EventPlanUpdateRequest(updated.version(),
                definition(today, today.plusDays(20), List.of(1, 4))));
        service.generate(plan.id());
        assertThat(generated(plan.id())).doesNotContainKey(today.plusDays(3));
    }

    @Test
    void stoppedPlanKeepsExistingEventsAndCannotGenerateAfterTheWindowMoves() {
        var plan = service.create(ownerId, new EventPlanCreateRequest(UUID.randomUUID(), definition(today, null, List.of(1, 4))));
        var initial = generated(plan.id());
        var stopped = service.stop(ownerId, plan.id(), new EventPlanStopRequest(plan.version(), false));
        assertThat(stopped.status()).isEqualTo(EventPlanStatus.STOPPED);
        clock.now.set(Instant.parse("2026-10-19T08:00:00Z"));
        service.generate(plan.id());
        assertThat(generated(plan.id())).isEqualTo(initial);
    }

    @Test
    void stopAndCancelPreservesStartedEventsAndCancelsOnlyFutureOccurrences() {
        var plan = service.create(ownerId, new EventPlanCreateRequest(UUID.randomUUID(),
                definition(today, today.plusDays(6), List.of(1, 2, 3, 4, 5, 6, 7))));
        var initial = generated(plan.id());
        clock.now.set(Instant.parse("2026-09-21T18:00:00Z")); // 21:00 Turkey; today's 20:00 event has started.
        var stopped = service.stop(ownerId, plan.id(), new EventPlanStopRequest(plan.version(), true));
        service.generate(plan.id());
        assertThat(stopped.status()).isEqualTo(EventPlanStatus.STOPPED);
        assertThat(generated(plan.id())).containsExactlyEntriesOf(Map.of(today, initial.get(today)));
        assertThat(jdbc.queryForObject("select count(*) from event_plan_occurrences where plan_id=? and status='CANCELLED'",
                Long.class, plan.id())).isEqualTo(6);
        assertThat(jdbc.queryForObject("select count(*) from tbl_event where id=?", Long.class, initial.get(today))).isEqualTo(1);
    }

    @Test
    void distantPlanPinsItsPosterBeforeAnyPublicEventIsGenerated() {
        var plan = service.create(ownerId, new EventPlanCreateRequest(UUID.randomUUID(),
                definition(today.plusDays(40), null, List.of(1))));
        assertThat(generated(plan.id())).isEmpty();
        UUID poster = UUID.randomUUID();
        // The reference index must tolerate the existing stored-string normalization contract.
        jdbc.update("update event_plans set poster_image=? where id=?", " " + poster.toString().toUpperCase() + " ", plan.id());
        assertThat(tx(() -> mediaReferences.countEventPlanPosterReferences(poster.toString()))).isEqualTo(1);
        assertThat(tx(() -> mediaReferences.countEventPosterReferences(poster.toString()))).isZero();
        service.stop(ownerId, plan.id(), new EventPlanStopRequest(plan.version(), false));
        assertThat(tx(() -> mediaReferences.countEventPlanPosterReferences(poster.toString()))).isEqualTo(1);
    }

    @Test
    void generatorRacingCancellationCannotLeaveAnyFutureEvent() throws Exception {
        var plan = service.create(ownerId, new EventPlanCreateRequest(UUID.randomUUID(),
                definition(today, null, List.of(1, 2, 3, 4, 5, 6, 7))));
        var initial = generated(plan.id());
        clock.now.set(Instant.parse("2026-09-28T08:00:00Z"));
        var release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var generation = executor.submit(() -> {
                if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Start latch timed out");
                service.generate(plan.id()); return null;
            });
            var cancellation = executor.submit(() -> {
                if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Start latch timed out");
                return service.stop(ownerId, plan.id(), new EventPlanStopRequest(plan.version(), true));
            });
            release.countDown();
            generation.get(25, TimeUnit.SECONDS);
            assertThat(cancellation.get(25, TimeUnit.SECONDS).status()).isEqualTo(EventPlanStatus.STOPPED);
        }
        service.generate(plan.id());
        assertThat(jdbc.queryForObject("select count(*) from event_plan_occurrences where plan_id=? and event_id is not null and event_date>=?",
                Long.class, plan.id(), today.plusDays(7))).isZero();
        assertThat(generated(plan.id())).hasSize(7);
        assertThat(generated(plan.id()).get(today)).isEqualTo(initial.get(today));
    }

    @Test
    void independentDateOverrideKeepsItsIdentityAndSurvivesTheNextBulkEdit() {
        var plan = service.create(ownerId, new EventPlanCreateRequest(UUID.randomUUID(),
                definition(today, today.plusDays(6), List.of(1, 2, 3, 4, 5, 6, 7))));
        UUID id = generated(plan.id()).get(today.plusDays(1));
        var specific = new EventPlanTemplate("One special night", "Independent date note", LocalTime.of(21, 0),
                LocalTime.of(23, 0), null, null, null, "Manual test performer");
        var overridden = service.override(ownerId, plan.id(), today.plusDays(1),
                new EventPlanOverrideRequest(plan.version(), today.plusDays(1), specific));
        var changedTemplate = new EventPlanTemplate("Changed entire program", "Bulk edit", LocalTime.of(19, 0),
                LocalTime.of(22, 0), null, null, null, "Manual test performer");
        service.update(ownerId, plan.id(), new EventPlanUpdateRequest(overridden.version(),
                new EventPlanDefinition(venueId, today, today.plusDays(6), List.of(1, 2, 3, 4, 5, 6, 7), List.of(), changedTemplate)));
        assertThat(generated(plan.id()).get(today.plusDays(1))).isEqualTo(id);
        assertThat(jdbc.queryForObject("select title from tbl_event where id=?", String.class, id)).isEqualTo("One special night");
        assertThat(jdbc.queryForObject("select status from event_plan_occurrences where plan_id=? and scheduled_date=?",
                String.class, plan.id(), today.plusDays(1))).isEqualTo("OVERRIDDEN");
        assertThat(jdbc.queryForObject("select title from tbl_event where id=?", String.class, generated(plan.id()).get(today.plusDays(2))))
                .isEqualTo("Changed entire program");
    }

    @Test
    void ownerAndVersionFencesRejectForeignAndStaleMutationsWithoutChangingEvents() {
        var plan = service.create(ownerId, new EventPlanCreateRequest(UUID.randomUUID(), definition(today, null, List.of(1, 4))));
        var initial = generated(plan.id());
        UUID stranger = tx(() -> user("ROLE_VENUE").getId());
        assertThatThrownBy(() -> service.getOwner(stranger, plan.id())).isInstanceOf(SoundConnectException.class);
        assertThatThrownBy(() -> service.stop(stranger, plan.id(), new EventPlanStopRequest(plan.version(), true)))
                .isInstanceOf(SoundConnectException.class);
        var updated = service.update(ownerId, plan.id(), new EventPlanUpdateRequest(plan.version(),
                definition(today, today.plusDays(40), List.of(1, 4))));
        assertThatThrownBy(() -> service.update(ownerId, plan.id(), new EventPlanUpdateRequest(plan.version(),
                definition(today, today.plusDays(40), List.of(2, 5))))).isInstanceOf(SoundConnectException.class);
        assertThat(service.getOwner(ownerId, plan.id()).version()).isEqualTo(updated.version());
        assertThat(generated(plan.id())).isEqualTo(initial);
    }

    @Test
    void withdrawnStoppedPlanRemovesFutureArtistLinksButKeepsEveryEventIdentity() {
        var artist = musician();
        var plan = service.create(ownerId, new EventPlanCreateRequest(UUID.randomUUID(), musicianDefinition(artist.profileId())));
        var initial = generated(plan.id());
        assertThatThrownBy(() -> service.decide(ownerId, plan.id(),
                new EventPlanDecisionRequest(plan.version(), EventPlanDecision.ACCEPT, true))).isInstanceOf(SoundConnectException.class);
        var accepted = service.decide(artist.userId(), plan.id(), new EventPlanDecisionRequest(plan.version(), EventPlanDecision.ACCEPT, true));
        var stopped = service.stop(ownerId, plan.id(), new EventPlanStopRequest(accepted.version(), false));
        var withdrawn = service.decide(artist.userId(), plan.id(), new EventPlanDecisionRequest(stopped.version(), EventPlanDecision.WITHDRAW, null));
        assertThat(withdrawn.consentStatus()).isEqualTo(EventPlanConsentStatus.WITHDRAWN);
        assertThat(generated(plan.id())).isEqualTo(initial);
        assertThat(jdbc.queryForObject("select count(*) from tbl_event e join event_plan_occurrences o on o.event_id=e.id where o.plan_id=? and (e.musician_profile_id is not null or e.profile_calendar_approved)",
                Long.class, plan.id())).isZero();
        assertThat(jdbc.queryForList("select e.manual_performer_name from tbl_event e join event_plan_occurrences o on o.event_id=e.id where o.plan_id=?",
                String.class, plan.id())).allMatch(name -> name != null && !name.isBlank());
    }

    @Test
    void falseProfileConsentIsPreservedByRollingGeneration() {
        var artist = musician();
        var plan = service.create(ownerId, new EventPlanCreateRequest(UUID.randomUUID(), musicianDefinition(artist.profileId())));
        var accepted = service.decide(artist.userId(), plan.id(), new EventPlanDecisionRequest(plan.version(), EventPlanDecision.ACCEPT, false));
        assertThat(accepted.showOnProfile()).isFalse();
        var before = generated(plan.id());
        clock.now.set(Instant.parse("2026-09-28T08:00:00Z"));
        service.generate(plan.id());
        assertThat(generated(plan.id())).containsAllEntriesOf(before);
        assertThat(jdbc.queryForObject("select count(*) from tbl_event e join event_plan_occurrences o on o.event_id=e.id where o.plan_id=? and e.profile_calendar_approved",
                Long.class, plan.id())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from tbl_event e join event_plan_occurrences o on o.event_id=e.id where o.plan_id=? and e.musician_profile_id=?",
                Long.class, plan.id(), artist.profileId())).isEqualTo(generated(plan.id()).size());
    }

    @Test
    void acceptedSeriesDoesNotRepublishAnOccurrenceThatTheArtistExplicitlyHid() {
        var artist = musician();
        var plan = service.create(ownerId, new EventPlanCreateRequest(UUID.randomUUID(), musicianDefinition(artist.profileId())));
        service.decide(artist.userId(), plan.id(), new EventPlanDecisionRequest(plan.version(), EventPlanDecision.ACCEPT, true));
        UUID hidden = generated(plan.id()).get(today.plusDays(7));
        jdbc.update("update tbl_event set profile_calendar_approved=false,profile_publication_version=profile_publication_version+1 where id=?", hidden);
        clock.now.set(Instant.parse("2026-09-28T08:00:00Z"));
        service.generate(plan.id());
        assertThat(jdbc.queryForObject("select profile_calendar_approved from tbl_event where id=?", Boolean.class, hidden)).isFalse();
        assertThat(jdbc.queryForObject("select musician_profile_id from tbl_event where id=?", UUID.class, hidden)).isEqualTo(artist.profileId());
        assertThat(generated(plan.id()).get(today.plusDays(7))).isEqualTo(hidden);
    }

    @Test
    void outboxFailureRollsBackConsentAndAllGeneratedPerformerLinks() {
        var artist = musician();
        var plan = service.create(ownerId, new EventPlanCreateRequest(UUID.randomUUID(), musicianDefinition(artist.profileId())));
        var initial = generated(plan.id());
        doThrow(new IllegalStateException("Isolated outbox failure")).when(notificationMock()).enqueueAll(anyList());
        assertThatThrownBy(() -> service.decide(artist.userId(), plan.id(),
                new EventPlanDecisionRequest(plan.version(), EventPlanDecision.ACCEPT, true)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Isolated outbox failure");
        var retained = service.getOwner(ownerId, plan.id());
        assertThat(retained.version()).isEqualTo(plan.version());
        assertThat(retained.consentStatus()).isEqualTo(EventPlanConsentStatus.PENDING);
        assertThat(generated(plan.id())).isEqualTo(initial);
        assertThat(jdbc.queryForObject("select count(*) from tbl_event e join event_plan_occurrences o on o.event_id=e.id where o.plan_id=? and (e.musician_profile_id is not null or e.profile_calendar_approved)",
                Long.class, plan.id())).isZero();
    }

    @Test
    void bandScopeChangeResetsMemberPublicationAndRechecksTheCurrentFounder() {
        var founder = musician();
        var member = musician();
        UUID bandId = tx(() -> {
            Band band = persist(Band.builder().name("Plan band " + UUID.randomUUID()).build());
            persist(BandMember.builder().band(band).user(em.getReference(User.class, founder.userId()))
                    .bandRole(BandRole.FOUNDER).status(BandMemberShipStatus.ACTIVE).build());
            persist(BandMember.builder().band(band).user(em.getReference(User.class, member.userId()))
                    .bandRole(BandRole.MEMBER).status(BandMemberShipStatus.ACTIVE).build());
            return band.getId();
        });
        var original = new EventPlanDefinition(venueId, today, null, List.of(1, 4), List.of(),
                new EventPlanTemplate("Band program", null, LocalTime.of(20, 0), LocalTime.of(22, 0),
                        null, null, bandId, null));
        var plan = service.create(ownerId, new EventPlanCreateRequest(UUID.randomUUID(), original));
        var accepted = service.decide(founder.userId(), plan.id(),
                new EventPlanDecisionRequest(plan.version(), EventPlanDecision.ACCEPT, true));
        var identities = generated(plan.id());
        UUID eventId = identities.get(today);
        assertThat(jdbc.queryForObject("select band_id from tbl_event where id=?", UUID.class, eventId)).isEqualTo(bandId);
        assertThat(jdbc.queryForObject("select profile_calendar_approved from tbl_event where id=?", Boolean.class, eventId)).isTrue();
        long previousPublicationVersion = jdbc.queryForObject(
                "select profile_publication_version from tbl_event where id=?", Long.class, eventId);
        jdbc.update("insert into event_member_publications(event_id,musician_profile_id,visible,version) values (?,?,true,4) "
                + "on conflict(event_id,musician_profile_id) do update set visible=true,version=4", eventId, member.profileId());

        var changed = new EventPlanDefinition(venueId, today, null, List.of(1, 4), List.of(),
                new EventPlanTemplate("Band program", null, LocalTime.of(21, 0), LocalTime.of(23, 0),
                        null, null, bandId, null));
        var pending = service.update(ownerId, plan.id(), new EventPlanUpdateRequest(accepted.version(), changed));
        assertThat(pending.consentStatus()).isEqualTo(EventPlanConsentStatus.PENDING);
        assertThat(generated(plan.id())).isEqualTo(identities);
        assertThat(jdbc.queryForObject("select visible from event_member_publications where event_id=? and musician_profile_id=?",
                Boolean.class, eventId, member.profileId())).isFalse();
        assertThat(jdbc.queryForObject("select version from event_member_publications where event_id=? and musician_profile_id=?",
                Long.class, eventId, member.profileId())).isEqualTo(5L);
        assertThat(jdbc.queryForObject("select profile_publication_version from tbl_event where id=?", Long.class, eventId))
                .isEqualTo(previousPublicationVersion + 1);
        assertThat(jdbc.queryForObject("select band_id from tbl_event where id=?", UUID.class, eventId)).isNull();

        // Persist a founder handover before the next request; authorization must use the current membership.
        jdbc.update("update tbl_band_member set band_role=case when user_id=? then 'FOUNDER' else 'MEMBER' end where band_id=?",
                member.userId(), bandId);
        assertThatThrownBy(() -> service.decide(founder.userId(), plan.id(),
                new EventPlanDecisionRequest(pending.version(), EventPlanDecision.ACCEPT, true)))
                .isInstanceOfSatisfying(SoundConnectException.class,
                        error -> assertThat(error.getErrorType()).isEqualTo(ErrorType.EVENT_NOT_FOUND));
        assertThat(service.getOwner(ownerId, plan.id()).version()).isEqualTo(pending.version());
        var renewed = service.decide(member.userId(), plan.id(),
                new EventPlanDecisionRequest(pending.version(), EventPlanDecision.ACCEPT, true));
        assertThat(renewed.consentStatus()).isEqualTo(EventPlanConsentStatus.ACCEPTED);
        assertThat(generated(plan.id())).isEqualTo(identities);
        assertThat(jdbc.queryForObject("select band_id from tbl_event where id=?", UUID.class, eventId)).isEqualTo(bandId);
        assertThat(jdbc.queryForObject("select visible from event_member_publications where event_id=? and musician_profile_id=?",
                Boolean.class, eventId, member.profileId())).isFalse();
        assertThat(jdbc.queryForObject("select version from event_member_publications where event_id=? and musician_profile_id=?",
                Long.class, eventId, member.profileId())).isEqualTo(5L);
    }

    private EventPlanDefinition definition(LocalDate start, LocalDate until, List<Integer> days) {
        return new EventPlanDefinition(venueId, start, until, days, List.of(),
                new EventPlanTemplate("Recurring isolated concert", "Retained definition", LocalTime.of(20, 0),
                        LocalTime.of(22, 0), null, null, null, "Manual test performer"));
    }

    private EventPlanDefinition musicianDefinition(UUID profile) {
        return new EventPlanDefinition(venueId, today, null, List.of(1, 4), List.of(),
                new EventPlanTemplate("Artist series", null, LocalTime.of(20, 0), LocalTime.of(22, 0), null, profile, null, null));
    }

    private Artist musician() {
        return tx(() -> {
            User account = user("ROLE_MUSICIAN");
            var profile = persist(MusicianProfile.builder().user(account).stageName("Isolated artist").build());
            return new Artist(account.getId(), profile.getId());
        });
    }

    private User user(String roleName) {
        var role = em.createQuery("select r from Role r where r.name=:role", Role.class).setParameter("role", roleName)
                .getResultStream().findFirst().orElseGet(() -> persist(Role.builder().name(roleName).build()));
        return persist(User.builder().username("plan" + UUID.randomUUID().toString().replace("-", "").substring(0, 15))
                .email(UUID.randomUUID() + "@test.invalid").password("unused-test-password")
                .roles(Set.of(role)).status(UserStatus.ACTIVE).emailVerified(true).build());
    }

    private record Artist(UUID userId, UUID profileId) { }

    private Map<LocalDate, UUID> generated(UUID planId) {
        var result = new LinkedHashMap<LocalDate, UUID>();
        jdbc.query("select scheduled_date,event_id from event_plan_occurrences where plan_id=? and event_id is not null order by scheduled_date",
                row -> { result.put(row.getObject("scheduled_date", LocalDate.class), row.getObject("event_id", UUID.class)); }, planId);
        return result;
    }

    private <T> T persist(T value) { em.persist(value); return value; }
    private <T> T tx(Supplier<T> work) { return new TransactionTemplate(transactions).execute(status -> work.get()); }
    private EventPerformerNotificationOutboxPublisher notificationMock() { return AopTestUtils.getUltimateTargetObject(notifications); }

    static class TestClock extends EventScheduleClock {
        final AtomicReference<Instant> now = new AtomicReference<>();
        @Override public Instant instant() { return now.get(); }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableJpaAuditing
    @EnableJpaRepositories(basePackageClasses = {EventPlanRepository.class, EventRepository.class,
            VenueRepository.class, MusicianProfileRepository.class, BandRepository.class,
            MediaAssetRepository.class, VenueProfileRepository.class})
    @EntityScan(basePackages = "com.berkayb.soundconnect")
    @Import({EventPlanService.class, EventMapper.class, BandRepresentationPolicy.class})
    static class Config {
        @Bean DataSource dataSource() {
            if (!POSTGRES.isRunning()) throw new IllegalStateException("Disposable PostgreSQL is not running");
            return new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        }
        @Bean TestClock clock() { return new TestClock(); }
        @Bean MediaAssetService media() { return mock(MediaAssetService.class); }
        @Bean EventPerformerRequestService performerRequests() { return mock(EventPerformerRequestService.class); }
        @Bean EventPerformerNotificationOutboxPublisher notifications() { return mock(EventPerformerNotificationOutboxPublisher.class); }
        @Bean EventShareUrlBuilder shareUrls() { return new EventShareUrlBuilder("https://event-plan.test"); }
        @Bean ObjectMapper objectMapper() { return new ObjectMapper().findAndRegisterModules(); }
    }
}

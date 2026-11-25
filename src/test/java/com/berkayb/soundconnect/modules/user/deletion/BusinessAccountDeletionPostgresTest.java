package com.berkayb.soundconnect.modules.user.deletion;

import com.berkayb.soundconnect.modules.event.entity.Event;
import com.berkayb.soundconnect.modules.event.enums.*;
import com.berkayb.soundconnect.modules.event.management.*;
import com.berkayb.soundconnect.modules.event.mapper.EventMapper;
import com.berkayb.soundconnect.modules.event.performer.service.EventPerformerRequestService;
import com.berkayb.soundconnect.modules.event.plan.EventPlanLifecycle;
import com.berkayb.soundconnect.modules.event.service.EventServiceImpl;
import com.berkayb.soundconnect.modules.event.support.EventScheduleClock;
import com.berkayb.soundconnect.modules.location.entity.*;
import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.entity.MusicianProfile;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.service.MusicianProfileService;
import com.berkayb.soundconnect.modules.profile.StudioProfile.entity.StudioProfile;
import com.berkayb.soundconnect.modules.profile.StudioProfile.repository.StudioProfileRepository;
import com.berkayb.soundconnect.modules.studio.reservation.entity.*;
import com.berkayb.soundconnect.modules.studio.reservation.enums.*;
import com.berkayb.soundconnect.modules.studio.reservation.event.StudioReservationNotificationEvent;
import com.berkayb.soundconnect.modules.studio.reservation.support.StudioReservationTimeProvider;
import com.berkayb.soundconnect.modules.studio.room.entity.StudioRoom;
import com.berkayb.soundconnect.modules.studio.room.mapper.StudioRoomMapper;
import com.berkayb.soundconnect.modules.studio.room.service.StudioRoomDailyMetricsService;
import com.berkayb.soundconnect.modules.studio.room.service.StudioRoomService;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.modules.user.support.UserEntityFinder;
import com.berkayb.soundconnect.modules.venue.entity.Venue;
import com.berkayb.soundconnect.modules.venue.enums.VenueStatus;
import com.berkayb.soundconnect.modules.venue.support.VenueEntityFinder;
import com.berkayb.soundconnect.shared.config.JpaAuditingConfig;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import com.berkayb.soundconnect.modules.application.studioapplication.repository.StudioApplicationRepository;
import com.berkayb.soundconnect.modules.application.venueapplication.repository.VenueApplicationRepository;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import javax.sql.DataSource;
import java.nio.file.*;
import java.time.*;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@DataJpaTest(properties={"spring.config.location=classpath:/application-test.yml", "spring.config.import=",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.show-sql=false",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect"})
@ActiveProfiles("test") @Testcontainers @RecordApplicationEvents
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes=BusinessAccountDeletionPostgresTest.Config.class)
class BusinessAccountDeletionPostgresTest {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16.4-alpine");
    @DynamicPropertySource static void configure(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url",PG::getJdbcUrl);
        properties.add("spring.datasource.username",PG::getUsername);
        properties.add("spring.datasource.password",PG::getPassword);
        properties.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");
    }
    @Autowired BusinessAccountDeletionService cleanup;
    @Autowired StudioProfileRepository studios;
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired ApplicationEvents published;
    @Autowired PlatformTransactionManager transactions;
    @Autowired StudioApplicationRepository studioApplications;
    @Autowired VenueApplicationRepository venueApplications;
    @MockitoBean EventMapper mapper;
    @MockitoBean MediaAssetService media;
    @MockitoBean MusicianProfileService musicians;
    @MockitoBean StudioRoomMapper roomMapper;
    @MockitoBean StudioRoomDailyMetricsService roomMetrics;
    @MockitoBean EventPerformerRequestService performerRequests;

    @BeforeEach void migrations() throws Exception {
        try (var connection=dataSource.getConnection(); var statement=connection.createStatement()) {
            for (String name : java.util.List.of("2026-09-10-listener-account-erasure", "2026-10-06-application-mail-intents",
                    "2026-11-25-business-account-erasure", "2026-11-25-application-erasure-mail-cleanup")) {
                statement.execute(Files.readString(Path.of("scripts/db",name+".sql")));
            }
        }
    }

    @Test void closingStudioPreservesCustomerHistoryAndEmitsBothCancellations() {
        User owner=user(), customer=user();
        StudioProfile studio=save(StudioProfile.builder().user(owner).name("Ses").phone("05555555555").build());
        StudioRoom room=save(StudioRoom.builder().studioProfile(studio).slotIndex(1).clientRequestId(UUID.randomUUID())
                .creationPayloadHash("1".repeat(64)).currency("TRY").name("Birinci oda").capacity(4).build());
        var pending=booking(room,customer,StudioReservationStatus.PENDING_APPROVAL,2);
        var confirmed=booking(room,customer,StudioReservationStatus.CONFIRMED,3);
        var past=booking(room,customer,StudioReservationStatus.CONFIRMED,-30);
        save(StudioRoomOccupancy.builder().room(room).reservation(confirmed).type(StudioOccupancyType.RESERVATION)
                .startsAt(confirmed.getStartsAt()).endsAt(confirmed.getEndsAt()).active(true).createdBy(owner.getId()).build());
        em.flush(); em.clear();

        cleanup.purgeAccount(owner.getId());
        em.flush(); em.clear();
        assertThat(status(pending.getId())).isEqualTo("CANCELLED_BY_STUDIO");
        assertThat(status(confirmed.getId())).isEqualTo("CANCELLED_BY_STUDIO");
        assertThat(status(past.getId())).isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("select active from tbl_studio_room_occupancy where reservation_id=?",Boolean.class,confirmed.getId())).isFalse();
        assertThat(jdbc.queryForObject("select archived_at is not null from tbl_studio_room where id=?",Boolean.class,room.getId())).isTrue();
        assertThat(jdbc.queryForObject("select phone from tbl_studio_profile where id=?",String.class,studio.getId())).isNull();
        assertThat(published.stream(StudioReservationNotificationEvent.class)
                .filter(e->e.recipientId().equals(customer.getId())).map(e->e.payload().get("reservationId")))
                .containsExactlyInAnyOrder(pending.getId().toString(),confirmed.getId().toString());
        jdbc.update("update tbl_user set erased_at=now() where id=?",owner.getId());
        assertThat(studios.findById(studio.getId())).isEmpty();
        assertThat(jdbc.queryForObject("""
                select count(*) from tbl_studio_room_reservation r join tbl_studio_room rm on rm.id=r.room_id
                join tbl_studio_profile p on p.id=rm.studio_profile_id where r.requester_id=? and p.id=?
                """, Long.class,customer.getId(),studio.getId())).isEqualTo(3L);
        assertThatThrownBy(()->jdbc.update("""
                update tbl_studio_profile set phone='new number' where id=?
                """,studio.getId())).hasRootCauseInstanceOf(java.sql.SQLException.class);
    }

    @Test void closingVenueStopsSeriesPreservesPastAndDetachesTheArtistEvent() {
        User owner=user(), artist=user();
        City city=save(City.builder().name("Şehir "+UUID.randomUUID()).build());
        District district=save(District.builder().city(city).name("İlçe").build());
        Neighborhood neighborhood=save(Neighborhood.builder().district(district).name("Mahalle").build());
        Venue venue=save(Venue.builder().name("Sahne").address("Adres").city(city).district(district)
                .neighborhood(neighborhood).owner(owner).status(VenueStatus.APPROVED).build());
        MusicianProfile profile=save(MusicianProfile.builder().user(artist).stageName("Sanatçı").build());
        LocalDate today=LocalDate.now(EventScheduleClock.ZONE);
        var past=save(Event.builder().venue(venue).title("Geçmiş").eventDate(today.minusDays(3)).startTime(LocalTime.NOON).build());
        var future=save(Event.builder().venue(venue).title("Gelecek").eventDate(today.plusDays(3)).startTime(LocalTime.NOON).build());
        var other=save(Event.builder().eventOrigin(EventOrigin.MUSICIAN).organizerUserId(artist.getId()).venue(venue)
                .musicianProfile(profile).title("Kendi konserim").eventDate(today.plusDays(4)).startTime(LocalTime.NOON)
                .performerApprovalStatus(EventPerformerApprovalStatus.APPROVED).profileCalendarApproved(true).build());
        em.flush(); em.clear();
        UUID plan=UUID.randomUUID();
        jdbc.update("""
                insert into event_plans(id,organizer_user_id,venue_id,client_request_id,creation_hash,version,consent_revision,
                  start_date,weekday_mask,excluded_dates,title,start_time,status,consent_status,show_on_profile,cancel_future)
                values(?,?,?,?,?,0,0,current_date,1,'[]','Tekrarlı','12:00','ACTIVE','NOT_REQUIRED',false,false)
                """,plan,owner.getId(),venue.getId(),UUID.randomUUID(),"a".repeat(64));
        jdbc.update("insert into musician_profile_venues(musician_profile_id,venue_id) values(?,?)",profile.getId(),venue.getId());

        cleanup.purgeAccount(owner.getId()); em.flush(); em.clear();
        assertThat(em.find(Event.class,past.getId())).isNotNull();
        assertThat(em.find(Event.class,future.getId())).isNull();
        Event retained=em.find(Event.class,other.getId());
        assertThat(retained.getVenue()).isNull();
        assertThat(retained.getOrganizerUserId()).isEqualTo(artist.getId());
        assertThat(retained.isProfileCalendarApproved()).isTrue();
        assertThat(jdbc.queryForObject("select status from event_plans where id=?",String.class,plan)).isEqualTo("STOPPED");
        assertThat(jdbc.queryForObject("select count(*) from musician_profile_venues where venue_id=?",Long.class,venue.getId())).isZero();
    }

    @ParameterizedTest @ValueSource(booleans={true,false})
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void aDecisionAlreadyWaitingOnErasureCannotRecreateEitherApplication(boolean studio) throws Exception {
        var tx=new TransactionTemplate(transactions);
        String table=studio ? "tbl_studio_applications" : "tbl_venue_applications";
        UUID application=UUID.randomUUID();
        UUID account=tx.execute(status -> newApplication(studio,application));
        try (var blocker=dataSource.getConnection(); var pool=Executors.newSingleThreadExecutor()) {
            blocker.setAutoCommit(false);
            try (var lock=blocker.prepareStatement("select pg_backend_pid() from tbl_user where id=? for update")) {
                lock.setObject(1,account);
                int pid;
                try(var row=lock.executeQuery()) {assertThat(row.next()).isTrue(); pid=row.getInt(1);}
                var decision=pool.submit(()->tx.execute(status->studio ? studioApplications.findByIdForUpdate(application).isPresent()
                        : venueApplications.findByIdForUpdate(application).isPresent()));
                try {
                    org.awaitility.Awaitility.await().atMost(10,TimeUnit.SECONDS).until(()->Boolean.TRUE.equals(
                            jdbc.queryForObject("select exists(select from pg_stat_activity where ?=any(pg_blocking_pids(pid)))", Boolean.class,pid)));
                    try (var remove=blocker.prepareStatement("delete from "+table+" where id=?");
                         var erase=blocker.prepareStatement("update tbl_user set erased_at=now() where id=?")) {
                        remove.setObject(1,application); assertThat(remove.executeUpdate()).isOne();
                        erase.setObject(1,account); assertThat(erase.executeUpdate()).isOne();
                    }
                    blocker.commit();
                    assertThat(decision.get(10,TimeUnit.SECONDS)).isFalse();
                } finally {blocker.rollback();}
            }
        }
    }

    private UUID newApplication(boolean studio, UUID application) {
        String table=studio ? "tbl_studio_applications" : "tbl_venue_applications";
        User applicant=user();
        City city=save(City.builder().name("QA "+application).build());
        District district=save(District.builder().name("District").city(city).build());
        Neighborhood neighborhood=save(Neighborhood.builder().name("Neighborhood").district(district).build());
        em.flush();
        String fields=studio ? "applicant_id,studio_name,studio_address" : "user_id,venue_name,venue_address";
        jdbc.update("insert into "+table+"(id,"+fields+",phone,city_id,district_id,neighborhood_id,status,application_date)"
            +" values(?,?,'QA','QA','05550000000',?,?,?,'PENDING',now())",application,applicant.getId(),city.getId(),district.getId(),neighborhood.getId());
        return applicant.getId();
    }

    @ParameterizedTest @ValueSource(booleans={true,false})
    void deletingAnApplicationRollsBackAndOnlyRemovesItsOwnContactMessages(boolean studio) throws Exception {
        UUID application=UUID.randomUUID();
        newApplication(studio, application);
        String table=studio ? "tbl_studio_applications" : "tbl_venue_applications";
        String other=studio ? "VENUE_APPLICATION_ADMIN" : "STUDIO_APPLICATION_ADMIN";
        for (String purpose : java.util.List.of("VENUE_APPLICATION_ADMIN","STUDIO_APPLICATION_ADMIN","STUDIO_APPLICATION_DECISION")) {
            String decision=purpose.endsWith("DECISION") ? "REJECTED" : "CREATED";
            jdbc.update("""
                    insert into tbl_application_mail_intent(id,application_id,purpose,decision,recipient,payload,max_attempts)
                    values(?,?,?,?,?,'{"phone":"05550000000"}',1)
                    """, UUID.randomUUID(),application,purpose,decision,"qa@example.invalid");
        }
        var connection=org.springframework.jdbc.datasource.DataSourceUtils.getConnection(dataSource);
        var beforeDelete=connection.setSavepoint();
        jdbc.update("delete from "+table+" where id=?",application);
        String list="select purpose from tbl_application_mail_intent where application_id=? order by purpose";
        var retained=jdbc.queryForList(list,String.class,application);
        if (studio) assertThat(retained).containsExactly(other);
        else assertThat(retained).containsExactly(other,"STUDIO_APPLICATION_DECISION");
        connection.rollback(beforeDelete);
        assertThat(jdbc.queryForList(list,String.class,application)).hasSize(3);
        assertThat(jdbc.queryForObject("select count(*) from "+table+" where id=?",Long.class,application)).isOne();
    }

    private String status(UUID id) {
        return jdbc.queryForObject("select status from tbl_studio_room_reservation where id=?",String.class,id);
    }
    private StudioRoomReservation booking(StudioRoom room, User customer, StudioReservationStatus status, int days) {
        Instant start=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.HOURS).plus(Duration.ofDays(days));
        return save(StudioRoomReservation.builder().room(room).requester(customer).status(status).startsAt(start)
                .endsAt(start.plusSeconds(3600)).currencySnapshot("TRY").approvalRequiredSnapshot(true)
                .contactPhoneSnapshot("05550000000").clientRequestId(UUID.randomUUID()).build());
    }
    private User user() {
        String name="qa"+UUID.randomUUID().toString().replace("-","").substring(0,16);
        return save(User.builder().username(name).email(name+"@test.invalid").password("unused")
                .emailVerified(true).status(UserStatus.ACTIVE).build());
    }
    private <T>T save(T item) {em.persist(item); return item;}

    @Configuration(proxyBeanMethods=false)
    @EntityScan("com.berkayb.soundconnect") @EnableJpaRepositories("com.berkayb.soundconnect")
    @Import({JpaAuditingConfig.class,BusinessAccountDeletionService.class,EventManagementRepository.class,
            EventManagementTimeStorage.class,EventScheduleClock.class,EventServiceImpl.class,EventPlanLifecycle.class,
            VenueEntityFinder.class,UserEntityFinder.class,StudioRoomService.class,StudioReservationTimeProvider.class})
    static class Config {}
}

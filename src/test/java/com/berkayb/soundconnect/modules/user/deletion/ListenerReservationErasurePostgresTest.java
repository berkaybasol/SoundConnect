package com.berkayb.soundconnect.modules.user.deletion;

import com.berkayb.soundconnect.modules.profile.StudioProfile.entity.StudioProfile;
import com.berkayb.soundconnect.modules.studio.reservation.entity.*;
import com.berkayb.soundconnect.modules.studio.reservation.enums.*;
import com.berkayb.soundconnect.modules.studio.room.entity.StudioRoom;
import com.berkayb.soundconnect.modules.tablegroup.game.service.TableGroupGameLifecycleService;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.shared.config.JpaAuditingConfig;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import javax.sql.DataSource;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;

/** Real booking/occupancy rows validate the SQL inventory independently of the full account graph. */
@DataJpaTest(properties={"spring.config.location=classpath:/application-test.yml","spring.config.import=",
        "spring.jpa.hibernate.ddl-auto=create-drop","spring.jpa.show-sql=false",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect"})
@ActiveProfiles("test") @Testcontainers
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes=ListenerReservationErasurePostgresTest.Config.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional(propagation=Propagation.NOT_SUPPORTED)
class ListenerReservationErasurePostgresTest {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:16.4-alpine")
            .withDatabaseName("reservation_erasure_test").withUsername("reservation_test")
            .withPassword("reservation_test").withReuse(false);
    @Autowired DataSource dataSource;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager manager;
    @Autowired ListenerAccountDataCleaner cleaner;
    @MockitoBean TableGroupGameLifecycleService games;

    @Test void pendingAndConfirmedBookingsAreCancelledAndOnlyTheirAvailabilityAndPhoneSnapshotsAreCleared() {
        Fixture fixture=tx(this::fixture);
        var jdbc=new JdbcTemplate(dataSource);
        tx(() -> { cleaner.prepareLifecycle(fixture.requester); return null; });
        for(UUID id:List.of(fixture.pending,fixture.confirmed)) {
            assertThat(jdbc.queryForObject("select status from tbl_studio_room_reservation where id=?",String.class,id)).isEqualTo("CANCELLED_BY_CUSTOMER");
            assertThat(jdbc.queryForObject("select cancelled_by from tbl_studio_room_reservation where id=?",UUID.class,id)).isEqualTo(fixture.requester);
            assertThat(jdbc.queryForObject("select cancelled_at from tbl_studio_room_reservation where id=?",java.sql.Timestamp.class,id)).isNotNull();
            assertThat(jdbc.queryForObject("select version from tbl_studio_room_reservation where id=?",Long.class,id)).isEqualTo(1);
            assertThat(jdbc.queryForObject("select total_price_minor_snapshot from tbl_studio_room_reservation where id=?",Long.class,id)).isEqualTo(25000);
        }
        assertThat(jdbc.queryForObject("select status from tbl_studio_room_reservation where id=?",String.class,fixture.expired)).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("select version from tbl_studio_room_reservation where id=?",Long.class,fixture.expired)).isZero();
        for(UUID id:List.of(fixture.pending,fixture.confirmed,fixture.expired))
            assertThat(jdbc.queryForObject("select contact_phone_snapshot from tbl_studio_room_reservation where id=?",String.class,id)).isNull();
        assertThat(jdbc.queryForObject("select active from tbl_studio_room_occupancy where id=?",Boolean.class,fixture.occupancy)).isFalse();
        assertThat(jdbc.queryForObject("select released_by from tbl_studio_room_occupancy where id=?",UUID.class,fixture.occupancy)).isEqualTo(fixture.requester);
        assertThat(jdbc.queryForObject("select released_at from tbl_studio_room_occupancy where id=?",java.sql.Timestamp.class,fixture.occupancy)).isNotNull();
        assertThat(jdbc.queryForObject("select release_reason from tbl_studio_room_occupancy where id=?",String.class,fixture.occupancy)).isEqualTo("Account deleted");
        assertThat(jdbc.queryForObject("select version from tbl_studio_room_occupancy where id=?",Long.class,fixture.occupancy)).isEqualTo(1);
        assertThat(row(jdbc,"tbl_studio_room_reservation",fixture.otherReservation)).isEqualTo(fixture.otherBefore);
        assertThat(row(jdbc,"tbl_studio_room_occupancy",fixture.otherOccupancy)).isEqualTo(fixture.otherOccupancyBefore);
        assertThat(row(jdbc,"tbl_studio_room_occupancy",fixture.manualBlock)).isEqualTo(fixture.manualBefore);
    }

    @Test void aLaterErasureFailureRollsBackCancellationAvailabilityAndContactErasureTogether() {
        Fixture fixture=tx(this::fixture); var jdbc=new JdbcTemplate(dataSource);
        String pendingBefore=row(jdbc,"tbl_studio_room_reservation",fixture.pending);
        String confirmedBefore=row(jdbc,"tbl_studio_room_reservation",fixture.confirmed);
        String occupancyBefore=row(jdbc,"tbl_studio_room_occupancy",fixture.occupancy);
        assertThatThrownBy(() -> tx(() -> {
            cleaner.prepareLifecycle(fixture.requester);
            assertThat(jdbc.queryForObject("select active from tbl_studio_room_occupancy where id=?",Boolean.class,fixture.occupancy)).isFalse();
            throw new IllegalStateException("Failure after booking preparation");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(row(jdbc,"tbl_studio_room_reservation",fixture.pending)).isEqualTo(pendingBefore);
        assertThat(row(jdbc,"tbl_studio_room_reservation",fixture.confirmed)).isEqualTo(confirmedBefore);
        assertThat(row(jdbc,"tbl_studio_room_occupancy",fixture.occupancy)).isEqualTo(occupancyBefore);
    }

    private Fixture fixture() {
        User requester=user(),other=user(),studioOwner=user();
        StudioProfile profile=persist(StudioProfile.builder().user(studioOwner).name("Studio").build());
        StudioRoom room=persist(StudioRoom.builder().studioProfile(profile).slotIndex(0).clientRequestId(UUID.randomUUID())
                .creationPayloadHash("a".repeat(64)).name("Room").capacity(4).currency("TRY").hourlyPriceMinor(12500L).build());
        Instant start=Instant.parse("2030-01-01T10:00:00Z");
        var pending=reservation(room,requester,StudioReservationStatus.PENDING_APPROVAL,start);
        var confirmed=reservation(room,requester,StudioReservationStatus.CONFIRMED,start.plusSeconds(7200));
        var expired=reservation(room,requester,StudioReservationStatus.EXPIRED,start.plusSeconds(14400));
        var otherReservation=reservation(room,other,StudioReservationStatus.CONFIRMED,start.plusSeconds(21600));
        var occupancy=occupancy(room,confirmed,studioOwner.getId());
        var otherOccupancy=occupancy(room,otherReservation,studioOwner.getId());
        var manual=persist(StudioRoomOccupancy.builder().room(room).type(StudioOccupancyType.MANUAL_BLOCK)
                .startsAt(start.plusSeconds(28800)).endsAt(start.plusSeconds(32400)).active(true)
                .createdBy(studioOwner.getId()).clientRequestId(UUID.randomUUID()).build());
        em.flush(); var jdbc=new JdbcTemplate(dataSource);
        return new Fixture(requester.getId(),pending.getId(),confirmed.getId(),expired.getId(),occupancy.getId(),otherReservation.getId(),
                otherOccupancy.getId(),manual.getId(),row(jdbc,"tbl_studio_room_reservation",otherReservation.getId()),
                row(jdbc,"tbl_studio_room_occupancy",otherOccupancy.getId()),row(jdbc,"tbl_studio_room_occupancy",manual.getId()));
    }
    private StudioRoomReservation reservation(StudioRoom room,User requester,StudioReservationStatus status,Instant start) {
        return persist(StudioRoomReservation.builder().room(room).requester(requester).clientRequestId(UUID.randomUUID())
                .startsAt(start).endsAt(start.plusSeconds(7200)).status(status).approvalRequiredSnapshot(status==StudioReservationStatus.PENDING_APPROVAL)
                .hourlyPriceMinorSnapshot(12500L).totalPriceMinorSnapshot(25000L).currencySnapshot("TRY").contactPhoneSnapshot("+905555555555").build());
    }
    private StudioRoomOccupancy occupancy(StudioRoom room,StudioRoomReservation reservation,UUID owner) {
        return persist(StudioRoomOccupancy.builder().room(room).reservation(reservation).type(StudioOccupancyType.RESERVATION)
                .startsAt(reservation.getStartsAt()).endsAt(reservation.getEndsAt()).active(true).createdBy(owner).build());
    }
    private User user() {
        return persist(User.builder().username("u"+UUID.randomUUID().toString().replace("-","").substring(0,16))
                .email(UUID.randomUUID()+"@test.invalid").password("unused").status(UserStatus.ACTIVE).emailVerified(true).build());
    }
    private String row(JdbcTemplate jdbc,String table,UUID id) { return jdbc.queryForObject("select to_jsonb(r)::text from "+table+" r where id=?",String.class,id); }
    private <T>T persist(T entity) { em.persist(entity); return entity; }
    private <T>T tx(Supplier<T> action) { return new TransactionTemplate(manager).execute(status -> action.get()); }
    private record Fixture(UUID requester,UUID pending,UUID confirmed,UUID expired,UUID occupancy,UUID otherReservation,
                           UUID otherOccupancy,UUID manualBlock,String otherBefore,String otherOccupancyBefore,String manualBefore) { }

    @Configuration(proxyBeanMethods=false)
    @EnableJpaRepositories(basePackages="com.berkayb.soundconnect") @EntityScan(basePackages="com.berkayb.soundconnect")
    @Import({JpaAuditingConfig.class,ListenerAccountDataCleaner.class})
    static class Config {
        @Bean DataSource dataSource() {
            if(!POSTGRES.isRunning()) throw new IllegalStateException("Disposable PostgreSQL must be running");
            return new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());
        }
    }
}

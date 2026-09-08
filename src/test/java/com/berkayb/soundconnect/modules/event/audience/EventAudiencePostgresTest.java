package com.berkayb.soundconnect.modules.event.audience;

import com.berkayb.soundconnect.modules.event.discovery.*;
import com.berkayb.soundconnect.modules.event.entity.Event;
import com.berkayb.soundconnect.modules.event.enums.*;
import com.berkayb.soundconnect.modules.event.support.*;
import com.berkayb.soundconnect.modules.location.entity.*;
import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.entity.ListenerProfile;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.enums.ListenerVisibilityMode;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.repository.ListenerProfileRepository;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.entity.MusicianProfile;
import com.berkayb.soundconnect.modules.role.entity.Role;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.berkayb.soundconnect.modules.venue.entity.Venue;
import com.berkayb.soundconnect.modules.venue.enums.VenueStatus;
import com.berkayb.soundconnect.shared.exception.*;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.data.jpa.repository.config.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import javax.sql.DataSource;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** No application datasource: every connection is verified against this disposable PostgreSQL container. */
@DataJpaTest(properties={"spring.config.location=classpath:/application-test.yml","spring.config.import=",
        "spring.jpa.hibernate.ddl-auto=create-drop","spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
        "spring.jpa.properties.hibernate.generate_statistics=true","spring.jpa.show-sql=false"})
@ActiveProfiles("test") @Testcontainers @AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes=EventAudiencePostgresTest.Config.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional(propagation=Propagation.NOT_SUPPORTED)
class EventAudiencePostgresTest {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:16.4-alpine")
            .withDatabaseName("audience_test").withUsername("audience_test").withPassword("audience_test").withReuse(false);
    @Autowired DataSource dataSource; @Autowired EntityManager em; @Autowired PlatformTransactionManager manager;
    @Autowired EventAudienceService service; @Autowired EventAudienceRepository repository;
    @Autowired ListenerProfileRepository listeners; @Autowired TestClock clock; @Autowired MediaAssetService media;
    JdbcTemplate jdbc; UUID actor, profile, viewer; Venue venue; LocalDate date=LocalDate.of(2026,9,8);
    static boolean schemaInstalled;
    @BeforeEach void setup() throws Exception {
        assertThat(POSTGRES.isRunning()).isTrue();
        try(var connection=dataSource.getConnection()) {
            assertThat(connection.getMetaData().getURL()).isEqualTo(POSTGRES.getJdbcUrl());
            assertThat(connection.getCatalog()).isEqualTo(POSTGRES.getDatabaseName());
        }
        jdbc=new JdbcTemplate(dataSource);
        if(!schemaInstalled) {
            // Replace only the generated new table so the actual additive SQL constraints/indexes are tested.
            jdbc.execute("drop table tbl_event_audience_intent");
            new ResourceDatabasePopulator(new FileSystemResource("scripts/db/2026-09-08-event-audience-intents.sql")).execute(dataSource);
            schemaInstalled=true;
        }
        clock.now.set(Instant.parse("2026-09-08T12:00:00Z")); reset(media);
        when(media.getDisplayUrlMap(anyList())).thenReturn(Map.of());
        tx(() -> {
            var city=persist(City.builder().name("City "+UUID.randomUUID()).build());
            var district=persist(District.builder().name("District "+UUID.randomUUID()).city(city).build());
            var neighborhood=persist(Neighborhood.builder().name("Neighborhood "+UUID.randomUUID()).district(district).build());
            venue=persist(Venue.builder().name("Venue "+UUID.randomUUID()).owner(user(null)).status(VenueStatus.APPROVED)
                    .address("Address").city(city).district(district).neighborhood(neighborhood).build());
            var owner=user("ROLE_LISTENER"); actor=owner.getId(); viewer=user(null).getId();
            profile=persist(ListenerProfile.builder().user(owner).visibilityMode(ListenerVisibilityMode.STANDARD)
                    .visibilityChoiceCompleted(true).build()).getId(); return null;
        });
    }
    @Test void migrationIsRepeatableEnforcesPrivacyConstraintsAndCascadesAccountDeletion() {
        UUID event=event(LocalTime.of(16,0),null); choose(event,EventIntent.GOING,true,"Note",0);
        new ResourceDatabasePopulator(new FileSystemResource("scripts/db/2026-09-08-event-audience-intents.sql")).execute(dataSource);
        assertThat(service.get(actor,event).version()).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update("update tbl_event_audience_intent set intent='MAYBE' where user_id=?",actor)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update tbl_event_audience_intent set published_on_profile=false where user_id=?",actor)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update tbl_event_audience_intent set version=-1 where user_id=?",actor)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        UUID bare=tx(() -> user(null).getId());
        jdbc.update("insert into tbl_event_audience_intent(user_id,event_id) values (?,?)",bare,event);
        jdbc.update("delete from tbl_user where id=?",bare);
        assertThat(jdbc.queryForObject("select count(*) from tbl_event_audience_intent where user_id=?",Long.class,bare)).isZero();
    }
    @Test void concurrentFirstChoicesSerializeAndExactRetriesNeverDuplicateOrAdvanceVersion() throws Exception {
        UUID event=event(LocalTime.of(16,0),null);
        try(var executor=Executors.newFixedThreadPool(8)) {
            var start=new CountDownLatch(1); var futures=new ArrayList<Future<Object>>();
            for(int i=0;i<8;i++) futures.add(executor.submit(() -> { start.await(); return choose(event,EventIntent.GOING,false,null,0); }));
            start.countDown(); for(var future:futures) assertThat(((EventIntentResponse.State)future.get(10,TimeUnit.SECONDS)).version()).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from tbl_event_audience_intent where user_id=? and event_id=?",Long.class,actor,event)).isEqualTo(1);
            var barrier=new CountDownLatch(1);
            Future<Object> first=executor.submit(() -> attemptAfter(barrier,() -> choose(event,EventIntent.THINKING,false,null,1)));
            Future<Object> second=executor.submit(() -> attemptAfter(barrier,() -> choose(event,EventIntent.NONE,false,null,1)));
            barrier.countDown(); var results=List.of(first.get(10,TimeUnit.SECONDS),second.get(10,TimeUnit.SECONDS));
            assertThat(results.stream().filter(EventIntentResponse.State.class::isInstance)).hasSize(1);
            assertThat(results.stream().filter(value -> value==ErrorType.EVENT_INTENT_VERSION_CONFLICT)).hasSize(1);
            assertThat(service.get(actor,event).version()).isEqualTo(2);
        }
    }
    @Test void independentAudienceActorsShareEventReadFenceWhileEventWritesRemainExcluded() throws Exception {
        UUID event=event(LocalTime.of(16,0),null);
        UUID other=tx(() -> { var user=user("ROLE_LISTENER");
            persist(ListenerProfile.builder().user(user).visibilityChoiceCompleted(true).build()); return user.getId(); });
        var locked=new CountDownLatch(1); var release=new CountDownLatch(1);
        try(var executor=Executors.newSingleThreadExecutor()) {
            var holder=executor.submit(() -> tx(() -> {
                repository.lockActor(other).orElseThrow(); repository.listenerVisibility(other).orElseThrow();
                repository.lockEvent(event).orElseThrow(); locked.countDown();
                try { if(!release.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("Test release timed out"); }
                catch(InterruptedException interrupted) { throw new RuntimeException(interrupted); }
                return null;
            }));
            try {
                assertThat(locked.await(5,TimeUnit.SECONDS)).isTrue();
                // NOWAIT makes unwanted cross-attendee serialization deterministic, without timing guesses.
                assertThat(jdbc.queryForObject("select id from tbl_event where id=? for share nowait",UUID.class,event)).isEqualTo(event);
                assertThat(choose(event,EventIntent.GOING,false,null,0).version()).isEqualTo(1);
                // The same fence must still prevent event deletion/schedule/consent edits until it releases.
                var blocked=catchThrowableOfType(() -> jdbc.queryForObject("select id from tbl_event where id=? for update nowait",UUID.class,event),
                        org.springframework.dao.DataAccessException.class);
                assertThat(blocked).isNotNull();
                assertThat(blocked.getMostSpecificCause()).isInstanceOfSatisfying(java.sql.SQLException.class,
                        failure -> assertThat(failure.getSQLState()).isEqualTo("55P03"));
            } finally { release.countDown(); }
            holder.get(10,TimeUnit.SECONDS);
        }
    }
    @Test void privateAndPublicPaginationStayStableOnLastAndBeyondPagesAndDecorateInOneBatch() {
        var expected=new ArrayList<UUID>();
        tx(() -> { for(int i=0;i<67;i++) {
            var value=newEvent(date,LocalTime.of(16,0),null); value.setPosterImage(UUID.randomUUID().toString());
            var intent=new EventAudienceIntent(actor,value.getId()); intent.setIntent(EventIntent.THINKING);
            intent.setPublishedOnProfile(true); intent.setNote("Plan "+i); intent.setPublishedAt(clock.instant());
            intent.setUpdatedAt(clock.instant()); intent.setVersion(1); em.persist(intent); expected.add(value.getId());
        } return null; });
        expected.sort(Comparator.comparing(UUID::toString));
        for(int page:new int[]{0,1,3}) {
            clearInvocations(media);
            var stats=em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics(); stats.clear();
            var mine=service.mine(actor,EventIntentPeriod.UPCOMING,page,20);
            assertThat(mine.content()).extracting(EventIntentResponse.State::eventId).containsExactlyElementsOf(expected.subList(page*20,Math.min(67,page*20+20)));
            assertThat(mine.totalElements()).isEqualTo(67); assertThat(mine.totalPages()).isEqualTo(4); assertThat(mine.last()).isEqualTo(page==3);
            assertThat(stats.getPrepareStatementCount()).isLessThanOrEqualTo(12);
            verify(media,times(1)).getDisplayUrlMap(anyList());
            var posts=service.posts(viewer,profile,EventIntentPeriod.ALL,page,20);
            assertThat(posts.content()).extracting(EventIntentResponse.Post::eventId).containsExactlyElementsOf(expected.subList(page*20,Math.min(67,page*20+20)));
            assertThat(posts.totalElements()).isEqualTo(67);
        }
        var beyond=service.mine(actor,EventIntentPeriod.ALL,1000,20);
        assertThat(beyond.content()).isEmpty(); assertThat(beyond.totalElements()).isEqualTo(67);
        assertThat(service.posts(viewer,profile,EventIntentPeriod.ALL,1000,20).content()).isEmpty();
    }
    @Test void calendarPeriodsUseIstanbulExactEndAndExistingOneHourFallbackNotJvmTimezone() {
        UUID exact=event(LocalTime.of(14,0),null), live=event(LocalTime.of(14,1),null), invalidEnd=event(LocalTime.of(14,0),LocalTime.of(13,0));
        clock.now.set(Instant.parse("2026-09-08T10:00:00Z"));
        for(UUID id:List.of(exact,live,invalidEnd)) choose(id,EventIntent.GOING,true,null,0);
        clock.now.set(Instant.parse("2026-09-08T12:00:00Z"));
        assertThat(service.mine(actor,EventIntentPeriod.PAST,0,20).content()).extracting(EventIntentResponse.State::eventId).containsExactlyInAnyOrder(exact,invalidEnd);
        assertThat(service.mine(actor,EventIntentPeriod.UPCOMING,0,20).content()).extracting(EventIntentResponse.State::eventId).containsExactly(live);
        assertThat(service.posts(viewer,profile,EventIntentPeriod.PAST,0,20).content()).allMatch(EventIntentResponse.Post::eventEnded);
        assertThat(service.get(actor,exact).eventEnded()).isTrue();
        UUID acrossMidnight=tx(() -> newEvent(date.minusDays(1),LocalTime.of(23,30),null).getId());
        clock.now.set(Instant.parse("2026-09-07T20:00:00Z")); choose(acrossMidnight,EventIntent.THINKING,false,null,0);
        clock.now.set(Instant.parse("2026-09-07T21:15:00Z"));
        assertThat(service.get(actor,acrossMidnight).eventEnded()).isFalse();
        assertThat(service.mine(actor,EventIntentPeriod.UPCOMING,0,20).content()).extracting(EventIntentResponse.State::eventId).contains(acrossMidnight);
        clock.now.set(Instant.parse("2026-09-07T21:30:00Z"));
        assertThat(service.mine(actor,EventIntentPeriod.PAST,0,20).content()).extracting(EventIntentResponse.State::eventId).contains(acrossMidnight);
    }
    @Test void ownerIsolationCurrentVenueEligibilityAndGhostVisibilityDoNotLeakNotes() {
        UUID event=event(LocalTime.of(16,0),null); choose(event,EventIntent.GOING,true,"Owner note",0);
        UUID other=tx(() -> { var user=user("ROLE_LISTENER"); persist(ListenerProfile.builder().user(user).visibilityChoiceCompleted(true).build()); return user.getId(); });
        assertThat(service.get(other,event).intent()).isEqualTo(EventIntent.NONE);
        assertThat(service.get(other,event).note()).isNull(); assertThat(service.mine(other,EventIntentPeriod.ALL,0,20).content()).isEmpty();
        setGhost(true); assertThat(service.posts(viewer,profile,EventIntentPeriod.ALL,0,20).totalElements()).isZero();
        var hidden=choose(event,EventIntent.THINKING,true,"Owner note",1); assertThat(hidden.publicationVisible()).isFalse();
        assertThatThrownBy(() -> choose(event,EventIntent.GOING,true,"New note",2)).isInstanceOf(SoundConnectException.class);
        setGhost(false); assertThat(service.posts(viewer,profile,EventIntentPeriod.ALL,0,20).content().getFirst().intent()).isEqualTo(EventIntent.THINKING);
        tx(() -> { em.find(Venue.class,venue.getId()).setStatus(VenueStatus.PENDING); return null; });
        assertThat(service.posts(viewer,profile,EventIntentPeriod.ALL,0,20).content()).isEmpty();
        assertThat(service.mine(actor,EventIntentPeriod.ALL,0,20).content()).isEmpty();
        assertThat(service.get(actor,event).eventAvailable()).isFalse();
        choose(event,EventIntent.NONE,false,null,2);
        assertThat(service.get(actor,event).note()).isNull();
    }
    @Test void ghostTransitionFencePreventsPublicationAfterTheVisibilityChangeCommits() throws Exception {
        UUID event=event(LocalTime.of(16,0),null); choose(event,EventIntent.THINKING,false,null,0);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var locked=new CountDownLatch(1); var release=new CountDownLatch(1);
            var ghost=executor.submit(() -> tx(() -> { var value=listeners.findByUserIdForUpdate(actor).orElseThrow();
                value.setVisibilityMode(ListenerVisibilityMode.GHOST); locked.countDown();
                try { if(!release.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("Test release timed out"); }
                catch(InterruptedException interrupted) { throw new RuntimeException(interrupted); } return null; }));
            assertThat(locked.await(5,TimeUnit.SECONDS)).isTrue();
            var publish=executor.submit(() -> { try { return (Object)choose(event,EventIntent.THINKING,true,"Hidden?",1); }
                catch(SoundConnectException failure) { return failure.getErrorType(); } });
            release.countDown(); ghost.get(10,TimeUnit.SECONDS);
            assertThat(publish.get(10,TimeUnit.SECONDS)).isEqualTo(ErrorType.FORBIDDEN_ACCESS);
            assertThat(service.get(actor,event).publishedOnProfile()).isFalse();
            assertThat(service.posts(viewer,profile,EventIntentPeriod.ALL,0,20).content()).isEmpty();
        }
    }
    @Test void requestScopedPersistenceContextCannotReusePreflightVisibilityAfterGhostTransition() {
        UUID event=event(LocalTime.of(16,0),null);
        var factory=em.getEntityManagerFactory();
        assertThat(TransactionSynchronizationManager.hasResource(factory)).isFalse();
        var requestEntityManager=factory.createEntityManager();
        TransactionSynchronizationManager.bindResource(factory,new EntityManagerHolder(requestEntityManager));
        try {
            // Mirrors the production OSIV context shared by authority preflight and the later PUT transaction.
            service.requireAuthority(actor);
            assertThat(requestEntityManager.find(ListenerProfile.class,profile).isGhost()).isFalse();
            // A separately committed visibility change while the request is checking Redis.
            jdbc.update("update \"tbl_listener-profile\" set visibility_mode='GHOST',version=version+1 where id=?",profile);
            assertThatThrownBy(() -> choose(event,EventIntent.GOING,true,"Should stay private",0))
                    .isInstanceOfSatisfying(SoundConnectException.class,
                            failure -> assertThat(failure.getErrorType()).isEqualTo(ErrorType.FORBIDDEN_ACCESS));
            assertThat(jdbc.queryForObject("select count(*) from tbl_event_audience_intent where user_id=? and event_id=?",Long.class,actor,event)).isZero();
        } finally {
            TransactionSynchronizationManager.unbindResource(factory);
            requestEntityManager.close();
        }
    }
    @Test void cachedStandardProfileCannotExposePostsAfterSeparatelyCommittedGhostTransition() {
        UUID event=event(LocalTime.of(16,0),null); choose(event,EventIntent.GOING,true,"Private in ghost",0);
        var factory=em.getEntityManagerFactory();
        assertThat(TransactionSynchronizationManager.hasResource(factory)).isFalse();
        var requestEntityManager=factory.createEntityManager();
        TransactionSynchronizationManager.bindResource(factory,new EntityManagerHolder(requestEntityManager));
        try {
            assertThat(requestEntityManager.find(ListenerProfile.class,profile).isGhost()).isFalse();
            jdbc.update("update \"tbl_listener-profile\" set visibility_mode='GHOST',version=version+1 where id=?",profile);
            var own=service.get(actor,event);
            assertThat(own.publishedOnProfile()).isTrue(); assertThat(own.publicationVisible()).isFalse();
            assertThat(own.canPublish()).isFalse();
            assertThat(service.posts(viewer,profile,EventIntentPeriod.ALL,0,20).totalElements()).isZero();
        } finally {
            TransactionSynchronizationManager.unbindResource(factory);
            requestEntityManager.close();
        }
    }
    @Test void deletedEventsAndMalformedLegacySchedulesAreHiddenAndKnownOwnersCanClear() {
        UUID event=event(LocalTime.of(16,0),null); choose(event,EventIntent.GOING,true,"Delete me",0);
        jdbc.update("delete from tbl_event where id=?",event);
        assertThat(service.get(actor,event).event()).isNull();
        assertThat(service.posts(viewer,profile,EventIntentPeriod.ALL,0,20).content()).isEmpty();
        assertThat(choose(event,EventIntent.NONE,false,null,1).version()).isEqualTo(2);
        UUID malformed=event(LocalTime.of(16,0),null); choose(malformed,EventIntent.THINKING,false,null,0);
        jdbc.execute("alter table tbl_event alter column start_time drop not null");
        try {
            jdbc.update("update tbl_event set start_time=null where id=?",malformed);
            assertThat(service.get(actor,malformed).eventAvailable()).isFalse();
            assertThat(service.mine(actor,EventIntentPeriod.ALL,0,20).content()).isEmpty();
            choose(malformed,EventIntent.NONE,false,null,1);
        } finally { jdbc.update("delete from tbl_event where id=?",malformed); jdbc.execute("alter table tbl_event alter column start_time set not null"); }
    }
    @Test void musicianCanMaintainPrivateIntentButBusinessInactiveOrUnverifiedAccountsCannotAct() {
        UUID event=event(LocalTime.of(16,0),null);
        UUID musician=tx(() -> {var user=user("ROLE_MUSICIAN"); persist(MusicianProfile.builder().user(user).stageName("Stage").build()); return user.getId();});
        assertThat(service.update(musician,event,new EventIntentUpdate(EventIntent.GOING,false,null,0L)).canPublish()).isFalse();
        assertThatThrownBy(() -> service.update(musician,event,new EventIntentUpdate(EventIntent.GOING,true,null,1L))).isInstanceOf(SoundConnectException.class);
        tx(() -> { em.find(User.class,actor).setEmailVerified(false); return null; });
        assertThatThrownBy(() -> service.get(actor,event)).isInstanceOf(SoundConnectException.class);
        assertThatThrownBy(() -> service.posts(viewer,profile,EventIntentPeriod.ALL,0,20)).isInstanceOf(SoundConnectException.class);
        assertThatThrownBy(() -> service.get(venue.getOwner().getId(),event)).isInstanceOf(SoundConnectException.class);
    }
    private Object attemptAfter(CountDownLatch latch,Supplier<Object> action) throws InterruptedException {
        latch.await(); try { return action.get(); } catch(SoundConnectException failure) { return failure.getErrorType(); }
    }
    private EventIntentResponse.State choose(UUID event,EventIntent intent,boolean published,String note,long version) {
        return service.update(actor,event,new EventIntentUpdate(intent,published,note,version));
    }
    private void setGhost(boolean ghost) { tx(() -> { em.find(ListenerProfile.class,profile).setVisibilityMode(ghost?ListenerVisibilityMode.GHOST:ListenerVisibilityMode.STANDARD); return null; }); }
    private UUID event(LocalTime start,LocalTime end) { return tx(() -> newEvent(date,start,end).getId()); }
    private Event newEvent(LocalDate day,LocalTime start,LocalTime end) {
        return persist(Event.builder().title("Event "+UUID.randomUUID()).venue(em.getReference(Venue.class,venue.getId()))
                .eventDate(day).startTime(start).endTime(end).build());
    }
    private User user(String roleName) {
        Set<Role> roles=new HashSet<>();
        if(roleName!=null) { var found=em.createQuery("select role from Role role where role.name=:name",Role.class).setParameter("name",roleName).getResultList();
            roles.add(found.isEmpty()?persist(Role.builder().name(roleName).build()):found.getFirst()); }
        return persist(User.builder().username("aud"+UUID.randomUUID().toString().replace("-","").substring(0,14))
                .email(UUID.randomUUID()+"@test.invalid").password("unused-test-password").roles(roles).status(UserStatus.ACTIVE).emailVerified(true).build());
    }
    private <T>T persist(T value) { em.persist(value); return value; }
    private <T>T tx(Supplier<T> work) { return new TransactionTemplate(manager).execute(status -> work.get()); }
    static class TestClock extends EventScheduleClock { final AtomicReference<Instant> now=new AtomicReference<>(); @Override public Instant instant(){return now.get();} }
    @Configuration(proxyBeanMethods=false) @EnableJpaAuditing
    @EnableJpaRepositories(basePackageClasses={EventAudienceRepository.class,EventDiscoveryRepository.class,UserRepository.class,ListenerProfileRepository.class})
    @EntityScan(basePackages="com.berkayb.soundconnect") @Import({EventAudienceService.class,EventDiscoveryService.class})
    static class Config {
        @Bean DataSource dataSource() { if(!POSTGRES.isRunning()) throw new IllegalStateException("Disposable PostgreSQL is not running");
            return new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()); }
        @Bean TestClock clock(){return new TestClock();}
        @Bean MediaAssetService media(){return mock(MediaAssetService.class);}
        @Bean EventShareUrlBuilder shareUrls(){return new EventShareUrlBuilder("https://example.test");}
    }
}

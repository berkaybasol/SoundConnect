package com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.service;

import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.dto.request.ArtistVenueConnectionRequestCreateDto;
import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.dto.response.ArtistVenueConnectionRequestResponseDto;
import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.entity.ArtistVenueConnectionRequest;
import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.enums.RequestByType;
import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.enums.RequestStatus;
import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.mapper.ArtistVenueConnectionRequestMapper;
import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.repository.ArtistVenueConnectionRequestRepository;
import com.berkayb.soundconnect.modules.location.entity.*;
import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.notification.service.TransactionalNotificationService;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity.*;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.enums.*;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.mapper.BandMapper;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.service.BandService;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.service.BandServiceImpl;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.support.BandEntityFinder;
import com.berkayb.soundconnect.modules.event.performer.service.EventPerformerRequestService;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.entity.MusicianProfile;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.modules.user.support.UserEntityFinder;
import com.berkayb.soundconnect.modules.venue.entity.Venue;
import com.berkayb.soundconnect.modules.venue.enums.VenueStatus;
import com.berkayb.soundconnect.shared.exception.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Runs only against its disposable container; never the developer's live database. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ArtistVenueConnectionRequestServiceImpl.class, BandServiceImpl.class})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ArtistVenueConnectionRequestConcurrencyPostgresTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine")
            .withDatabaseName("artist_venue_connections")
            .withUsername("soundconnect").withPassword("soundconnect");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
    }

    @Autowired ArtistVenueConnectionRequestService connections;
    @Autowired ArtistVenueConnectionRequestRepository requests;
    @Autowired BandService bandService;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoBean ArtistVenueConnectionRequestMapper mapper;
    @MockitoBean MediaAssetService media;
    @MockitoBean TransactionalNotificationService notifications;
    @MockitoBean BandMapper bandMapper;
    @MockitoBean BandEntityFinder bandEntityFinder;
    @MockitoBean UserEntityFinder userEntityFinder;
    @MockitoBean EventPerformerRequestService eventPerformerRequests;

    @BeforeEach
    void mapResponses() {
        reset(notifications);
        when(mapper.toResponseDto(any())).thenAnswer(invocation -> {
            ArtistVenueConnectionRequest request = invocation.getArgument(0);
            return new ArtistVenueConnectionRequestResponseDto(request.getId(),
                    request.getMusicianProfile() == null ? null : request.getMusicianProfile().getId(),
                    request.getBand() == null ? null : request.getBand().getId(),
                    request.getVenue().getId(), "Musician", "Band", null, "Venue", request.getMessage(),
                    request.getStatus().name(), request.getRequestByType(),
                    request.getCreatedAt() == null ? null : request.getCreatedAt().toString());
        });
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void concurrentOppositeSideCreatesProduceExactlyOnePendingRequest(boolean band) throws Exception {
        Fixture fixture = fixture();
        CountDownLatch firstReady = new CountDownLatch(1), releaseFirst = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> tx().execute(status -> {
                var created = create(fixture, band, false);
                firstReady.countDown();
                await(releaseFirst);
                return created;
            }));
            try {
                assertThat(firstReady.await(10, TimeUnit.SECONDS)).isTrue();
                var second = pool.submit(() -> attempt(() -> create(fixture, band, true)));
                assertThatThrownBy(() -> second.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                releaseFirst.countDown();
                assertThat(first.get(10, TimeUnit.SECONDS).status()).isEqualTo("PENDING");
                assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo(ErrorType.REQUEST_PENDING_ALREADY);
            } finally {
                releaseFirst.countDown();
            }
        }
        assertThat(statuses(fixture)).containsExactly(RequestStatus.PENDING);
        verify(notifications).persistInCurrentTransaction(any());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void concurrentAcceptanceAndRejectionCannotOverwriteTheFirstDecision(boolean band) throws Exception {
        Fixture fixture = fixture();
        UUID requestId = create(fixture, band, false).id();
        reset(notifications);
        CountDownLatch firstReady = new CountDownLatch(1), releaseFirst = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> tx().execute(status -> {
                var accepted = connections.acceptRequest(fixture.owner(), requestId);
                firstReady.countDown();
                await(releaseFirst);
                return accepted;
            }));
            try {
                assertThat(firstReady.await(10, TimeUnit.SECONDS)).isTrue();
                var second = pool.submit(() -> attempt(() -> connections.rejectRequest(fixture.owner(), requestId)));
                assertThatThrownBy(() -> second.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                releaseFirst.countDown();
                assertThat(first.get(10, TimeUnit.SECONDS).status()).isEqualTo("ACCEPTED");
                assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo(ErrorType.REQUEST_ALREADY_ACCEPTED);
            } finally {
                releaseFirst.countDown();
            }
        }
        assertThat(statuses(fixture)).containsExactly(RequestStatus.ACCEPTED);
        assertConnected(fixture, band, true);
        verify(notifications).persistInCurrentTransaction(any());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void oldDisconnectedRequestCannotUndoAReconnection(boolean band) {
        Fixture fixture = fixture();
        UUID oldId = create(fixture, band, false).id();
        connections.acceptRequest(fixture.owner(), oldId);
        assertThat(attempt(() -> create(fixture, band, true))).isEqualTo(ErrorType.REQUEST_ALREADY_ACCEPTED);
        connections.disconnect(fixture.actor(), oldId);
        UUID currentId = create(fixture, band, true).id();
        connections.acceptRequest(fixture.actor(), currentId);
        assertThat(attempt(() -> connections.disconnect(fixture.actor(), oldId))).isEqualTo(ErrorType.REQUEST_ALREADY_REJECTED);
        assertConnected(fixture, band, true);
        assertThat(statuses(fixture)).containsExactlyInAnyOrder(RequestStatus.ACCEPTED, RequestStatus.REJECTED);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void notificationPersistenceFailureRollsBackRequestAndConnectionChanges(boolean band) {
        Fixture fixture = fixture();
        doThrow(new IllegalStateException("inbox unavailable")).when(notifications).persistInCurrentTransaction(any());
        assertThatThrownBy(() -> create(fixture, band, false)).hasMessage("inbox unavailable");
        assertThat(statuses(fixture)).isEmpty();
        reset(notifications);
        UUID requestId = create(fixture, band, false).id();
        doThrow(new IllegalStateException("inbox unavailable")).when(notifications).persistInCurrentTransaction(any());
        assertThatThrownBy(() -> connections.acceptRequest(fixture.owner(), requestId)).hasMessage("inbox unavailable");
        assertThat(statuses(fixture)).containsExactly(RequestStatus.PENDING);
        assertConnected(fixture, band, false);
    }

    @Test
    void acceptingTwoIdenticallyNamedMusiciansDoesNotCollapseTheVenueRoster() {
        Fixture fixture = fixture();
        UUID firstRequest = create(fixture, false, false).id();
        UUID[] secondIdentity = tx().execute(status -> {
            User secondUser = user();
            MusicianProfile secondProfile = MusicianProfile.builder().user(secondUser).stageName("Musician").build();
            em.persist(secondProfile);
            return new UUID[] {secondUser.getId(), secondProfile.getId()};
        });
        UUID secondRequest = connections.createRequest(secondIdentity[0],
                new ArtistVenueConnectionRequestCreateDto(secondIdentity[1], null, fixture.venue(), "Connection"),
                RequestByType.ARTIST).id();
        connections.acceptRequest(fixture.owner(), firstRequest);
        connections.acceptRequest(fixture.owner(), secondRequest);

        tx().executeWithoutResult(status -> {
            Number joins = (Number) em.createNativeQuery("select count(*) from musician_profile_venues where venue_id = :venue")
                    .setParameter("venue", fixture.venue()).getSingleResult();
            assertThat(joins.longValue()).isEqualTo(2);
            assertThat(em.find(Venue.class, fixture.venue()).getActiveMusicians())
                    .extracting(MusicianProfile::getId).containsExactlyInAnyOrder(fixture.profile(), secondIdentity[1]);
        });
        connections.disconnect(fixture.owner(), firstRequest);
        tx().executeWithoutResult(status -> assertThat(em.find(Venue.class, fixture.venue()).getActiveMusicians())
                .extracting(MusicianProfile::getId).containsExactly(secondIdentity[1]));
    }

    @Test
    void musicianIdentityComparisonDoesNotInitializeALazyProxy() {
        Fixture fixture = fixture();
        MusicianProfile detached = tx().execute(status -> em.find(MusicianProfile.class, fixture.profile()));
        tx().executeWithoutResult(status -> {
            MusicianProfile proxy = em.getReference(MusicianProfile.class, fixture.profile());
            var persistence = em.getEntityManagerFactory().getPersistenceUnitUtil();
            assertThat(persistence.isLoaded(proxy)).isFalse();
            assertThat(detached.equals(proxy)).isTrue();
            assertThat(proxy.equals(detached)).isTrue();
            assertThat(proxy.hashCode()).isEqualTo(detached.hashCode());
            assertThat(persistence.isLoaded(proxy)).isFalse();
        });
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void bothLoadedRelationshipSidesStayConsistentInsideTheAcceptDisconnectTransaction(boolean bandTarget) {
        Fixture fixture = fixture();
        UUID request = create(fixture, bandTarget, false).id();
        tx().executeWithoutResult(status -> {
            Band band = em.find(Band.class, fixture.band());
            MusicianProfile musician = em.find(MusicianProfile.class, fixture.profile());
            Venue venue = em.find(Venue.class, fixture.venue());
            assertThat(band.getActiveVenues()).isEmpty();
            assertThat(musician.getActiveVenues()).isEmpty();
            assertThat(venue.getActiveBands()).isEmpty();
            assertThat(venue.getActiveMusicians()).isEmpty();
            connections.acceptRequest(fixture.owner(), request);
            assertThat(bandTarget ? band.getActiveVenues() : musician.getActiveVenues()).contains(venue);
            connections.disconnect(fixture.actor(), request);
            assertThat(band.getActiveVenues()).isEmpty();
            assertThat(musician.getActiveVenues()).isEmpty();
            assertThat(venue.getActiveBands()).isEmpty();
            assertThat(venue.getActiveMusicians()).isEmpty();
        });
        assertConnected(fixture, bandTarget, false);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void bandDeletionAndConnectionAcceptanceShareOneLockOrderAndLeaveNoOrphans(boolean deletionWins) throws Exception {
        Fixture fixture = fixture();
        UUID request = create(fixture, true, false).id();
        CountDownLatch firstReady = new CountDownLatch(1), releaseFirst = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> tx().execute(status -> {
                if (deletionWins) bandService.deleteBand(fixture.band(), fixture.actor());
                else connections.acceptRequest(fixture.owner(), request);
                em.flush();
                firstReady.countDown();
                await(releaseFirst);
                return true;
            }));
            try {
                assertThat(firstReady.await(10, TimeUnit.SECONDS)).isTrue();
                var second = pool.submit(() -> attempt(() -> {
                    if (deletionWins) connections.acceptRequest(fixture.owner(), request);
                    else bandService.deleteBand(fixture.band(), fixture.actor());
                }));
                assertThatThrownBy(() -> second.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                releaseFirst.countDown();
                assertThat(first.get(10, TimeUnit.SECONDS)).isTrue();
                assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo(deletionWins ? ErrorType.REQUEST_NOT_FOUND : null);
            } finally { releaseFirst.countDown(); }
        }
        tx().executeWithoutResult(status -> {
            assertThat(em.find(Band.class, fixture.band())).isNull();
            assertThat(requests.findById(request)).isEmpty();
            Number joins = (Number) em.createNativeQuery("select count(*) from venue_active_bands where band_id = :band")
                    .setParameter("band", fixture.band()).getSingleResult();
            assertThat(joins.longValue()).isZero();
        });
    }

    @Test
    void bandAndMembershipIdentityComparisonDoesNotInitializeLazyProxies() {
        Fixture fixture = fixture();
        Band detachedBand = tx().execute(status -> em.find(Band.class, fixture.band()));
        BandMember detachedMember = tx().execute(status -> em.createQuery(
                "select member from BandMember member where member.band.id = :band", BandMember.class)
                .setParameter("band", fixture.band()).getSingleResult());
        tx().executeWithoutResult(status -> {
            Band bandProxy = em.getReference(Band.class, fixture.band());
            BandMember memberProxy = em.getReference(BandMember.class, detachedMember.getId());
            var persistence = em.getEntityManagerFactory().getPersistenceUnitUtil();
            assertThat(persistence.isLoaded(bandProxy)).isFalse();
            assertThat(persistence.isLoaded(memberProxy)).isFalse();
            assertThat(detachedBand.equals(bandProxy)).isTrue();
            assertThat(bandProxy.equals(detachedBand)).isTrue();
            assertThat(bandProxy.hashCode()).isEqualTo(detachedBand.hashCode());
            assertThat(detachedMember.equals(memberProxy)).isTrue();
            assertThat(memberProxy.equals(detachedMember)).isTrue();
            assertThat(memberProxy.hashCode()).isEqualTo(detachedMember.hashCode());
            assertThat(persistence.isLoaded(bandProxy)).isFalse();
            assertThat(persistence.isLoaded(memberProxy)).isFalse();
        });
    }

    @ParameterizedTest
    @CsvSource({"false,false,false", "false,false,true", "false,true,false", "false,true,true",
            "true,false,false", "true,false,true", "true,true,false", "true,true,true"})
    void newConnectionsRequireUsableAccountsButTheActivePartyCanStillCleanUp(
            boolean band, boolean venueAccount, boolean unverified) {
        Fixture fixture = fixture();
        UUID blocked = venueAccount ? fixture.owner() : fixture.actor();
        disable(blocked, unverified);
        assertThat(attempt(() -> create(fixture, band, false))).isEqualTo(ErrorType.REQUEST_PARTICIPANT_UNAVAILABLE);
        assertThat(statuses(fixture)).isEmpty();
        enable(blocked);
        UUID pendingId = create(fixture, band, false).id();
        disable(blocked, unverified);
        reset(notifications);
        assertThat(attempt(() -> connections.acceptRequest(fixture.owner(), pendingId)))
                .isEqualTo(ErrorType.REQUEST_PARTICIPANT_UNAVAILABLE);
        assertThat(statuses(fixture)).containsExactly(RequestStatus.PENDING);
        assertConnected(fixture, band, false);
        verifyNoInteractions(notifications);
        if (venueAccount) connections.cancelRequest(fixture.actor(), pendingId);
        else connections.rejectRequest(fixture.owner(), pendingId);
        assertThat(statuses(fixture)).containsExactly(RequestStatus.REJECTED);

        enable(blocked);
        UUID acceptedId = create(fixture, band, false).id();
        connections.acceptRequest(fixture.owner(), acceptedId);
        disable(blocked, unverified);
        assertConnected(fixture, band, true); // Disabling does not silently rewrite existing relationships.
        connections.disconnect(venueAccount ? fixture.actor() : fixture.owner(), acceptedId);
        assertConnected(fixture, band, false);
    }

    @Test
    void activeVerifiedManagerCanKeepRepresentingABandWithAnInactiveFounder() {
        Fixture fixture = fixture();
        UUID manager = tx().execute(status -> {
            User managerUser = user();
            em.persist(MusicianProfile.builder().user(managerUser).stageName("Manager").build());
            em.persist(BandMember.builder().band(em.find(Band.class, fixture.band())).user(managerUser)
                    .bandRole(BandRole.MANAGER).status(BandMemberShipStatus.ACTIVE).build());
            return managerUser.getId();
        });
        disable(fixture.actor(), false);
        UUID request = create(fixture, true, true).id();
        assertThat(attempt(() -> connections.acceptRequest(fixture.actor(), request)))
                .isEqualTo(ErrorType.REQUEST_PARTICIPANT_UNAVAILABLE);
        connections.acceptRequest(manager, request);
        assertConnected(fixture, true, true);
    }

    @ParameterizedTest @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void cancellationWinningTheRacePreventsTheOtherSideFromAccepting(boolean band, boolean venueInitiated) throws Exception {
        Fixture fixture = fixture();
        UUID request = create(fixture, band, venueInitiated).id();
        UUID sender = venueInitiated ? fixture.owner() : fixture.actor();
        UUID recipient = venueInitiated ? fixture.actor() : fixture.owner();
        reset(notifications);
        CountDownLatch firstReady = new CountDownLatch(1), releaseFirst = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var cancellation = pool.submit(() -> tx().execute(status -> {
                var result = connections.cancelRequest(sender, request);
                firstReady.countDown();
                await(releaseFirst);
                return result;
            }));
            try {
                assertThat(firstReady.await(10, TimeUnit.SECONDS)).isTrue();
                var acceptance = pool.submit(() -> attempt(() -> connections.acceptRequest(recipient, request)));
                assertThatThrownBy(() -> acceptance.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                releaseFirst.countDown();
                assertThat(cancellation.get(10, TimeUnit.SECONDS).status()).isEqualTo("REJECTED");
                assertThat(acceptance.get(10, TimeUnit.SECONDS)).isEqualTo(ErrorType.REQUEST_ALREADY_REJECTED);
            } finally { releaseFirst.countDown(); }
        }
        assertThat(statuses(fixture)).containsExactly(RequestStatus.REJECTED);
        assertConnected(fixture, band, false);
        verifyNoInteractions(notifications);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void reconnectWaitsForConcurrentDisconnectAndUsesANewRequestIdentity(boolean band) throws Exception {
        Fixture fixture = fixture();
        UUID oldId = create(fixture, band, false).id();
        connections.acceptRequest(fixture.owner(), oldId);
        CountDownLatch firstReady = new CountDownLatch(1), releaseFirst = new CountDownLatch(1);
        UUID newId;
        try (var pool = Executors.newFixedThreadPool(2)) {
            var disconnection = pool.submit(() -> tx().execute(status -> {
                var result = connections.disconnect(fixture.actor(), oldId);
                firstReady.countDown();
                await(releaseFirst);
                return result;
            }));
            try {
                assertThat(firstReady.await(10, TimeUnit.SECONDS)).isTrue();
                var reconnect = pool.submit(() -> create(fixture, band, true));
                assertThatThrownBy(() -> reconnect.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                releaseFirst.countDown();
                assertThat(disconnection.get(10, TimeUnit.SECONDS).status()).isEqualTo("REJECTED");
                newId = reconnect.get(10, TimeUnit.SECONDS).id();
            } finally { releaseFirst.countDown(); }
        }
        assertThat(newId).isNotEqualTo(oldId);
        connections.acceptRequest(fixture.actor(), newId);
        assertThat(attempt(() -> connections.disconnect(fixture.owner(), oldId))).isEqualTo(ErrorType.REQUEST_ALREADY_REJECTED);
        assertConnected(fixture, band, true);
    }

    @Test
    void managerRemovalWinningBandLockPreventsAcceptanceFromTheOldMembership() throws Exception {
        Fixture fixture = fixture();
        tx().executeWithoutResult(status -> {
            BandMember actorMembership = em.createQuery("select member from BandMember member where member.band.id = :band", BandMember.class)
                    .setParameter("band", fixture.band()).getSingleResult();
            actorMembership.setBandRole(BandRole.MANAGER);
            User founder = user();
            em.persist(MusicianProfile.builder().user(founder).stageName("Founder").build());
            em.persist(BandMember.builder().band(em.find(Band.class, fixture.band())).user(founder)
                    .bandRole(BandRole.FOUNDER).status(BandMemberShipStatus.ACTIVE).build());
        });
        UUID request = create(fixture, true, true).id();
        reset(notifications);
        CountDownLatch firstReady = new CountDownLatch(1), releaseFirst = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var removal = pool.submit(() -> tx().execute(status -> {
                em.find(Band.class, fixture.band(), jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
                BandMember actorMembership = em.createQuery("select member from BandMember member where member.band.id = :band and member.user.id = :actor", BandMember.class)
                        .setParameter("band", fixture.band()).setParameter("actor", fixture.actor()).getSingleResult();
                actorMembership.setStatus(BandMemberShipStatus.LEFT);
                em.flush();
                firstReady.countDown();
                await(releaseFirst);
                return true;
            }));
            try {
                assertThat(firstReady.await(10, TimeUnit.SECONDS)).isTrue();
                var acceptance = pool.submit(() -> attempt(() -> connections.acceptRequest(fixture.actor(), request)));
                assertThatThrownBy(() -> acceptance.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                releaseFirst.countDown();
                assertThat(removal.get(10, TimeUnit.SECONDS)).isTrue();
                assertThat(acceptance.get(10, TimeUnit.SECONDS)).isEqualTo(ErrorType.FORBIDDEN_ACCESS);
            } finally { releaseFirst.countDown(); }
        }
        assertThat(statuses(fixture)).containsExactly(RequestStatus.PENDING);
        assertConnected(fixture, true, false);
        verifyNoInteractions(notifications);
    }

    @ParameterizedTest @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void concurrentAccountDisableIsRecheckedAfterWaitingWithoutUsingStaleManagedUser(
            boolean band, boolean accepting) throws Exception {
        Fixture fixture = fixture();
        UUID request = accepting ? create(fixture, band, false).id() : null;
        reset(notifications);
        CountDownLatch firstReady = new CountDownLatch(1), releaseFirst = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var deactivation = pool.submit(() -> tx().execute(status -> {
                em.find(User.class, fixture.actor()).setStatus(UserStatus.INACTIVE);
                em.flush();
                firstReady.countDown();
                await(releaseFirst);
                return true;
            }));
            try {
                assertThat(firstReady.await(10, TimeUnit.SECONDS)).isTrue();
                var mutation = pool.submit(() -> attempt(() -> {
                    if (accepting) connections.acceptRequest(fixture.owner(), request);
                    else create(fixture, band, true);
                }));
                assertThatThrownBy(() -> mutation.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                releaseFirst.countDown();
                assertThat(deactivation.get(10, TimeUnit.SECONDS)).isTrue();
                assertThat(mutation.get(10, TimeUnit.SECONDS)).isEqualTo(ErrorType.REQUEST_PARTICIPANT_UNAVAILABLE);
            } finally { releaseFirst.countDown(); }
        }
        if (accepting) assertThat(statuses(fixture)).containsExactly(RequestStatus.PENDING);
        else assertThat(statuses(fixture)).isEmpty();
        assertConnected(fixture, band, false);
        verifyNoInteractions(notifications);
    }

    private void disable(UUID account, boolean unverified) {
        tx().executeWithoutResult(status -> {
            User user = em.find(User.class, account);
            if (unverified) user.setEmailVerified(false);
            else user.setStatus(UserStatus.INACTIVE);
        });
    }

    private void enable(UUID account) {
        tx().executeWithoutResult(status -> {
            User user = em.find(User.class, account);
            user.setStatus(UserStatus.ACTIVE);
            user.setEmailVerified(true);
        });
    }

    @Test
    void privatePagesFilterDirectionBeforeCountingAndBreakTimestampTiesWithoutDroppingRows() {
        Fixture fixture = fixture();
        List<UUID> expectedIncoming = new ArrayList<>();
        tx().executeWithoutResult(status -> {
            for (int i = 0; i < 3; i++) expectedIncoming.add(history(fixture, false, RequestByType.ARTIST).getId());
            for (int i = 0; i < 2; i++) expectedIncoming.add(history(fixture, true, RequestByType.BAND).getId());
            history(fixture, false, RequestByType.VENUE);
            history(fixture, true, RequestByType.VENUE);
            em.flush();
            em.createNativeQuery("update artist_venue_connection_requests set created_at = timestamp '2026-09-07 12:00:00' where venue_id = :venue")
                    .setParameter("venue", fixture.venue()).executeUpdate();
        });
        Fixture unrelated = fixture();
        tx().executeWithoutResult(status -> history(unrelated, false, RequestByType.ARTIST));
        var first = connections.getVenuePage(fixture.owner(), fixture.venue(), RequestStatus.REJECTED, true, 0, 2);
        var second = connections.getVenuePage(fixture.owner(), fixture.venue(), RequestStatus.REJECTED, true, 1, 2);
        var last = connections.getVenuePage(fixture.owner(), fixture.venue(), RequestStatus.REJECTED, true, 2, 2);
        assertThat(first.totalElements()).isEqualTo(5);
        assertThat(first.totalPages()).isEqualTo(3);
        assertThat(first.first()).isTrue();
        assertThat(last.last()).isTrue();
        List<UUID> actual = new ArrayList<>();
        for (var page : List.of(first, second, last)) page.content().forEach(row -> actual.add(row.id()));
        expectedIncoming.sort(Comparator.comparing(UUID::toString).reversed());
        assertThat(actual).containsExactlyElementsOf(expectedIncoming);
        assertThat(connections.getVenuePage(fixture.owner(), fixture.venue(), null, false, 0, 20).totalElements()).isEqualTo(2);
        assertThat(connections.getMusicianPage(fixture.actor(), fixture.profile(), null, false, 0, 20).totalElements()).isEqualTo(3);
        assertThat(connections.getMusicianPage(fixture.actor(), fixture.profile(), null, true, 0, 20).totalElements()).isEqualTo(1);
        assertThat(connections.getBandPage(fixture.actor(), fixture.band(), null, false, 0, 20).totalElements()).isEqualTo(2);
        assertThat(connections.getBandPage(fixture.actor(), fixture.band(), null, true, 0, 20).totalElements()).isEqualTo(1);
        assertThat(connections.getVenuePage(fixture.owner(), fixture.venue(), RequestStatus.PENDING, null, 0, 20).content()).isEmpty();
        // Legacy profile consumers still receive the complete list.
        assertThat(connections.getRequestsByVenue(fixture.owner(), fixture.venue(), null)).hasSize(7);
    }

    private ArtistVenueConnectionRequest history(Fixture fixture, boolean band, RequestByType by) {
        ArtistVenueConnectionRequest request = ArtistVenueConnectionRequest.builder()
                .venue(em.find(Venue.class, fixture.venue()))
                .band(band ? em.find(Band.class, fixture.band()) : null)
                .musicianProfile(band ? null : em.find(MusicianProfile.class, fixture.profile()))
                .requestByType(by).status(RequestStatus.REJECTED).message("Private history").build();
        em.persist(request);
        return request;
    }

    private ArtistVenueConnectionRequestResponseDto create(Fixture fixture, boolean band, boolean venueInitiated) {
        return connections.createRequest(venueInitiated ? fixture.owner() : fixture.actor(),
                new ArtistVenueConnectionRequestCreateDto(band ? null : fixture.profile(), band ? fixture.band() : null,
                        fixture.venue(), "Connection"),
                venueInitiated ? RequestByType.VENUE : band ? RequestByType.BAND : RequestByType.ARTIST);
    }

    private List<RequestStatus> statuses(Fixture fixture) {
        return tx().execute(status -> requests.findAllByVenueId(fixture.venue()).stream()
                .map(ArtistVenueConnectionRequest::getStatus).toList());
    }

    private void assertConnected(Fixture fixture, boolean band, boolean connected) {
        tx().executeWithoutResult(status -> {
            Venue venue = em.find(Venue.class, fixture.venue());
            boolean actual = band ? venue.getActiveBands().stream().anyMatch(item -> item.getId().equals(fixture.band()))
                    : venue.getActiveMusicians().stream().anyMatch(item -> item.getId().equals(fixture.profile()));
            assertThat(actual).isEqualTo(connected);
        });
    }

    private ErrorType attempt(Runnable action) {
        try {
            action.run();
            return null;
        } catch (SoundConnectException error) {
            return error.getErrorType();
        }
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Transaction latch timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private TransactionTemplate tx() { return new TransactionTemplate(transactionManager); }

    private Fixture fixture() {
        return tx().execute(status -> {
            User actor = user(), owner = user();
            MusicianProfile profile = MusicianProfile.builder().user(actor).stageName("Musician").build();
            em.persist(profile);
            City city = City.builder().name("City " + UUID.randomUUID()).build(); em.persist(city);
            District district = District.builder().name("District").city(city).build(); em.persist(district);
            Neighborhood neighborhood = Neighborhood.builder().name("Neighborhood").district(district).build(); em.persist(neighborhood);
            Venue venue = Venue.builder().name("Venue").owner(owner).address("Ankara").city(city).district(district)
                    .neighborhood(neighborhood).status(VenueStatus.APPROVED).build(); em.persist(venue);
            Band band = Band.builder().name("Band " + UUID.randomUUID()).build(); em.persist(band);
            em.persist(BandMember.builder().band(band).user(actor).bandRole(BandRole.FOUNDER)
                    .status(BandMemberShipStatus.ACTIVE).build());
            return new Fixture(actor.getId(), owner.getId(), profile.getId(), band.getId(), venue.getId());
        });
    }

    private User user() {
        String name = "connection_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        User user = User.builder().username(name).email(name + "@example.com").password("unused")
                .emailVerified(true).status(UserStatus.ACTIVE).build();
        em.persist(user);
        return user;
    }

    private record Fixture(UUID actor, UUID owner, UUID profile, UUID band, UUID venue) {}
}

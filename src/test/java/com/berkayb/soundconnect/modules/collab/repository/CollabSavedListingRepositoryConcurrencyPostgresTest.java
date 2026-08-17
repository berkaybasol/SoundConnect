package com.berkayb.soundconnect.modules.collab.repository;

import com.berkayb.soundconnect.modules.collab.entity.Collab;
import com.berkayb.soundconnect.modules.collab.entity.CollabActor;
import com.berkayb.soundconnect.modules.collab.enums.CollabCadence;
import com.berkayb.soundconnect.modules.collab.enums.CollabClosureReason;
import com.berkayb.soundconnect.modules.collab.enums.CollabListingStatus;
import com.berkayb.soundconnect.modules.collab.enums.CollabWantedType;
import com.berkayb.soundconnect.modules.collab.spec.CollabSavedListingSpecifications;
import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.location.repository.CityRepository;
import com.berkayb.soundconnect.modules.profile.shared.media.enums.ProfileType;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Testcontainers(disabledWithoutDocker = true)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class CollabSavedListingRepositoryConcurrencyPostgresTest {
    private static final Instant NOW = Instant.parse("2026-08-11T09:00:00Z");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
    }

    @Autowired private UserRepository userRepository;
    @Autowired private CityRepository cityRepository;
    @Autowired private CollabActorRepository actorRepository;
    @Autowired private CollabRepository listingRepository;
    @Autowired private CollabSavedListingRepository savedRepository;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentPutRequestsShareTheListingLockAndCreateOneSavedRow() throws Exception {
        User owner = userRepository.saveAndFlush(user("owner"));
        User saver = userRepository.saveAndFlush(user("saver"));
        City city = cityRepository.saveAndFlush(City.builder().name("İstanbul").build());
        CollabActor actor = actorRepository.saveAndFlush(CollabActor.builder()
                .profileType(ProfileType.VENUE)
                .sourceProfileId(UUID.randomUUID())
                .displayName("Sahne")
                .build());
        Collab listing = listingRepository.saveAndFlush(Collab.builder()
                .owner(owner)
                .publisherActor(actor)
                .clientRequestId(UUID.randomUUID())
                .creationPayloadHash("a".repeat(64))
                .cadence(CollabCadence.REGULAR)
                .wantedType(CollabWantedType.BAND)
                .title("Akustik grup arıyoruz")
                .description("Düzenli sahne programımız için akustik bir grup arıyoruz.")
                .city(city)
                .genres(new ArrayList<>())
                .status(CollabListingStatus.OPEN)
                .publishedAt(NOW)
                .build());

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch sharedLocksAcquired = new CountDownLatch(2);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Integer> first = executor.submit(() -> saveOnce(
                    transaction, start, sharedLocksAcquired, saver.getId(), listing.getId()));
            Future<Integer> second = executor.submit(() -> saveOnce(
                    transaction, start, sharedLocksAcquired, saver.getId(), listing.getId()));
            start.countDown();

            assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(0, 1);
        }

        assertThat(savedRepository.count()).isOne();

        transaction.executeWithoutResult(status -> {
            Collab locked = listingRepository.findByIdForUpdate(listing.getId()).orElseThrow();
            locked.setStatus(CollabListingStatus.CLOSED);
            locked.setClosureReason(CollabClosureReason.OWNER_CLOSED);
            locked.setClosedAt(NOW.plusSeconds(60));
        });
        var visibleAfterClose = transaction.execute(status -> savedRepository.findAll(
                CollabSavedListingSpecifications.visible(
                        saver.getId(), CollabListingStatus.OPEN, NOW.plusSeconds(60)),
                PageRequest.of(0, 20)));
        assertThat(visibleAfterClose).isNotNull();
        assertThat(visibleAfterClose.getContent()).isEmpty();
    }

    private int saveOnce(TransactionTemplate transaction, CountDownLatch start,
                         CountDownLatch sharedLocksAcquired, UUID userId, UUID listingId) throws Exception {
        start.await();
        return transaction.execute(status -> {
            listingRepository.findVisibleOpenByIdForShare(listingId, CollabListingStatus.OPEN, NOW)
                    .orElseThrow();
            sharedLocksAcquired.countDown();
            await(sharedLocksAcquired);
            return savedRepository.insertIfAbsent(UUID.randomUUID(), userId, listingId);
        });
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Shared lock timeout");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while testing concurrent save", exception);
        }
    }

    private User user(String suffix) {
        return User.builder()
                .username("collab-" + suffix)
                .password("encoded-password")
                .email("collab-" + suffix + "@soundconnect.test")
                .status(UserStatus.ACTIVE)
                .emailVerified(true)
                .build();
    }
}

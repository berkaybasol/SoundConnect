package com.berkayb.soundconnect.modules.profile.MusicianProfile.service;

import com.berkayb.soundconnect.modules.instrument.repository.InstrumentRepository;
import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.service.BandService;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.dto.request.MusicianProfileSaveRequestDto;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.dto.response.MusicianProfileResponseDto;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.entity.MusicianProfile;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.mapper.MusicianProfileMapper;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.repository.MusicianProfileRepository;
import com.berkayb.soundconnect.modules.profile.shared.type.PersonalProfileTypePolicy;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.berkayb.soundconnect.modules.user.support.UserEntityFinder;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/** Real request-like persistence contexts may preload a profile before its write lock. */
@DataJpaTest(properties = {
        "spring.config.location=classpath:/application-test.yml",
        "spring.config.import=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect"
})
@ActiveProfiles("test")
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = MusicianProfileConcurrentUpdatePostgresTest.TestConfiguration.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MusicianProfileConcurrentUpdatePostgresTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.4-alpine")
            .withDatabaseName("musician_profile_concurrent_update_test")
            .withUsername("repository_test").withPassword("repository_test").withReuse(false);

    @Autowired DataSource dataSource;
    @Autowired EntityManager em;
    @Autowired UserRepository users;
    @Autowired MusicianProfileRepository profiles;
    @Autowired MusicianProfileService service;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoBean InstrumentRepository instruments;
    @MockitoBean BandService bands;
    @MockitoBean MediaAssetService media;
    @MockitoBean PersonalProfileTypePolicy profilePolicy;

    @BeforeEach
    void onlyUseTheDisposableDatabase() throws SQLException {
        assertThat(postgres.isRunning()).isTrue();
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getURL()).isEqualTo(postgres.getJdbcUrl());
            assertThat(connection.getCatalog()).isEqualTo(postgres.getDatabaseName());
        }
    }

    @Test
    void overlappingPartialUpdatesRefreshAProfilePreloadedBeforeTheWriteLock() throws Exception {
        UUID userId = transaction().execute(status -> {
            User user = users.saveAndFlush(User.builder().username("concurrentartist")
                    .email("concurrentartist@test.invalid").password("test-password")
                    .status(UserStatus.ACTIVE).emailVerified(true).build());
            profiles.saveAndFlush(MusicianProfile.builder().user(user).stageName("Unchanged name")
                    .description("Original bio").instagramUrl("https://example.com/original")
                    .spotifyTrackIds(new ArrayList<>(List.of("preserved-track"))).build());
            return user.getId();
        });
        CountDownLatch bothPreloaded = new CountDownLatch(2);
        CountDownLatch firstUpdated = new CountDownLatch(1);
        CountDownLatch secondAttempting = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> transaction().execute(status -> {
                preload(userId, bothPreloaded);
                MusicianProfileResponseDto result = service.updateProfile(userId, update("New concurrent bio", null));
                firstUpdated.countDown();
                await(releaseFirst);
                return result;
            }));
            var second = executor.submit(() -> transaction().execute(status -> {
                preload(userId, bothPreloaded);
                await(firstUpdated);
                secondAttempting.countDown();
                return service.updateProfile(userId, update(null, "https://example.com/concurrent"));
            }));
            try {
                assertThat(secondAttempting.await(15, TimeUnit.SECONDS)).isTrue();
                assertThatExceptionOfType(TimeoutException.class)
                        .isThrownBy(() -> second.get(300, TimeUnit.MILLISECONDS));
            } finally {
                releaseFirst.countDown();
            }
            assertThat(first.get(15, TimeUnit.SECONDS).bio()).isEqualTo("New concurrent bio");
            MusicianProfileResponseDto secondResponse = second.get(15, TimeUnit.SECONDS);
            assertThat(secondResponse.bio()).isEqualTo("New concurrent bio");
            assertThat(secondResponse.instagramUrl()).isEqualTo("https://example.com/concurrent");
            assertThat(secondResponse.spotifyTrackIds()).containsExactly("preserved-track");
        } finally {
            releaseFirst.countDown();
        }

        transaction().executeWithoutResult(status -> {
            MusicianProfile finalProfile = profiles.findByUserId(userId).orElseThrow();
            assertThat(finalProfile.getDescription()).isEqualTo("New concurrent bio");
            assertThat(finalProfile.getInstagramUrl()).isEqualTo("https://example.com/concurrent");
            assertThat(finalProfile.getStageName()).isEqualTo("Unchanged name");
            assertThat(finalProfile.getSpotifyTrackIds()).containsExactly("preserved-track");
        });
    }

    private void preload(UUID userId, CountDownLatch bothPreloaded) {
        MusicianProfile profile = profiles.findByUserId(userId).orElseThrow();
        assertThat(em.contains(profile)).isTrue();
        assertThat(profile.getDescription()).isEqualTo("Original bio");
        bothPreloaded.countDown();
        await(bothPreloaded);
    }

    private TransactionTemplate transaction() {
        return new TransactionTemplate(transactionManager);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(15, TimeUnit.SECONDS)) throw new AssertionError("Concurrent update coordination timed out");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    private static MusicianProfileSaveRequestDto update(String bio, String instagram) {
        return new MusicianProfileSaveRequestDto(null, bio, null, instagram, null, null,
                null, null, null, null, null);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableJpaAuditing
    @EnableJpaRepositories(basePackageClasses = {MusicianProfileRepository.class, UserRepository.class})
    @EntityScan(basePackages = "com.berkayb.soundconnect")
    @Import({MusicianProfileServiceImpl.class, UserEntityFinder.class})
    static class TestConfiguration {
        @Bean DataSource dataSource() {
            if (!postgres.isRunning()) throw new IllegalStateException("Disposable PostgreSQL must be running");
            return new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        }
        @Bean MusicianProfileMapper mapper() {
            return Mappers.getMapper(MusicianProfileMapper.class);
        }
    }
}

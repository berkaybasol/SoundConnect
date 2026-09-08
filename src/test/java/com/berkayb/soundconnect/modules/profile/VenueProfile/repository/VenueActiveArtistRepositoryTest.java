package com.berkayb.soundconnect.modules.profile.VenueProfile.repository;

import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.entity.ArtistVenueConnectionRequest;
import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.enums.RequestByType;
import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.enums.RequestStatus;
import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.location.entity.District;
import com.berkayb.soundconnect.modules.location.entity.Neighborhood;
import com.berkayb.soundconnect.modules.media.entity.MediaAsset;
import com.berkayb.soundconnect.modules.media.enums.*;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity.Band;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.entity.MusicianProfile;
import com.berkayb.soundconnect.modules.profile.VenueProfile.entity.VenueProfile;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.AuthProvider;
import com.berkayb.soundconnect.modules.venue.entity.Venue;
import com.berkayb.soundconnect.modules.venue.enums.VenueStatus;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Uses an explicitly constructed disposable datasource; it cannot point at local application data. */
@DataJpaTest(properties = {
        "spring.config.location=classpath:/application-test.yml", "spring.config.import=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect"
})
@ActiveProfiles("test")
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = VenueActiveArtistRepositoryTest.RepositoryConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class VenueActiveArtistRepositoryTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine")
            .withDatabaseName("venue_artist_directory_test")
            .withUsername("directory_test").withPassword("directory_test").withReuse(false);

    @Autowired DataSource dataSource;
    @Autowired EntityManager em;
    @Autowired VenueActiveArtistRepository repository;
    Venue venue;
    City city;
    District district;
    Neighborhood neighborhood;

    @BeforeEach
    void seedVenueAfterCheckingDatasource() throws SQLException {
        assertThat(POSTGRES.isRunning()).isTrue();
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getURL()).isEqualTo(POSTGRES.getJdbcUrl());
            assertThat(connection.getCatalog()).isEqualTo(POSTGRES.getDatabaseName());
        }
        city = persist(City.builder().name("Ankara").build());
        district = persist(District.builder().name("Çankaya").city(city).build());
        neighborhood = persist(Neighborhood.builder().name("Çayyolu").district(district).build());
        venue = newVenue("soundconnectankara");
    }

    @Test
    void onlyActiveAcceptedMusiciansOfSelectedVenueAppearRegardlessOfRequestDirection() {
        var first = musician("First", "first", venue, RequestStatus.ACCEPTED, true, RequestByType.ARTIST);
        var second = musician("Second", "second", venue, RequestStatus.ACCEPTED, true, RequestByType.VENUE);
        musician("Pending", "pending", venue, RequestStatus.PENDING, true, RequestByType.ARTIST);
        musician("Rejected", "rejected", venue, RequestStatus.REJECTED, true, RequestByType.ARTIST);
        musician("Disconnected", "disconnected", venue, RequestStatus.ACCEPTED, false, RequestByType.ARTIST);
        musician("Foreign", "foreign", newVenue("other"), RequestStatus.ACCEPTED, true, RequestByType.ARTIST);
        band("Not a musician", venue, RequestStatus.ACCEPTED, true);
        // Legacy duplicate accepted requests must not duplicate artists or inflate totals.
        persist(ArtistVenueConnectionRequest.builder().venue(venue).musicianProfile(first)
                .status(RequestStatus.ACCEPTED).requestByType(RequestByType.VENUE).build());
        em.flush();

        var result = repository.findMusicians(venue.getId(), "", PageRequest.of(0, 1));
        assertThat(result.getTotalElements()).isEqualTo(2);
        assertThat(result.getContent()).extracting(VenueActiveArtistRow::id).containsExactly(first.getId());
        assertThat(repository.findMusicians(venue.getId(), "", PageRequest.of(1, 1)).getContent())
                .extracting(VenueActiveArtistRow::id).containsExactly(second.getId());
        assertThat(repository.findMusicians(venue.getId(), "", PageRequest.of(2, 1)).getContent()).isEmpty();
    }

    @Test
    void onlyActiveAcceptedBandsOfSelectedVenueAppear() {
        var accepted = band("Şahbaz", venue, RequestStatus.ACCEPTED, true);
        band("Pending", venue, RequestStatus.PENDING, true);
        band("Rejected", venue, RequestStatus.REJECTED, true);
        band("Disconnected", venue, RequestStatus.ACCEPTED, false);
        band("Foreign", newVenue("other"), RequestStatus.ACCEPTED, true);
        musician("Not a band", "solo", venue, RequestStatus.ACCEPTED, true, RequestByType.VENUE);
        em.flush();
        var result = repository.findBands(venue.getId(), "", PageRequest.of(0, 20));
        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent()).extracting(VenueActiveArtistRow::id).containsExactly(accepted.getId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"şah", "SAH", "ŞAH", "sah"})
    void turkishSearchMatchesStageNamesUsernamesAndBands(String query) {
        var stage = musician("Şahne", "different", venue, RequestStatus.ACCEPTED, true, RequestByType.ARTIST);
        var username = musician("Other", "bugrasahin", venue, RequestStatus.ACCEPTED, true, RequestByType.VENUE);
        var group = band("Şahbaz", venue, RequestStatus.ACCEPTED, true);
        em.flush();
        assertThat(repository.findMusicians(venue.getId(), query, PageRequest.of(0, 20)).getContent())
                .extracting(VenueActiveArtistRow::id).containsExactlyInAnyOrder(stage.getId(), username.getId());
        assertThat(repository.findBands(venue.getId(), query, PageRequest.of(0, 20)).getContent())
                .extracting(VenueActiveArtistRow::id).containsExactly(group.getId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"%", "_", "'", "\\"})
    void searchTreatsSqlWildcardsAsLiteralText(String query) {
        musician("Ordinary", "ordinary", venue, RequestStatus.ACCEPTED, true, RequestByType.ARTIST);
        band("Ordinary band", venue, RequestStatus.ACCEPTED, true);
        em.flush();
        assertThat(repository.findMusicians(venue.getId(), query, PageRequest.of(0, 20))).isEmpty();
        assertThat(repository.findBands(venue.getId(), query, PageRequest.of(0, 20))).isEmpty();
    }

    @Test
    void stableTieBreakingAndBlankStageNameFallbackAreAppliedInDatabase() {
        var first = musician("Same", "one", venue, RequestStatus.ACCEPTED, true, RequestByType.ARTIST);
        var second = musician("Same", "two", venue, RequestStatus.ACCEPTED, true, RequestByType.VENUE);
        var fallback = musician("   ", "username", venue, RequestStatus.ACCEPTED, true, RequestByType.ARTIST);
        em.flush();
        var page0 = repository.findMusicians(venue.getId(), "", PageRequest.of(0, 1));
        var page1 = repository.findMusicians(venue.getId(), "", PageRequest.of(1, 1));
        assertThat(List.of(page0.getContent().getFirst().id(), page1.getContent().getFirst().id()))
                .containsExactlyInAnyOrder(first.getId(), second.getId()).doesNotHaveDuplicates();
        assertThat(repository.findMusicians(venue.getId(), "", PageRequest.of(0, 1)).getContent())
                .isEqualTo(page0.getContent());
        assertThat(repository.findMusicians(venue.getId(), "username", PageRequest.of(0, 20)).getContent())
                .singleElement().satisfies(row -> {
                    assertThat(row.id()).isEqualTo(fallback.getId());
                    assertThat(row.name()).isEqualTo("username");
                });
    }

    @Test
    void onlyPublicReadyImageUrlsAreExposedWithoutDroppingArtists() {
        for (int i = 0; i < 6; i++) {
            var image = persist(MediaAsset.builder().kind(i == 4 ? MediaKind.VIDEO : MediaKind.IMAGE)
                    .status(i == 2 ? MediaStatus.PROCESSING : MediaStatus.READY)
                    .visibility(i == 1 ? MediaVisibility.PRIVATE : MediaVisibility.PUBLIC)
                    .ownerType(MediaOwnerType.BAND).ownerId(UUID.randomUUID())
                    .mimeType(i == 4 ? "video/mp4" : "image/png").size(100L)
                    .thumbnailUrl(i == 3 || i == 5 ? " " : "https://cdn.test/thumb-" + i)
                    .playbackUrl(i == 5 ? null : "https://cdn.test/full-" + i).build());
            var artist = musician("Artist " + i, "user" + i, venue, RequestStatus.ACCEPTED, true, RequestByType.ARTIST);
            artist.setProfilePictureMediaId(image.getId());
            var group = band("Band " + i, venue, RequestStatus.ACCEPTED, true);
            group.setProfilePictureMediaId(image.getId());
        }
        em.flush();
        var musicians = repository.findMusicians(venue.getId(), "", PageRequest.of(0, 20)).getContent();
        var bands = repository.findBands(venue.getId(), "", PageRequest.of(0, 20)).getContent();
        for (var rows : List.of(musicians, bands)) {
            assertThat(rows).hasSize(6);
            assertThat(rows.get(0).profilePictureUrl()).isEqualTo("https://cdn.test/thumb-0");
            assertThat(rows.get(1).profilePictureUrl()).isNull();
            assertThat(rows.get(2).profilePictureUrl()).isNull();
            assertThat(rows.get(3).profilePictureUrl()).isEqualTo("https://cdn.test/full-3");
            assertThat(rows.get(4).profilePictureUrl()).isNull();
            assertThat(rows.get(5).profilePictureUrl()).isNull();
        }
    }

    @Test
    void venueExistenceRequiresPublicProfileButNotAnyConnections() {
        em.flush();
        assertThat(repository.existsVenueProfile(venue.getId())).isTrue();
        assertThat(repository.existsVenueProfile(UUID.randomUUID())).isFalse();
        assertThat(repository.findMusicians(venue.getId(), "", PageRequest.of(0, 20))).isEmpty();
        assertThat(repository.findBands(venue.getId(), "", PageRequest.of(0, 20))).isEmpty();
    }

    private Venue newVenue(String name) {
        var result = persist(Venue.builder().name(name).address("Address").city(city).district(district)
                .neighborhood(neighborhood).status(VenueStatus.APPROVED).build());
        persist(VenueProfile.builder().venue(result).build());
        return result;
    }

    private MusicianProfile musician(String stageName, String username, Venue target,
                                     RequestStatus status, boolean active, RequestByType direction) {
        var user = persist(User.builder().username(username).email(UUID.randomUUID() + "@test.invalid")
                .password("not-a-real-password").provider(AuthProvider.LOCAL).emailVerified(true).build());
        var musician = persist(MusicianProfile.builder().user(user).stageName(stageName).build());
        if (active) musician.getActiveVenues().add(target);
        persist(ArtistVenueConnectionRequest.builder().musicianProfile(musician).venue(target)
                .status(status).requestByType(direction).message("private request note").build());
        return musician;
    }

    private Band band(String name, Venue target, RequestStatus status, boolean active) {
        var band = persist(Band.builder().name(name).build());
        if (active) target.getActiveBands().add(band);
        persist(ArtistVenueConnectionRequest.builder().band(band).venue(target)
                .status(status).requestByType(RequestByType.BAND).message("private request note").build());
        return band;
    }

    private <T> T persist(T entity) {
        em.persist(entity);
        return entity;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableJpaAuditing
    @EnableJpaRepositories(basePackageClasses = VenueActiveArtistRepository.class)
    @EntityScan(basePackages = "com.berkayb.soundconnect")
    static class RepositoryConfiguration {
        @Bean
        DataSource dataSource() {
            if (!POSTGRES.isRunning()) throw new IllegalStateException("Disposable PostgreSQL must be running");
            return new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        }
    }
}

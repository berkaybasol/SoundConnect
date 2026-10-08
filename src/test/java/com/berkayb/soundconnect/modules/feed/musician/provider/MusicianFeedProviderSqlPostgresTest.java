package com.berkayb.soundconnect.modules.feed.musician.provider;

import com.berkayb.soundconnect.modules.event.support.EventShareUrlBuilder;
import com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedItemType;
import com.berkayb.soundconnect.modules.feed.musician.candidate.*;
import com.berkayb.soundconnect.modules.feed.musician.core.BackstageFeedAudience;
import com.berkayb.soundconnect.modules.feed.musician.feedback.MusicianFeedFeedbackSnapshot;
import com.berkayb.soundconnect.modules.feed.musician.personalization.MusicianFeedPersonalizationSnapshot;
import com.berkayb.soundconnect.modules.overthinking.service.OverthinkingPostService;
import com.berkayb.soundconnect.modules.overthinking.dto.response.OverthinkingPostResponseDto;
import com.berkayb.soundconnect.modules.overthinking.enums.OverthinkingVisibilityType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyList;

/**
 * Compiles every feed-native query against the real Hibernate production schema
 * on PostgreSQL. Empty fixtures are intentional: PostgreSQL still resolves every
 * relation, column, cast, lateral join and parameter before returning no rows.
 */
@DataJpaTest(properties = {
        "spring.config.location=classpath:/application-test.yml",
        "spring.config.import=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
        "spring.jpa.properties.hibernate.hbm2ddl.halt_on_error=true"
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MusicianFeedProviderSqlPostgresTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine")
            .withDatabaseName("musician_feed_provider_sql")
            .withUsername("soundconnect").withPassword("soundconnect");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired DataSource dataSource;

    @Test
    void everyProviderQueryMatchesTheGeneratedPostgresSchema() {
        NamedParameterJdbcTemplate jdbc = new NamedParameterJdbcTemplate(dataSource);
        EventShareUrlBuilder shareUrls = new EventShareUrlBuilder("https://soundconnect.test");
        OverthinkingPostService overthinkingPosts = mock(OverthinkingPostService.class);
        ObjectMapper objectMapper = new ObjectMapper();
        List<MusicianFeedCandidateProvider> providers = List.of(
                new MusicianFeedTrackCandidateProvider(jdbc),
                new MusicianFeedProfileMediaCandidateProvider(jdbc),
                new MusicianFeedCollabCandidateProvider(jdbc),
                new MusicianFeedEventCandidateProvider(jdbc, shareUrls),
                new MusicianFeedListenerEventShareCandidateProvider(jdbc, shareUrls),
                new MusicianFeedOverthinkingShareCandidateProvider(jdbc, overthinkingPosts),
                new MusicianFeedTableGroupShareCandidateProvider(jdbc, objectMapper),
                new MusicianFeedProfileDiscoveryCandidateProvider(jdbc),
                new MusicianFeedFollowActivityCandidateProvider(jdbc),
                new MusicianFeedMediaActivityCandidateProvider(
                        jdbc, shareUrls, objectMapper, overthinkingPosts));
        Instant anchor = Instant.parse("2026-09-11T12:00:00Z");
        var request = new MusicianFeedCandidateRequest(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), anchor, anchor, 20, EnumSet.allOf(MusicianFeedItemType.class),
                MusicianFeedPersonalizationSnapshot.empty(), MusicianFeedFeedbackSnapshot.empty());

        for (MusicianFeedCandidateProvider provider : providers) {
            assertThatCode(() -> assertThat(provider.findCandidates(request)).isEmpty())
                    .as(provider.providerId())
                    .doesNotThrowAnyException();
            assertThatCode(() -> assertThat(provider.findCandidates(request.withAudience(BackstageFeedAudience.VENUE))).isEmpty())
                    .as(provider.providerId() + " venue audience")
                    .doesNotThrowAnyException();
            assertThatCode(() -> assertThat(provider.findCandidates(request.withAudience(BackstageFeedAudience.LISTENER))).isEmpty())
                    .as(provider.providerId() + " listener audience")
                    .doesNotThrowAnyException();
            assertThatCode(() -> assertThat(provider.findCandidates(request.withAudience(BackstageFeedAudience.STUDIO))).isEmpty())
                    .as(provider.providerId() + " studio audience")
                    .doesNotThrowAnyException();
        }
    }

    @ParameterizedTest
    @EnumSource(value = BackstageFeedAudience.class, names = {"VENUE", "STUDIO"})
    void businessDiscoveryMatchesPrivateOpportunityPreferencesWithoutExposingThemAndKeepsUnknownCityFallback(
            BackstageFeedAudience audience) {
        JdbcTemplate sql = new JdbcTemplate(dataSource);
        NamedParameterJdbcTemplate jdbc = new NamedParameterJdbcTemplate(dataSource);
        Instant anchor = Instant.parse("2026-09-11T12:00:00Z");
        Timestamp created = Timestamp.from(anchor.minusSeconds(600));
        UUID viewer = UUID.randomUUID(), city = UUID.randomUUID(), otherCity = UUID.randomUUID();
        insertAccount(sql, viewer, "venue-discovery-viewer", "ACTIVE", true);
        sql.update("insert into tbl_city(id,created_at,updated_at,name) values (?,?,?,?),(?,?,?,?)",
                city, created, created, "Venue city", otherCity, created, created, "Public other city");
        UUID preferredUser = UUID.randomUUID(), preferredProfile = UUID.randomUUID();
        UUID localUser = UUID.randomUUID(), localProfile = UUID.randomUUID();
        UUID remoteUser = UUID.randomUUID(), remoteProfile = UUID.randomUUID();
        UUID unknownUser = UUID.randomUUID(), unknownProfile = UUID.randomUUID();
        UUID listener = UUID.randomUUID(), listenerProfile = UUID.randomUUID(), band = UUID.randomUUID();
        insertMusician(sql, preferredUser, preferredProfile, "artist-preferred");
        insertMusician(sql, localUser, localProfile, "artist-local");
        insertMusician(sql, remoteUser, remoteProfile, "artist-remote");
        insertMusician(sql, unknownUser, unknownProfile, "artist-unknown");
        insertListener(sql, listener, listenerProfile, "venue-social-listener");
        sql.update("update tbl_user set city_id=? where id=?", otherCity, preferredUser);
        sql.update("update tbl_user set city_id=? where id in (?,?)", city, localUser, remoteUser);
        sql.update("insert into tbl_musician_feed_preferences(musician_profile_id,opportunity_city_id,version) values (?,?,0),(?,?,0)",
                preferredProfile, city, remoteProfile, otherCity);
        sql.update("insert into tbl_band(id,created_at,updated_at,name) values (?,?,?,?)", band, created, created, "Venue discovery band");
        insertBandMember(sql, band, remoteUser, "FOUNDER");
        insertBandMember(sql, band, preferredUser, "MEMBER");
        record Publisher(UUID profile, String type) { }
        for (var publisher : List.of(new Publisher(preferredProfile, "MUSICIAN"), new Publisher(localProfile, "MUSICIAN"),
                new Publisher(remoteProfile, "MUSICIAN"), new Publisher(unknownProfile, "MUSICIAN"),
                new Publisher(listenerProfile, "LISTENER"), new Publisher(band, "BAND"))) {
            UUID media = UUID.randomUUID();
            insertPublicAudio(sql, media, publisher.profile(), anchor.minusSeconds(300));
            String ownerType = publisher.type().equals("BAND") ? "BAND" : publisher.type() + "_PROFILE";
            sql.update("update tbl_media_asset set owner_type=? where id=?", ownerType, media);
            sql.update("insert into tbl_tracks(id,created_at,updated_at,media_asset_id,owner_type,owner_id,title) values (?,?,?,?,?,?,?)",
                    UUID.randomUUID(), created, created, media, ownerType, publisher.profile(), "Artist publication");
            sql.update("insert into tbl_profile_media(id,created_at,updated_at,profile_type,profile_id,media_asset_id,role,order_index) values (?,?,?,?,?,?,'GALLERY',0)",
                    UUID.randomUUID(), created, created, publisher.type(), publisher.profile(), media);
        }
        var request = new MusicianFeedCandidateRequest(viewer, UUID.randomUUID(), UUID.randomUUID(), anchor, anchor, 20,
                Set.of(MusicianFeedItemType.PROFILE, MusicianFeedItemType.TRACK, MusicianFeedItemType.PROFILE_MEDIA),
                new MusicianFeedPersonalizationSnapshot(city, Set.of(), null), MusicianFeedFeedbackSnapshot.empty())
                .withAudience(audience);
        List<MusicianFeedCandidateProvider> providers = List.of(new MusicianFeedProfileDiscoveryCandidateProvider(jdbc),
                new MusicianFeedTrackCandidateProvider(jdbc), new MusicianFeedProfileMediaCandidateProvider(jdbc));
        for (var provider : providers) {
            var candidates = provider.findCandidates(request);
            assertThat(candidates).as(provider.providerId()).hasSize(5);
            assertThat(candidates).extracting(value -> value.author().profileId())
                    .containsExactlyInAnyOrder(preferredProfile, localProfile, remoteProfile, unknownProfile, band);
            assertThat(candidates).filteredOn(value -> Set.of(preferredProfile, localProfile, band).contains(value.author().profileId()))
                    .allSatisfy(value -> assertThat(value.reason().code()).isEqualTo(com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedReasonCode.CITY_MATCH));
            assertThat(candidates).filteredOn(value -> Set.of(remoteProfile, unknownProfile).contains(value.author().profileId()))
                    .allSatisfy(value -> assertThat(value.reason().code()).isEqualTo(com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedReasonCode.DISCOVERY));
        }
        var preferredCard = new MusicianFeedProfileDiscoveryCandidateProvider(jdbc).findCandidates(request).stream()
                .filter(value -> value.author().profileId().equals(preferredProfile)).findFirst().orElseThrow();
        assertThat(((com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedPayloads.Profile) preferredCard.payload()).location())
                .isEqualTo("Public other city");
        insertFollow(sql, viewer, listener, anchor.minusSeconds(120));
        assertThat(new MusicianFeedTrackCandidateProvider(jdbc).findCandidates(request))
                .filteredOn(value -> value.author().profileId().equals(listenerProfile)).singleElement()
                .satisfies(value -> assertThat(value.lane()).isEqualTo(MusicianFeedLane.FOLLOWING));
        assertThat(new MusicianFeedProfileMediaCandidateProvider(jdbc).findCandidates(request))
                .filteredOn(value -> value.author().profileId().equals(listenerProfile)).singleElement()
                .satisfies(value -> assertThat(value.lane()).isEqualTo(MusicianFeedLane.FOLLOWING));
        assertThat(new MusicianFeedProfileDiscoveryCandidateProvider(jdbc).findCandidates(request))
                .allSatisfy(value -> assertThat(value.author().profileType()).isIn("MUSICIAN", "BAND"));
        sql.update("update tbl_user set email_verified=false where id=?", preferredUser);
        assertThat(new MusicianFeedProfileDiscoveryCandidateProvider(jdbc).findCandidates(request))
                .filteredOn(value -> value.author().profileId().equals(band)).singleElement()
                .satisfies(value -> assertThat(value.reason().code()).isEqualTo(com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedReasonCode.DISCOVERY));
    }

    @ParameterizedTest
    @EnumSource(value = BackstageFeedAudience.class, names = {"VENUE", "STUDIO"})
    void businessMediaCandidateLimitPrefersPerformancesOverNewerPhotosWithoutChangingMusicianOrder(
            BackstageFeedAudience audience) {
        JdbcTemplate sql = new JdbcTemplate(dataSource);
        NamedParameterJdbcTemplate jdbc = new NamedParameterJdbcTemplate(dataSource);
        Instant anchor = Instant.parse("2026-09-11T12:00:00Z");
        UUID viewer = UUID.randomUUID(), artist = UUID.randomUUID(), profile = UUID.randomUUID();
        insertAccount(sql, viewer, "venue-performance-viewer", "ACTIVE", true);
        insertMusician(sql, artist, profile, "venue-performance-artist");
        for (int index = 0; index < 3; index++) {
            UUID media = UUID.randomUUID();
            Instant createdAt = anchor.minusSeconds(300 - index * 60L);
            insertPublicAudio(sql, media, profile, createdAt);
            if (index > 0) sql.update("update tbl_media_asset set kind='IMAGE',mime_type='image/jpeg' where id=?", media);
            sql.update("insert into tbl_profile_media(id,created_at,updated_at,profile_type,profile_id,media_asset_id,role,order_index) values (?,?,?,'MUSICIAN',?,?,'GALLERY',?)",
                    UUID.randomUUID(), Timestamp.from(createdAt), Timestamp.from(createdAt), profile, media, index);
        }
        var request = new MusicianFeedCandidateRequest(viewer, UUID.randomUUID(), UUID.randomUUID(), anchor, anchor, 1,
                Set.of(MusicianFeedItemType.PROFILE_MEDIA), MusicianFeedPersonalizationSnapshot.empty(), MusicianFeedFeedbackSnapshot.empty());
        var provider = new MusicianFeedProfileMediaCandidateProvider(jdbc);
        assertThat(provider.findCandidates(request.withAudience(audience))).singleElement()
                .satisfies(value -> assertThat(((com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedPayloads.ProfileMedia) value.payload()).kind()).isEqualTo("AUDIO"));
        assertThat(provider.findCandidates(request)).singleElement()
                .satisfies(value -> assertThat(((com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedPayloads.ProfileMedia) value.payload()).kind()).isEqualTo("IMAGE"));
    }

    @Test
    void studioCollabDiscoveryFiltersWrongWantedTypesBeforeLimitAndBackfillsLocalThenNationalDemand() {
        JdbcTemplate sql = new JdbcTemplate(dataSource);
        Instant anchor = Instant.parse("2026-09-11T12:00:00Z");
        UUID viewer = UUID.randomUUID(), publisher = UUID.randomUUID(), publisherProfile = UUID.randomUUID();
        UUID city = UUID.randomUUID(), otherCity = UUID.randomUUID(), instrument = UUID.randomUUID();
        insertAccount(sql, viewer, "studio-demand-viewer", "ACTIVE", true);
        insertMusician(sql, publisher, publisherProfile, "studio-demand-publisher");
        Timestamp created = Timestamp.from(anchor.minusSeconds(900));
        sql.update("insert into tbl_city(id,created_at,updated_at,name) values (?,?,?,?),(?,?,?,?)",
                city, created, created, "Studio demand city", otherCity, created, created, "Other demand city");
        sql.update("insert into tbl_instrument(id,created_at,updated_at,name) values (?,?,?,?)",
                instrument, created, created, "Studio demand guitar");
        UUID actor = insertCollabActor(sql, publisherProfile, anchor);
        for (int index = 0; index < 24; index++) {
            insertCollabListing(sql, publisher, actor, city, "MUSICIAN",
                    index % 2 == 0 ? "SOUND_ENGINEER" : null, instrument, anchor.minusSeconds(30 + index));
        }
        UUID local = insertCollabListing(sql, publisher, actor, city, "STUDIO", null, null, anchor.minusSeconds(500));
        UUID national = insertCollabListing(sql, publisher, actor, otherCity, "STUDIO", null, null, anchor.minusSeconds(400));
        var request = new MusicianFeedCandidateRequest(viewer, UUID.randomUUID(), UUID.randomUUID(), anchor, anchor, 2,
                Set.of(MusicianFeedItemType.COLLAB), new MusicianFeedPersonalizationSnapshot(city, Set.of(instrument), null),
                MusicianFeedFeedbackSnapshot.empty()).withAudience(BackstageFeedAudience.STUDIO);
        var provider = new MusicianFeedCollabCandidateProvider(new NamedParameterJdbcTemplate(dataSource));
        var candidates = provider.findCandidates(request);

        assertThat(candidates).extracting(value -> value.target().id()).containsExactly(local, national);
        assertThat(candidates).allSatisfy(value -> {
            assertThat(value.lane()).isEqualTo(MusicianFeedLane.RELEVANT_OPPORTUNITY);
            assertThat(((com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedPayloads.Collab)
                    value.payload()).listing().wantedType())
                    .isEqualTo(com.berkayb.soundconnect.modules.collab.enums.CollabWantedType.STUDIO);
        });
        assertThat(candidates.getFirst().reason().code())
                .isEqualTo(com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedReasonCode.CITY_MATCH);
        assertThat(candidates.getLast().reason().code())
                .isEqualTo(com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedReasonCode.DISCOVERY);
        assertThat(candidates.getFirst().relevanceScore()).isGreaterThan(candidates.getLast().relevanceScore());

        var noInstrumentRequest = new MusicianFeedCandidateRequest(viewer, request.musicianProfileId(), request.feedSessionId(),
                anchor, anchor, 2, request.supportedTypes(), new MusicianFeedPersonalizationSnapshot(city, Set.of(), null),
                MusicianFeedFeedbackSnapshot.empty()).withAudience(BackstageFeedAudience.STUDIO);
        assertThat(provider.findCandidates(noInstrumentRequest)).isEqualTo(candidates);
        var noCityRequest = new MusicianFeedCandidateRequest(viewer, request.musicianProfileId(), request.feedSessionId(),
                anchor, anchor, 2, request.supportedTypes(), new MusicianFeedPersonalizationSnapshot(null, Set.of(instrument), null),
                MusicianFeedFeedbackSnapshot.empty()).withAudience(BackstageFeedAudience.STUDIO);
        assertThat(provider.findCandidates(noCityRequest)).extracting(value -> value.target().id())
                .containsExactly(national, local);
    }

    @Test
    void studioKeepsFollowedMusicianAndSoundEngineerListingsWithoutTreatingThemAsStudioDemand() {
        JdbcTemplate sql = new JdbcTemplate(dataSource);
        Instant anchor = Instant.parse("2026-09-11T12:00:00Z");
        UUID viewer = UUID.randomUUID(), publisher = UUID.randomUUID(), publisherProfile = UUID.randomUUID();
        UUID city = UUID.randomUUID(), instrument = UUID.randomUUID();
        insertAccount(sql, viewer, "studio-social-viewer", "ACTIVE", true);
        insertMusician(sql, publisher, publisherProfile, "studio-social-publisher");
        Timestamp created = Timestamp.from(anchor.minusSeconds(900));
        sql.update("insert into tbl_city(id,created_at,updated_at,name) values (?,?,?,?)",
                city, created, created, "Studio social city");
        sql.update("insert into tbl_instrument(id,created_at,updated_at,name) values (?,?,?,?)",
                instrument, created, created, "Studio social instrument");
        UUID actor = insertCollabActor(sql, publisherProfile, anchor);
        UUID musician = insertCollabListing(sql, publisher, actor, city, "MUSICIAN", null, instrument, anchor.minusSeconds(100));
        UUID engineer = insertCollabListing(sql, publisher, actor, city, "MUSICIAN", "SOUND_ENGINEER", null, anchor.minusSeconds(50));
        var request = new MusicianFeedCandidateRequest(viewer, UUID.randomUUID(), UUID.randomUUID(), anchor, anchor, 4,
                Set.of(MusicianFeedItemType.COLLAB), new MusicianFeedPersonalizationSnapshot(city, Set.of(instrument), null),
                MusicianFeedFeedbackSnapshot.empty()).withAudience(BackstageFeedAudience.STUDIO);
        var provider = new MusicianFeedCollabCandidateProvider(new NamedParameterJdbcTemplate(dataSource));
        assertThat(provider.findCandidates(request)).isEmpty();

        insertFollow(sql, viewer, publisher, anchor.minusSeconds(600));
        assertThat(provider.findCandidates(request)).extracting(value -> value.target().id())
                .containsExactly(engineer, musician);
        assertThat(provider.findCandidates(request)).allSatisfy(value -> {
            assertThat(value.lane()).isEqualTo(MusicianFeedLane.FOLLOWING);
            assertThat(value.relevanceScore()).isZero();
            assertThat(value.reason().code())
                    .isEqualTo(com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedReasonCode.FOLLOWING_PUBLICATION);
        });
        assertThat(provider.findCandidates(request.withAudience(BackstageFeedAudience.MUSICIAN)))
                .hasSize(2).allSatisfy(value -> assertThat(value.relevanceScore()).isPositive());
    }

    @Test
    void studioLocalEventsRemainLowRateDiscoveryWhileFollowingRetainsTheExistingEventPolicy() {
        JdbcTemplate sql = new JdbcTemplate(dataSource);
        Instant anchor = Instant.parse("2026-09-11T12:00:00Z");
        UUID viewer = UUID.randomUUID(), venueOwner = UUID.randomUUID();
        insertAccount(sql, viewer, "studio-event-viewer", "ACTIVE", true);
        insertAccount(sql, venueOwner, "studio-event-venue", "ACTIVE", true);
        UUID event = insertFutureVenueEvent(sql, venueOwner, anchor);
        UUID city = sql.queryForObject("select venue.city_id from tbl_event event join tbl_venues venue on venue.id=event.venue_id where event.id=?",
                UUID.class, event);
        var request = new MusicianFeedCandidateRequest(viewer, UUID.randomUUID(), UUID.randomUUID(), anchor, anchor, 20,
                Set.of(MusicianFeedItemType.EVENT), new MusicianFeedPersonalizationSnapshot(city, Set.of(), null),
                MusicianFeedFeedbackSnapshot.empty());
        var provider = new MusicianFeedEventCandidateProvider(new NamedParameterJdbcTemplate(dataSource),
                new EventShareUrlBuilder("https://soundconnect.test"));
        var musician = provider.findCandidates(request);
        var studio = provider.findCandidates(request.withAudience(BackstageFeedAudience.STUDIO));
        assertThat(musician).singleElement().satisfies(value -> {
            assertThat(value.target().id()).isEqualTo(event);
            assertThat(value.lane()).isEqualTo(MusicianFeedLane.RELEVANT_OPPORTUNITY);
        });
        assertThat(studio).singleElement().satisfies(value -> {
            assertThat(value.target().id()).isEqualTo(event);
            assertThat(value.lane()).isEqualTo(MusicianFeedLane.GENERAL_DISCOVERY);
            assertThat(value.relevanceScore()).isPositive().isLessThan(musician.getFirst().relevanceScore());
            assertThat(value.reason().code())
                    .isEqualTo(com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedReasonCode.CITY_MATCH);
            assertThat(value.payload()).isEqualTo(musician.getFirst().payload());
        });

        insertFollow(sql, viewer, venueOwner, anchor.minusSeconds(600));
        assertThat(provider.findCandidates(request.withAudience(BackstageFeedAudience.STUDIO)))
                .isEqualTo(provider.findCandidates(request))
                .singleElement().satisfies(value -> assertThat(value.lane()).isEqualTo(MusicianFeedLane.FOLLOWING));
    }

    @Test
    void activityProvidersRequireTheNestedRendererCapabilityBeforeQuerying() {
        NamedParameterJdbcTemplate jdbc = new NamedParameterJdbcTemplate(dataSource);
        EventShareUrlBuilder shareUrls = new EventShareUrlBuilder("https://soundconnect.test");
        OverthinkingPostService overthinkingPosts = mock(OverthinkingPostService.class);
        Instant anchor = Instant.parse("2026-09-11T12:00:00Z");
        var followOnly = request(anchor, Set.of(MusicianFeedItemType.ACTIVITY_FOLLOW));
        var likeOnly = request(anchor, Set.of(MusicianFeedItemType.ACTIVITY_LIKE));

        assertThat(new MusicianFeedFollowActivityCandidateProvider(jdbc).findCandidates(followOnly)).isEmpty();
        assertThat(new MusicianFeedMediaActivityCandidateProvider(
                jdbc, shareUrls, new ObjectMapper(), overthinkingPosts).findCandidates(likeOnly)).isEmpty();
    }

    @Test
    void nestedActivityTargetProjectionQueriesMatchThePostgresSchema() throws Exception {
        NamedParameterJdbcTemplate jdbc = new NamedParameterJdbcTemplate(dataSource);
        Instant anchor = Instant.parse("2026-09-11T12:00:00Z");
        var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                .addValue("viewerId", UUID.randomUUID())
                .addValue("targetIds", List.of(UUID.randomUUID()))
                .addValue("anchor", Timestamp.from(anchor))
                .addValue("readAt", Timestamp.from(anchor))
                .addValue("today", java.time.LocalDate.of(2026, 9, 11))
                .addValue("nowSeconds", 12 * 3600)
                .addValue("storageMidnight", java.time.LocalTime.MIDNIGHT);
        for (String fieldName : List.of("EVENT_TARGET_SQL", "EVENT_POST_TARGET_SQL", "TABLE_TARGET_SQL")) {
            var field = MusicianFeedMediaActivityCandidateProvider.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            String sql = (String) field.get(null);
            assertThatCode(() -> jdbc.queryForList(sql, parameters))
                    .as(fieldName)
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void mediaLikesAggregateActorsAndHydrateTheAdvertisedNativeRenderer() {
        JdbcTemplate sql = new JdbcTemplate(dataSource);
        UUID viewer = UUID.randomUUID();
        UUID actorOne = UUID.randomUUID();
        UUID actorTwo = UUID.randomUUID();
        UUID publisher = UUID.randomUUID();
        UUID viewerProfile = UUID.randomUUID();
        UUID actorOneProfile = UUID.randomUUID();
        UUID actorTwoProfile = UUID.randomUUID();
        UUID publisherProfile = UUID.randomUUID();
        insertMusician(sql, viewer, viewerProfile, "viewer");
        insertMusician(sql, actorOne, actorOneProfile, "actor-one");
        insertMusician(sql, actorTwo, actorTwoProfile, "actor-two");
        insertMusician(sql, publisher, publisherProfile, "publisher");

        Instant anchor = Instant.parse("2026-09-11T12:00:00Z");
        insertFollow(sql, viewer, actorOne, anchor.minusSeconds(600));
        insertFollow(sql, viewer, actorTwo, anchor.minusSeconds(500));
        UUID mediaId = UUID.randomUUID();
        UUID trackId = UUID.randomUUID();
        insertPublicAudio(sql, mediaId, publisherProfile, anchor.minusSeconds(400));
        sql.update("""
                insert into tbl_tracks(id,created_at,updated_at,media_asset_id,owner_type,owner_id,title)
                values (?,?,?,?,?,?,?)
                """, trackId, Timestamp.from(anchor.minusSeconds(400)), Timestamp.from(anchor.minusSeconds(400)),
                mediaId, "MUSICIAN_PROFILE", publisherProfile, "Session take");
        insertLike(sql, actorOne, mediaId, "MEDIA", anchor.minusSeconds(100));
        insertLike(sql, actorTwo, mediaId, "MEDIA", anchor.minusSeconds(80));
        // More than the provider limit of newer, non-public targets must be
        // rejected before LIMIT or the older eligible card is permanently starved.
        for (int index = 0; index < 4; index++) {
            UUID privateMediaId = UUID.randomUUID();
            insertPublicAudio(sql, privateMediaId, publisherProfile, anchor.minusSeconds(60 - index));
            sql.update("update tbl_media_asset set visibility='PRIVATE' where id=?", privateMediaId);
            sql.update("""
                    insert into tbl_tracks(id,created_at,updated_at,media_asset_id,owner_type,owner_id,title)
                    values (?,?,?,?,?,?,?)
                    """, UUID.randomUUID(), Timestamp.from(anchor.minusSeconds(60 - index)),
                    Timestamp.from(anchor.minusSeconds(60 - index)), privateMediaId,
                    "MUSICIAN_PROFILE", publisherProfile, "Private take " + index);
            insertLike(sql, actorOne, privateMediaId, "MEDIA", anchor.minusSeconds(10 + index));
        }
        UUID ownMediaId = UUID.randomUUID();
        insertPublicAudio(sql, ownMediaId, viewerProfile, anchor.minusSeconds(50));
        sql.update("""
                insert into tbl_tracks(id,created_at,updated_at,media_asset_id,owner_type,owner_id,title)
                values (?,?,?,?,?,?,?)
                """, UUID.randomUUID(), Timestamp.from(anchor.minusSeconds(50)),
                Timestamp.from(anchor.minusSeconds(50)), ownMediaId,
                "MUSICIAN_PROFILE", viewerProfile, "Viewer-owned take");
        insertLike(sql, actorOne, ownMediaId, "MEDIA", anchor.minusSeconds(5));

        NamedParameterJdbcTemplate jdbc = new NamedParameterJdbcTemplate(dataSource);
        var provider = new MusicianFeedMediaActivityCandidateProvider(jdbc,
                new EventShareUrlBuilder("https://soundconnect.test"), new ObjectMapper(),
                mock(OverthinkingPostService.class));
        var request = new MusicianFeedCandidateRequest(viewer, viewerProfile, UUID.randomUUID(),
                anchor, anchor, 2, Set.of(MusicianFeedItemType.ACTIVITY_LIKE, MusicianFeedItemType.TRACK),
                MusicianFeedPersonalizationSnapshot.empty(), MusicianFeedFeedbackSnapshot.empty());

        List<MusicianFeedCandidate> candidates = provider.findCandidates(request);

        assertThat(candidates).hasSize(1);
        MusicianFeedCandidate candidate = candidates.getFirst();
        assertThat(candidate.itemId()).isEqualTo("ACTIVITY_LIKE:MEDIA:" + mediaId);
        assertThat(candidate.author().username()).isEqualTo("actor-two");
        assertThat(candidate.author().displayName()).isEqualTo("actor-two");
        assertThat(candidate.reason().actors()).extracting(actor -> actor.userId())
                .containsExactly(actorTwo, actorOne);
        assertThat(candidate.reason().actors()).extracting(actor -> actor.displayName())
                .containsExactly("actor-two", "actor-one");
        assertThat(candidate.reason().secondaryActorCount()).isZero();
        assertThat(((com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedPayloads.Activity)
                candidate.payload()).targetItemType()).isEqualTo(MusicianFeedItemType.TRACK);
    }

    @Test
    void olderCommentKeepsAnActionReservationWhenNewerLikesExceedTheProviderCap() {
        JdbcTemplate sql = new JdbcTemplate(dataSource);
        UUID viewer = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        UUID publisher = UUID.randomUUID();
        UUID viewerProfile = UUID.randomUUID();
        UUID actorProfile = UUID.randomUUID();
        UUID publisherProfile = UUID.randomUUID();
        insertMusician(sql, viewer, viewerProfile, "reservation-viewer");
        insertMusician(sql, actor, actorProfile, "reservation-actor");
        insertMusician(sql, publisher, publisherProfile, "reservation-publisher");

        Instant anchor = Instant.parse("2026-09-11T12:00:00Z");
        insertFollow(sql, viewer, actor, anchor.minusSeconds(4_000));
        List<UUID> mediaIds = new ArrayList<>();
        for (int index = 0; index < 161; index++) {
            UUID mediaId = UUID.randomUUID();
            mediaIds.add(mediaId);
            Instant publishedAt = anchor.minusSeconds(3_000L + index);
            insertPublicAudio(sql, mediaId, publisherProfile, publishedAt);
            sql.update("""
                    insert into tbl_tracks(id,created_at,updated_at,media_asset_id,owner_type,owner_id,title)
                    values (?,?,?,?,?,?,?)
                    """, UUID.randomUUID(), Timestamp.from(publishedAt), Timestamp.from(publishedAt), mediaId,
                    "MUSICIAN_PROFILE", publisherProfile, "Reservation track " + index);
            insertLike(sql, actor, mediaId, "MEDIA", anchor.minusSeconds(100L + index));
        }
        UUID commentId = UUID.randomUUID();
        insertComment(sql, commentId, actor, mediaIds.getFirst(), "MEDIA",
                anchor.minusSeconds(1_000));

        var provider = mediaActivityProvider();
        int providerCap = 160;
        var request = new MusicianFeedCandidateRequest(viewer, viewerProfile, UUID.randomUUID(),
                anchor, anchor, providerCap, Set.of(MusicianFeedItemType.ACTIVITY_LIKE,
                MusicianFeedItemType.ACTIVITY_COMMENT, MusicianFeedItemType.TRACK),
                MusicianFeedPersonalizationSnapshot.empty(), MusicianFeedFeedbackSnapshot.empty());

        List<MusicianFeedCandidate> candidates = provider.findCandidates(request);

        assertThat(candidates).hasSize(providerCap);
        assertThat(candidates).filteredOn(value -> value.type() == MusicianFeedItemType.ACTIVITY_COMMENT)
                .singleElement().extracting(MusicianFeedCandidate::itemId)
                .isEqualTo("ACTIVITY_COMMENT:" + commentId);
        assertThat(candidates).filteredOn(value -> value.type() == MusicianFeedItemType.ACTIVITY_LIKE)
                .hasSize(providerCap - 1);
    }

    @Test
    void singleSlotIsWorkConservingAndUsesTheActivityIdAsItsStableTieBreaker() {
        JdbcTemplate sql = new JdbcTemplate(dataSource);
        UUID viewer = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        UUID publisher = UUID.randomUUID();
        UUID viewerProfile = UUID.randomUUID();
        UUID actorProfile = UUID.randomUUID();
        UUID publisherProfile = UUID.randomUUID();
        insertMusician(sql, viewer, viewerProfile, "single-slot-viewer");
        insertMusician(sql, actor, actorProfile, "single-slot-actor");
        insertMusician(sql, publisher, publisherProfile, "single-slot-publisher");

        Instant anchor = Instant.parse("2026-09-11T12:00:00Z");
        Instant occurredAt = anchor.minusSeconds(100);
        insertFollow(sql, viewer, actor, anchor.minusSeconds(500));
        UUID mediaId = UUID.randomUUID();
        insertPublicAudio(sql, mediaId, publisherProfile, anchor.minusSeconds(400));
        sql.update("""
                insert into tbl_tracks(id,created_at,updated_at,media_asset_id,owner_type,owner_id,title)
                values (?,?,?,?,?,?,?)
                """, UUID.randomUUID(), Timestamp.from(anchor.minusSeconds(400)),
                Timestamp.from(anchor.minusSeconds(400)), mediaId, "MUSICIAN_PROFILE", publisherProfile,
                "Single-slot track");
        UUID likeId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        insertLike(sql, likeId, actor, mediaId, "MEDIA", occurredAt);

        var provider = mediaActivityProvider();
        var request = new MusicianFeedCandidateRequest(viewer, viewerProfile, UUID.randomUUID(),
                anchor, anchor, 1, Set.of(MusicianFeedItemType.ACTIVITY_LIKE,
                MusicianFeedItemType.ACTIVITY_COMMENT, MusicianFeedItemType.TRACK),
                MusicianFeedPersonalizationSnapshot.empty(), MusicianFeedFeedbackSnapshot.empty());

        assertThat(provider.findCandidates(request)).singleElement()
                .extracting(MusicianFeedCandidate::type)
                .isEqualTo(MusicianFeedItemType.ACTIVITY_LIKE);

        UUID commentId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        insertComment(sql, commentId, actor, mediaId, "MEDIA", occurredAt);

        assertThat(provider.findCandidates(request)).singleElement().satisfies(candidate -> {
            assertThat(candidate.type()).isEqualTo(MusicianFeedItemType.ACTIVITY_LIKE);
            assertThat(candidate.itemId()).isEqualTo("ACTIVITY_LIKE:MEDIA:" + mediaId);
        });
    }

    @Test
    void overthinkingShareActivityUsesCanonicalPrivateSourceAndTheCombinedModuleLane() {
        JdbcTemplate sql = new JdbcTemplate(dataSource);
        UUID viewer = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        UUID publisher = UUID.randomUUID();
        UUID viewerProfile = UUID.randomUUID();
        UUID actorProfile = UUID.randomUUID();
        UUID listenerProfile = UUID.randomUUID();
        insertMusician(sql, viewer, viewerProfile, "privacy-viewer");
        insertMusician(sql, actor, actorProfile, "privacy-actor");
        insertListener(sql, publisher, listenerProfile, "raw-source-owner");

        Instant anchor = Instant.parse("2026-09-11T12:00:00Z");
        insertFollow(sql, viewer, actor, anchor.minusSeconds(600));
        insertFollow(sql, viewer, publisher, anchor.minusSeconds(550));
        UUID sourceId = UUID.randomUUID();
        UUID shareId = UUID.randomUUID();
        sql.update("""
                insert into tbl_overthinking_post(id,created_at,updated_at,author_id,title,content,visibility_type)
                values (?,?,?,?,?,?,?)
                """, sourceId, Timestamp.from(anchor.minusSeconds(500)), Timestamp.from(anchor.minusSeconds(500)),
                publisher, "Private thought", "Private body", "ANONYMOUS");
        sql.update("""
                insert into tbl_overthinking_profile_share(id,owner_user_id,listener_profile_id,source_post_id,note,published_at)
                values (?,?,?,?,?,?)
                """, shareId, publisher, listenerProfile, sourceId, "Shared note",
                Timestamp.from(anchor.minusSeconds(400)));
        insertLike(sql, actor, shareId, "OVERTHINKING_PROFILE_SHARE", anchor.minusSeconds(100));

        OverthinkingPostService posts = mock(OverthinkingPostService.class);
        var safeSource = new OverthinkingPostResponseDto(sourceId, null, "raw-source-owner",
                "https://private.test/avatar.jpg", true, false, OverthinkingVisibilityType.ANONYMOUS,
                "Private thought", "Private body", null, null, null, null, null,
                null, null, null, null, 0, 0, false);
        when(posts.getByIdsForViewer(eq(viewer), anyList())).thenReturn(Map.of(sourceId, safeSource));
        var provider = new MusicianFeedMediaActivityCandidateProvider(
                new NamedParameterJdbcTemplate(dataSource),
                new EventShareUrlBuilder("https://soundconnect.test"), new ObjectMapper(), posts);
        var request = new MusicianFeedCandidateRequest(viewer, viewerProfile, UUID.randomUUID(),
                anchor, anchor, 20, Set.of(MusicianFeedItemType.ACTIVITY_LIKE,
                MusicianFeedItemType.OVERTHINKING_PROFILE_SHARE),
                MusicianFeedPersonalizationSnapshot.empty(), MusicianFeedFeedbackSnapshot.empty());

        List<MusicianFeedCandidate> candidates = provider.findCandidates(request);

        assertThat(candidates).hasSize(1);
        assertThat(candidates.getFirst().lane()).isEqualTo(MusicianFeedLane.MODULE_SHARE);
        var activity = (com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedPayloads.Activity)
                candidates.getFirst().payload();
        var share = (com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedPayloads.ProfileShare)
                activity.targetPayload();
        var source = (OverthinkingPostResponseDto) share.source();
        assertThat(source.authorId()).isNull();
        assertThat(source.authorUsername()).isEqualTo("Anonymous");
        assertThat(source.authorAvatarUrl()).isNull();

        var publicationProvider = new MusicianFeedOverthinkingShareCandidateProvider(
                new NamedParameterJdbcTemplate(dataSource), posts);
        var publicationRequest = new MusicianFeedCandidateRequest(viewer, viewerProfile, UUID.randomUUID(),
                anchor, anchor, 20, Set.of(MusicianFeedItemType.OVERTHINKING_PROFILE_SHARE),
                MusicianFeedPersonalizationSnapshot.empty(), MusicianFeedFeedbackSnapshot.empty());
        List<MusicianFeedCandidate> publications = publicationProvider.findCandidates(publicationRequest);
        assertThat(publications).hasSize(1);
        var publicationPayload = (com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedPayloads.ProfileShare)
                publications.getFirst().payload();
        assertThat(((OverthinkingPostResponseDto) publicationPayload.source()).authorUsername())
                .isEqualTo("Anonymous");

        sql.update("update \"tbl_listener-profile\" set visibility_mode='GHOST' where id=?", listenerProfile);
        assertThat(publicationProvider.findCandidates(publicationRequest)).isEmpty();
    }

    @Test
    void mixedUserAndBandFollowStoriesRemainContractValidAndRespectBandFences() {
        JdbcTemplate sql = new JdbcTemplate(dataSource);
        UUID viewer = UUID.randomUUID();
        UUID actorOne = UUID.randomUUID();
        UUID actorTwo = UUID.randomUUID();
        UUID publicMember = UUID.randomUUID();
        UUID followedUser = UUID.randomUUID();
        UUID viewerProfile = UUID.randomUUID();
        UUID actorOneProfile = UUID.randomUUID();
        UUID actorTwoProfile = UUID.randomUUID();
        UUID memberProfile = UUID.randomUUID();
        UUID followedUserProfile = UUID.randomUUID();
        insertMusician(sql, viewer, viewerProfile, "band-viewer");
        insertMusician(sql, actorOne, actorOneProfile, "band-actor-one");
        insertMusician(sql, actorTwo, actorTwoProfile, "band-actor-two");
        insertMusician(sql, publicMember, memberProfile, "band-member");
        insertMusician(sql, followedUser, followedUserProfile, "followed-user");
        Instant anchor = Instant.parse("2026-09-11T12:00:00Z");
        insertFollow(sql, viewer, actorOne, anchor.minusSeconds(800));
        insertFollow(sql, viewer, actorTwo, anchor.minusSeconds(700));
        insertFollow(sql, actorOne, followedUser, anchor.minusSeconds(50));

        UUID bandId = UUID.randomUUID();
        sql.update("insert into tbl_band(id,created_at,updated_at,name,description) values (?,?,?,?,?)",
                bandId, Timestamp.from(anchor.minusSeconds(900)), Timestamp.from(anchor.minusSeconds(900)),
                "The Stable IDs", "Band preview");
        insertBandMember(sql, bandId, publicMember, "FOUNDER");
        insertBandFollow(sql, actorOne, bandId, anchor.minusSeconds(200));
        insertBandFollow(sql, actorTwo, bandId, anchor.minusSeconds(100));

        var provider = new MusicianFeedFollowActivityCandidateProvider(
                new NamedParameterJdbcTemplate(dataSource));
        var request = new MusicianFeedCandidateRequest(viewer, viewerProfile, UUID.randomUUID(),
                anchor, anchor, 20, Set.of(MusicianFeedItemType.ACTIVITY_FOLLOW, MusicianFeedItemType.PROFILE),
                MusicianFeedPersonalizationSnapshot.empty(), MusicianFeedFeedbackSnapshot.empty());
        List<MusicianFeedCandidate> candidates = provider.findCandidates(request);
        assertThat(candidates).hasSize(2);
        assertThat(MusicianFeedCandidateContract.validateBatch(provider.providerId(),
                provider.supportedTypes(), request, candidates, request.limit(), false)).hasSize(2);
        assertThat(candidates).extracting(MusicianFeedCandidate::itemId)
                .containsExactly("ACTIVITY_FOLLOW:MUSICIAN:" + followedUserProfile,
                        "ACTIVITY_FOLLOW:BAND:" + bandId);
        MusicianFeedCandidate musicianCandidate = candidates.getFirst();
        assertThat(musicianCandidate.author().displayName()).isEqualTo("band-actor-one");
        var musicianActivity = (com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedPayloads.Activity)
                musicianCandidate.payload();
        var musicianTarget = (com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedPayloads.Profile)
                musicianActivity.targetPayload();
        assertThat(musicianTarget.username()).isEqualTo("followed-user");
        assertThat(musicianTarget.displayName()).isEqualTo("followed-user");
        MusicianFeedCandidate bandCandidate = candidates.stream()
                .filter(value -> value.itemId().equals("ACTIVITY_FOLLOW:BAND:" + bandId))
                .findFirst().orElseThrow();
        assertThat(bandCandidate.reason().actors()).extracting(actor -> actor.userId())
                .containsExactly(actorTwo, actorOne);
        var payload = (com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedPayloads.Activity)
                bandCandidate.payload();
        var target = (com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedPayloads.Profile)
                payload.targetPayload();
        assertThat(target.profileType()).isEqualTo("BAND");
        assertThat(target.userId()).isEqualTo(publicMember);
        assertThat(target.username()).isEqualTo("band-member");

        insertMute(sql, viewer, "MUSICIAN", actorTwoProfile, anchor.minusSeconds(50));
        List<MusicianFeedCandidate> afterMute = provider.findCandidates(request);
        assertThat(afterMute).hasSize(2);
        MusicianFeedCandidate bandAfterMute = afterMute.stream()
                .filter(value -> value.itemId().equals("ACTIVITY_FOLLOW:BAND:" + bandId))
                .findFirst().orElseThrow();
        assertThat(bandAfterMute.reason().actors()).extracting(actor -> actor.userId())
                .containsExactly(actorOne);

        insertBandMember(sql, bandId, viewer, "MEMBER");
        assertThat(provider.findCandidates(request)).extracting(MusicianFeedCandidate::itemId)
                .containsExactly("ACTIVITY_FOLLOW:MUSICIAN:" + followedUserProfile);
    }

    @Test
    void futureEventsWithoutMemberPublicationsRemainVisibleAndStillExcludeTheirOwners() {
        JdbcTemplate sql = new JdbcTemplate(dataSource);
        Instant anchor = Instant.parse("2026-09-11T12:00:00Z");
        UUID viewer = UUID.randomUUID();
        UUID viewerProfile = UUID.randomUUID();
        UUID performer = UUID.randomUUID();
        UUID performerProfile = UUID.randomUUID();
        UUID venueOwner = UUID.randomUUID();
        insertMusician(sql, viewer, viewerProfile, "nullable-event-viewer");
        insertMusician(sql, performer, performerProfile, "nullable-event-performer");
        insertAccount(sql, venueOwner, "nullable-event-venue", "ACTIVE", true);
        insertFollow(sql, viewer, venueOwner, anchor.minusSeconds(600));
        UUID event = insertFutureVenueEvent(sql, venueOwner, anchor);
        var provider = new MusicianFeedEventCandidateProvider(new NamedParameterJdbcTemplate(dataSource),
                new EventShareUrlBuilder("https://soundconnect.test"));
        var request = new MusicianFeedCandidateRequest(viewer, viewerProfile, UUID.randomUUID(),
                anchor, anchor, 20, Set.of(MusicianFeedItemType.EVENT),
                MusicianFeedPersonalizationSnapshot.empty(), MusicianFeedFeedbackSnapshot.empty());

        assertThat(provider.findCandidates(request)).singleElement().satisfies(candidate -> {
            assertThat(candidate.target().id()).isEqualTo(event);
            assertThat(candidate.author().profileType()).isEqualTo("VENUE");
            assertThat(candidate.lane()).isEqualTo(MusicianFeedLane.FOLLOWING);
            assertThat(candidate.ownedByViewer()).isFalse();
        });
        sql.update("update tbl_event set musician_profile_id=?,profile_calendar_approved=true,"
                        + "performer_approval_status='APPROVED' where id=?",
                performerProfile, event);
        assertThat(provider.findCandidates(request)).singleElement()
                .satisfies(candidate -> assertThat(candidate.author().profileId()).isEqualTo(performerProfile));
        sql.update("update tbl_user set status='INACTIVE' where id=?", performer);
        assertThat(provider.findCandidates(request)).as("selected musician is no longer public").isEmpty();
        sql.update("update tbl_user set status='ACTIVE' where id=?", performer);

        sql.update("update tbl_event set musician_profile_id=? where id=?", viewerProfile, event);
        assertThat(provider.findCandidates(request)).as("viewer is the published musician").isEmpty();
        sql.update("update tbl_event set musician_profile_id=null,profile_calendar_approved=false,"
                + "performer_approval_status='NOT_REQUIRED',"
                + "organizer_user_id=? where id=?", viewer, event);
        assertThat(provider.findCandidates(request)).as("viewer organized the event").isEmpty();
        sql.update("update tbl_event set organizer_user_id=? where id=?", venueOwner, event);
        sql.update("insert into event_member_publications(event_id,musician_profile_id,visible,version)"
                + " values (?,?,true,0)", event, viewerProfile);
        assertThat(provider.findCandidates(request)).as("viewer explicitly published as a member").isEmpty();
    }

    @Test
    void publishedBandEventActivitiesHydrateWithoutASoloMusicianAndKeepOwnBandExcluded() {
        JdbcTemplate sql = new JdbcTemplate(dataSource);
        Instant anchor = Instant.parse("2026-09-11T12:00:00Z");
        UUID viewer = UUID.randomUUID();
        UUID viewerProfile = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        UUID actorProfile = UUID.randomUUID();
        UUID bandMember = UUID.randomUUID();
        UUID bandMemberProfile = UUID.randomUUID();
        UUID venueOwner = UUID.randomUUID();
        insertMusician(sql, viewer, viewerProfile, "band-event-viewer");
        insertMusician(sql, actor, actorProfile, "band-event-actor");
        insertMusician(sql, bandMember, bandMemberProfile, "band-event-member");
        insertAccount(sql, venueOwner, "band-event-venue", "ACTIVE", true);
        insertFollow(sql, viewer, actor, anchor.minusSeconds(600));
        UUID event = insertFutureVenueEvent(sql, venueOwner, anchor);
        UUID band = UUID.randomUUID();
        sql.update("insert into tbl_band(id,created_at,updated_at,name,description) values (?,?,?,?,?)",
                band, Timestamp.from(anchor.minusSeconds(1000)), Timestamp.from(anchor.minusSeconds(1000)),
                "Published event band", "Band event fixture");
        insertBandMember(sql, band, bandMember, "FOUNDER");
        sql.update("update tbl_event set band_id=?,profile_calendar_approved=true,"
                + "performer_approval_status='APPROVED' where id=?", band, event);
        insertLike(sql, actor, event, "EVENT", anchor.minusSeconds(100));
        insertComment(sql, UUID.randomUUID(), actor, event, "EVENT", anchor.minusSeconds(50));
        var request = new MusicianFeedCandidateRequest(viewer, viewerProfile, UUID.randomUUID(),
                anchor, anchor, 20, Set.of(MusicianFeedItemType.EVENT,
                MusicianFeedItemType.ACTIVITY_LIKE, MusicianFeedItemType.ACTIVITY_COMMENT),
                MusicianFeedPersonalizationSnapshot.empty(), MusicianFeedFeedbackSnapshot.empty());
        var provider = mediaActivityProvider();
        var publications = new MusicianFeedEventCandidateProvider(new NamedParameterJdbcTemplate(dataSource),
                new EventShareUrlBuilder("https://soundconnect.test"));

        var activities = provider.findCandidates(request);
        assertThat(activities).extracting(MusicianFeedCandidate::type)
                .containsExactlyInAnyOrder(MusicianFeedItemType.ACTIVITY_LIKE, MusicianFeedItemType.ACTIVITY_COMMENT);
        assertThat(activities).allSatisfy(candidate -> {
            assertThat(candidate.target().id()).isEqualTo(event);
            var activity = (com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedPayloads.Activity)
                    candidate.payload();
            var payload = (com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedPayloads.Event)
                    activity.targetPayload();
            assertThat(payload.event().bandId()).isEqualTo(band);
            assertThat(payload.event().musicianProfileId()).isNull();
        });
        assertThat(publications.findCandidates(request)).singleElement()
                .satisfies(candidate -> assertThat(candidate.author().profileId()).isEqualTo(band));
        sql.update("update tbl_user set status='INACTIVE' where id=?", bandMember);
        assertThat(publications.findCandidates(request)).as("band has no public member").isEmpty();
        assertThat(provider.findCandidates(request)).as("band activity has no public member").isEmpty();
        sql.update("update tbl_user set status='ACTIVE' where id=?", bandMember);
        insertBandMember(sql, band, viewer, "MEMBER");
        assertThat(provider.findCandidates(request)).as("viewer belongs to the performing band").isEmpty();
        assertThat(publications.findCandidates(request)).as("viewer belongs to the performing band").isEmpty();
    }

    @Test
    void listenerEventShareCannotResurrectAnInvalidSelectedMusician() {
        JdbcTemplate sql = new JdbcTemplate(dataSource);
        UUID viewer = UUID.randomUUID();
        UUID viewerProfile = UUID.randomUUID();
        UUID publisher = UUID.randomUUID();
        UUID listenerProfile = UUID.randomUUID();
        UUID venueOwner = UUID.randomUUID();
        UUID performer = UUID.randomUUID();
        UUID performerProfile = UUID.randomUUID();
        insertMusician(sql, viewer, viewerProfile, "event-viewer");
        insertListener(sql, publisher, listenerProfile, "event-publisher");
        insertAccount(sql, venueOwner, "event-venue-owner", "ACTIVE", true);
        insertMusician(sql, performer, performerProfile, "invalid-performer");
        sql.update("update tbl_user set status='INACTIVE' where id=?", performer);

        Instant anchor = Instant.parse("2026-09-11T12:00:00Z");
        insertFollow(sql, viewer, publisher, anchor.minusSeconds(600));
        UUID city = UUID.randomUUID();
        UUID district = UUID.randomUUID();
        UUID neighborhood = UUID.randomUUID();
        UUID venue = UUID.randomUUID();
        sql.update("insert into tbl_city(id,created_at,updated_at,name) values (?,?,?,?)",
                city, Timestamp.from(anchor.minusSeconds(1000)), Timestamp.from(anchor.minusSeconds(1000)),
                "Event City " + city);
        sql.update("insert into tbl_district(id,created_at,updated_at,name,city_id) values (?,?,?,?,?)",
                district, Timestamp.from(anchor.minusSeconds(1000)), Timestamp.from(anchor.minusSeconds(1000)),
                "Event District", city);
        sql.update("insert into tbl_neighborhood(id,created_at,updated_at,name,district_id) values (?,?,?,?,?)",
                neighborhood, Timestamp.from(anchor.minusSeconds(1000)), Timestamp.from(anchor.minusSeconds(1000)),
                "Event Neighborhood", district);
        sql.update("""
                insert into tbl_venues(id,created_at,updated_at,name,address,city_id,district_id,
                    neighborhood_id,status,owner_id)
                values (?,?,?,?,?,?,?,?,?,?)
                """, venue, Timestamp.from(anchor.minusSeconds(900)), Timestamp.from(anchor.minusSeconds(900)),
                "Event Venue", "Test address", city, district, neighborhood, "APPROVED", venueOwner);
        UUID event = UUID.randomUUID();
        sql.update("""
                insert into tbl_event(id,created_at,updated_at,title,event_date,start_time,venue_id,
                    event_origin,organizer_user_id,venue_approval_status,venue_calendar_approved,
                    musician_profile_id,performer_approval_status,profile_calendar_approved,
                    profile_publication_version)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, event, Timestamp.from(anchor.minusSeconds(800)), Timestamp.from(anchor.minusSeconds(800)),
                "Future event", java.time.LocalDate.of(2026, 9, 12), java.time.LocalTime.of(20, 0), venue,
                "VENUE", venueOwner, "APPROVED", true, performerProfile, "APPROVED", false, 0L);
        UUID post = UUID.randomUUID();
        sql.update("""
                insert into tbl_event_audience_intent(user_id,event_id,intent,published_on_profile,note,
                    version,updated_at,published_at,post_id)
                values (?,?,?,?,?,?,?,?,?)
                """, publisher, event, "GOING", true, "See you there", 1L,
                Timestamp.from(anchor.minusSeconds(200)), Timestamp.from(anchor.minusSeconds(200)), post);

        var provider = new MusicianFeedListenerEventShareCandidateProvider(
                new NamedParameterJdbcTemplate(dataSource),
                new EventShareUrlBuilder("https://soundconnect.test"));
        var request = new MusicianFeedCandidateRequest(viewer, viewerProfile, UUID.randomUUID(),
                anchor, anchor, 20, Set.of(MusicianFeedItemType.EVENT_PROFILE_SHARE),
                MusicianFeedPersonalizationSnapshot.empty(), MusicianFeedFeedbackSnapshot.empty());

        assertThat(provider.findCandidates(request)).isEmpty();
        sql.update("update tbl_user set status='ACTIVE' where id=?", performer);
        List<MusicianFeedCandidate> listenerShares = provider.findCandidates(request);
        assertThat(listenerShares).hasSize(1);
        assertThat(provider.findCandidates(request.withAudience(BackstageFeedAudience.LISTENER)))
                .extracting(MusicianFeedCandidate::itemId).containsExactly("EVENT_PROFILE_SHARE:" + post);
        sql.update("update tbl_event_audience_intent set published_on_profile=false where post_id=?", post);
        assertThat(provider.findCandidates(request.withAudience(BackstageFeedAudience.LISTENER))).isEmpty();
        sql.update("update tbl_event_audience_intent set published_on_profile=true where post_id=?", post);
        sql.update("update \"tbl_listener-profile\" set visibility_mode='GHOST' where id=?", listenerProfile);
        assertThat(provider.findCandidates(request.withAudience(BackstageFeedAudience.LISTENER))).isEmpty();
        sql.update("update \"tbl_listener-profile\" set visibility_mode='STANDARD' where id=?", listenerProfile);
        var listenerEvent = (com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedPayloads.Event)
                listenerShares.getFirst().payload();
        assertThat(listenerEvent.event().performerName()).isEqualTo("invalid-performer");

        insertLike(sql, publisher, event, "EVENT", anchor.minusSeconds(50));
        var activityRequest = new MusicianFeedCandidateRequest(viewer, viewerProfile, UUID.randomUUID(),
                anchor, anchor, 20, Set.of(MusicianFeedItemType.ACTIVITY_LIKE, MusicianFeedItemType.EVENT),
                MusicianFeedPersonalizationSnapshot.empty(), MusicianFeedFeedbackSnapshot.empty());
        List<MusicianFeedCandidate> activities = mediaActivityProvider().findCandidates(activityRequest);
        assertThat(activities).hasSize(1);
        var activity = (com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedPayloads.Activity)
                activities.getFirst().payload();
        var activityEvent = (com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedPayloads.Event)
                activity.targetPayload();
        assertThat(activityEvent.event().performerName()).isEqualTo("invalid-performer");
    }

    @Test
    void feedProviderQueriesNeverReadTheLegacyMusicianStageName() throws Exception {
        for (Class<?> provider : List.of(
                MusicianFeedTrackCandidateProvider.class,
                MusicianFeedProfileMediaCandidateProvider.class,
                MusicianFeedCollabCandidateProvider.class,
                MusicianFeedProfileDiscoveryCandidateProvider.class,
                MusicianFeedFollowActivityCandidateProvider.class,
                MusicianFeedMediaActivityCandidateProvider.class,
                MusicianFeedEventCandidateProvider.class,
                MusicianFeedListenerEventShareCandidateProvider.class)) {
            for (var field : provider.getDeclaredFields()) {
                if (field.getType() != String.class || !java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                field.setAccessible(true);
                assertThat(((String) field.get(null)).toLowerCase(Locale.ROOT))
                        .as(provider.getSimpleName() + "." + field.getName())
                        .doesNotContain("stage_name");
            }
        }
    }

    @Test
    void musicianCollabIdentityIgnoresThePersistedActorDisplaySnapshot() {
        assertThat(MusicianFeedCollabCandidateProvider.musicianDisplayName(null)).isEqualTo("Müzisyen");
        assertThat(MusicianFeedCollabCandidateProvider.musicianDisplayName("   ")).isEqualTo("Müzisyen");

        JdbcTemplate sql = new JdbcTemplate(dataSource);
        NamedParameterJdbcTemplate jdbc = new NamedParameterJdbcTemplate(dataSource);
        Instant anchor = Instant.parse("2026-09-11T12:00:00Z");
        UUID viewer = UUID.randomUUID();
        UUID viewerProfile = UUID.randomUUID();
        UUID publisher = UUID.randomUUID();
        UUID publisherProfile = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        UUID city = UUID.randomUUID();
        UUID listing = UUID.randomUUID();
        insertMusician(sql, viewer, viewerProfile, "collab-viewer");
        insertMusician(sql, publisher, publisherProfile, "collab-publisher");
        sql.update("insert into tbl_city(id,created_at,updated_at,name) values (?,?,?,?)",
                city, Timestamp.from(anchor), Timestamp.from(anchor), "Identity City");
        sql.update("""
                insert into tbl_collab_actor(id,created_at,updated_at,profile_type,source_profile_id,
                    display_name,rating_sum,review_count,completed_job_count,active,version)
                values (?,?,?,?,?,?,?,?,?,?,?)
                """, actor, Timestamp.from(anchor), Timestamp.from(anchor), "MUSICIAN", publisherProfile,
                "Legacy Stage collab-publisher", 0L, 0L, 0L, true, 0L);
        sql.update("""
                insert into tbl_collab(id,created_at,updated_at,owner_user_id,publisher_actor_id,
                    client_request_id,creation_payload_hash,cadence,wanted_type,title,description,city_id,
                    status,published_at,version)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, listing, Timestamp.from(anchor.minusSeconds(300)), Timestamp.from(anchor.minusSeconds(300)),
                publisher, actor, UUID.randomUUID(), "0".repeat(64), "REGULAR", "MUSICIAN",
                "Username identity", "The actor snapshot must not leak into the musician feed.", city,
                "OPEN", Timestamp.from(anchor.minusSeconds(200)), 0L);

        var request = new MusicianFeedCandidateRequest(viewer, viewerProfile, UUID.randomUUID(),
                anchor, anchor, 20, Set.of(MusicianFeedItemType.COLLAB),
                MusicianFeedPersonalizationSnapshot.empty(), MusicianFeedFeedbackSnapshot.empty());
        List<MusicianFeedCandidate> candidates =
                new MusicianFeedCollabCandidateProvider(jdbc).findCandidates(request);

        assertThat(candidates).hasSize(1);
        assertThat(candidates.getFirst().author().username()).isEqualTo("collab-publisher");
        assertThat(candidates.getFirst().author().displayName()).isEqualTo("collab-publisher");
        var payload = (com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedPayloads.Collab)
                candidates.getFirst().payload();
        assertThat(payload.listing().publisher().contactUsername()).isEqualTo("collab-publisher");
        assertThat(payload.listing().publisher().displayName()).isEqualTo("collab-publisher");
    }

    @Test
    void musicianPublicationAndDiscoveryIdentityAlwaysUsesUsername() {
        JdbcTemplate sql = new JdbcTemplate(dataSource);
        NamedParameterJdbcTemplate jdbc = new NamedParameterJdbcTemplate(dataSource);
        Instant anchor = Instant.parse("2026-09-11T12:00:00Z");
        UUID viewer = UUID.randomUUID();
        UUID viewerProfile = UUID.randomUUID();
        UUID publisher = UUID.randomUUID();
        UUID publisherProfile = UUID.randomUUID();
        UUID discoverable = UUID.randomUUID();
        UUID discoverableProfile = UUID.randomUUID();
        insertMusician(sql, viewer, viewerProfile, "identity-viewer");
        insertMusician(sql, publisher, publisherProfile, "publication-user");
        insertMusician(sql, discoverable, discoverableProfile, "discovery-user");
        insertFollow(sql, viewer, publisher, anchor.minusSeconds(500));

        UUID media = UUID.randomUUID();
        insertPublicAudio(sql, media, publisherProfile, anchor.minusSeconds(200));
        sql.update("""
                insert into tbl_tracks(id,created_at,updated_at,media_asset_id,owner_type,owner_id,title)
                values (?,?,?,?,?,?,?)
                """, UUID.randomUUID(), Timestamp.from(anchor.minusSeconds(190)),
                Timestamp.from(anchor.minusSeconds(190)), media, "MUSICIAN_PROFILE", publisherProfile,
                "Identity track");
        sql.update("""
                insert into tbl_profile_media(id,created_at,updated_at,profile_type,profile_id,
                    media_asset_id,role,order_index)
                values (?,?,?,?,?,?,?,?)
                """, UUID.randomUUID(), Timestamp.from(anchor.minusSeconds(180)),
                Timestamp.from(anchor.minusSeconds(180)), "MUSICIAN", publisherProfile, media, "GALLERY", 0);

        var publicationRequest = new MusicianFeedCandidateRequest(
                viewer, viewerProfile, UUID.randomUUID(), anchor, anchor, 20,
                Set.of(MusicianFeedItemType.TRACK, MusicianFeedItemType.PROFILE_MEDIA),
                MusicianFeedPersonalizationSnapshot.empty(), MusicianFeedFeedbackSnapshot.empty());
        List<MusicianFeedCandidate> tracks =
                new MusicianFeedTrackCandidateProvider(jdbc).findCandidates(publicationRequest);
        List<MusicianFeedCandidate> mediaItems =
                new MusicianFeedProfileMediaCandidateProvider(jdbc).findCandidates(publicationRequest);
        assertThat(tracks).hasSize(1);
        assertThat(tracks.getFirst().author().username()).isEqualTo("publication-user");
        assertThat(tracks.getFirst().author().displayName()).isEqualTo("publication-user");
        assertThat(mediaItems).hasSize(1);
        assertThat(mediaItems.getFirst().author().username()).isEqualTo("publication-user");
        assertThat(mediaItems.getFirst().author().displayName()).isEqualTo("publication-user");

        var discoveryRequest = new MusicianFeedCandidateRequest(
                viewer, viewerProfile, UUID.randomUUID(), anchor, anchor, 20,
                Set.of(MusicianFeedItemType.PROFILE),
                MusicianFeedPersonalizationSnapshot.empty(), MusicianFeedFeedbackSnapshot.empty());
        List<MusicianFeedCandidate> profiles =
                new MusicianFeedProfileDiscoveryCandidateProvider(jdbc).findCandidates(discoveryRequest);
        assertThat(profiles).hasSize(1);
        assertThat(profiles.getFirst().author().username()).isEqualTo("discovery-user");
        assertThat(profiles.getFirst().author().displayName()).isEqualTo("discovery-user");
        var profile = (com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedPayloads.Profile)
                profiles.getFirst().payload();
        assertThat(profile.displayName()).isEqualTo("discovery-user");
    }

    @Test
    void standardListenerPublicationsAndDiscoveryRequireAPureListenerRoleFootprint() {
        JdbcTemplate sql = new JdbcTemplate(dataSource);
        NamedParameterJdbcTemplate jdbc = new NamedParameterJdbcTemplate(dataSource);
        UUID viewer = UUID.randomUUID();
        UUID viewerProfile = UUID.randomUUID();
        UUID listener = UUID.randomUUID();
        UUID listenerProfile = UUID.randomUUID();
        insertMusician(sql, viewer, viewerProfile, "pure-listener-viewer");
        insertListener(sql, listener, listenerProfile, "pure-listener-author");
        Instant anchor = Instant.parse("2026-09-11T12:00:00Z");
        UUID media = UUID.randomUUID();
        insertPublicAudio(sql, media, listenerProfile, anchor.minusSeconds(120));
        sql.update("update tbl_media_asset set owner_type='LISTENER_PROFILE' where id=?", media);
        sql.update("""
                insert into tbl_tracks(id,created_at,updated_at,media_asset_id,owner_type,owner_id,title)
                values (?,?,?,?,?,?,?)
                """, UUID.randomUUID(), Timestamp.from(anchor.minusSeconds(100)),
                Timestamp.from(anchor.minusSeconds(100)), media, "LISTENER_PROFILE", listenerProfile,
                "Listener track");
        sql.update("""
                insert into tbl_profile_media(id,created_at,updated_at,profile_type,profile_id,
                    media_asset_id,role,order_index)
                values (?,?,?,?,?,?,?,?)
                """, UUID.randomUUID(), Timestamp.from(anchor.minusSeconds(90)),
                Timestamp.from(anchor.minusSeconds(90)), "LISTENER", listenerProfile, media, "GALLERY", 0);

        var base = new MusicianFeedCandidateRequest(viewer, viewerProfile, UUID.randomUUID(),
                anchor, anchor, 20, Set.of(MusicianFeedItemType.TRACK,
                MusicianFeedItemType.PROFILE_MEDIA, MusicianFeedItemType.PROFILE),
                MusicianFeedPersonalizationSnapshot.empty(), MusicianFeedFeedbackSnapshot.empty());
        var track = new MusicianFeedTrackCandidateProvider(jdbc);
        var profileMedia = new MusicianFeedProfileMediaCandidateProvider(jdbc);
        var profiles = new MusicianFeedProfileDiscoveryCandidateProvider(jdbc);
        assertThat(track.findCandidates(base)).hasSize(1);
        assertThat(profileMedia.findCandidates(base)).hasSize(1);
        assertThat(profiles.findCandidates(base)).hasSize(1);

        for (String overlappingRole : List.of("ROLE_ADMIN", "ROLE_OWNER", "ROLE_MUSICIAN",
                "ROLE_VENUE", "ROLE_STUDIO", "ROLE_ORGANIZER", "ROLE_PRODUCER")) {
            UUID roleId = UUID.randomUUID();
            sql.update("insert into tbl_role(id,created_at,updated_at,name) values (?,?,?,?)",
                    roleId, Timestamp.from(anchor), Timestamp.from(anchor), overlappingRole);
            sql.update("insert into user_roles(user_id,role_id) values (?,?)", listener, roleId);

            assertThat(track.findCandidates(base)).as(overlappingRole + " track").isEmpty();
            assertThat(profileMedia.findCandidates(base)).as(overlappingRole + " media").isEmpty();
            assertThat(profiles.findCandidates(base)).as(overlappingRole + " profile").isEmpty();

            sql.update("delete from user_roles where user_id=? and role_id=?", listener, roleId);
            sql.update("delete from tbl_role where id=?", roleId);
        }
        assertThat(track.findCandidates(base)).hasSize(1);
        assertThat(profileMedia.findCandidates(base)).hasSize(1);
        assertThat(profiles.findCandidates(base)).hasSize(1);

        sql.update("insert into tbl_producer_profile(id,user_id) values (?,?)",
                UUID.randomUUID(), listener);
        assertThat(track.findCandidates(base)).as("producer profile overlap track").isEmpty();
        assertThat(profileMedia.findCandidates(base)).as("producer profile overlap media").isEmpty();
        assertThat(profiles.findCandidates(base)).as("producer profile overlap profile").isEmpty();
    }

    @Test
    void trackPublisherBranchesKeepAllOwnerIdentitiesAndOwnBandExclusion() {
        JdbcTemplate sql = new JdbcTemplate(dataSource);
        Instant anchor = Instant.parse("2026-09-11T12:00:00Z");
        Timestamp created = Timestamp.from(anchor.minusSeconds(600));
        UUID viewer = UUID.randomUUID(), viewerProfile = UUID.randomUUID();
        UUID musician = UUID.randomUUID(), musicianProfile = UUID.randomUUID();
        UUID listener = UUID.randomUUID(), listenerProfile = UUID.randomUUID();
        UUID studio = UUID.randomUUID(), studioProfile = UUID.randomUUID();
        UUID venueOwner = UUID.randomUUID(), venueProfile = UUID.randomUUID();
        UUID bandActor = UUID.randomUUID(), band = UUID.randomUUID();
        insertMusician(sql, viewer, viewerProfile, "branch-viewer");
        insertMusician(sql, musician, musicianProfile, "branch-musician");
        insertListener(sql, listener, listenerProfile, "branch-listener");
        insertAccount(sql, studio, "branch-studio", "ACTIVE", true);
        insertAccount(sql, venueOwner, "branch-venue", "ACTIVE", true);
        insertAccount(sql, bandActor, "branch-band-actor", "ACTIVE", true);
        sql.update("insert into tbl_studio_profile(id,created_at,updated_at,user_id,name,time_zone,version)"
                + " values (?,?,?,?,?,'Europe/Istanbul',0)", studioProfile, created, created, studio, "Studio display");
        UUID event = insertFutureVenueEvent(sql, venueOwner, anchor);
        UUID venue = sql.queryForObject("select venue_id from tbl_event where id=?", UUID.class, event);
        sql.update("insert into tbl_venue_profile(id,created_at,updated_at,venue_id) values (?,?,?,?)",
                venueProfile, created, created, venue);
        sql.update("insert into tbl_band(id,created_at,updated_at,name) values (?,?,?,?)", band, created, created, "Band display");
        insertBandMember(sql, band, bandActor, "FOUNDER");
        record Publisher(String ownerType, UUID owner, String profileType, UUID profile, UUID user) { }
        List<Publisher> publishers = List.of(
                new Publisher("MUSICIAN_PROFILE", musicianProfile, "MUSICIAN", musicianProfile, musician),
                new Publisher("LISTENER_PROFILE", listenerProfile, "LISTENER", listenerProfile, listener),
                new Publisher("STUDIO_PROFILE", studioProfile, "STUDIO", studioProfile, studio),
                new Publisher("VENUE_PROFILE", venueProfile, "VENUE", venue, venueOwner),
                new Publisher("BAND", band, "BAND", band, bandActor));
        for (Publisher publisher : publishers) {
            UUID media = UUID.randomUUID();
            insertPublicAudio(sql, media, publisher.owner(), anchor.minusSeconds(600));
            sql.update("update tbl_media_asset set owner_type=? where id=?", publisher.ownerType(), media);
            sql.update("insert into tbl_tracks(id,created_at,updated_at,media_asset_id,owner_type,owner_id,title)"
                    + " values (?,?,?,?,?,?,?)", UUID.randomUUID(), created, created, media, publisher.ownerType(),
                    publisher.owner(), publisher.profileType() + " branch track");
        }
        var request = new MusicianFeedCandidateRequest(viewer, viewerProfile, UUID.randomUUID(), anchor, anchor, 40,
                Set.of(MusicianFeedItemType.TRACK), MusicianFeedPersonalizationSnapshot.empty(), MusicianFeedFeedbackSnapshot.empty());
        var provider = new MusicianFeedTrackCandidateProvider(new NamedParameterJdbcTemplate(dataSource));
        var candidates = provider.findCandidates(request);
        assertThat(candidates.size()).isEqualTo(5);
        for (Publisher publisher : publishers) {
            assertThat(candidates).filteredOn(candidate -> candidate.author().profileType().equals(publisher.profileType()))
                    .singleElement().satisfies(candidate -> {
                        assertThat(candidate.author().profileId()).isEqualTo(publisher.profile());
                        assertThat(candidate.author().userId()).isEqualTo(publisher.user());
                        assertThat(candidate.author().followedByViewer()).isFalse();
                    });
        }
        for (Publisher publisher : publishers) {
            if (publisher.ownerType().equals("BAND")) {
                insertBandFollow(sql, viewer, publisher.owner(), anchor.minusSeconds(500));
            } else {
                insertFollow(sql, viewer, publisher.user(), anchor.minusSeconds(500));
            }
        }
        var followed = provider.findCandidates(request);
        assertThat(followed).extracting(candidate -> candidate.author().profileType())
                .containsExactlyInAnyOrder("MUSICIAN", "LISTENER", "STUDIO", "VENUE", "BAND");
        assertThat(followed).allSatisfy(candidate -> {
            assertThat(candidate.author().followedByViewer()).isTrue();
            assertThat(candidate.lane()).isEqualTo(MusicianFeedLane.FOLLOWING);
        });
        var listenerRequest = request.withAudience(BackstageFeedAudience.LISTENER);
        assertThat(provider.findCandidates(listenerRequest)).extracting(candidate -> candidate.author().profileType())
                .containsExactlyInAnyOrder("MUSICIAN", "LISTENER", "VENUE", "BAND");
        sql.update("update tbl_media_asset set content_audience='BACKSTAGE' where owner_id=?", venueProfile);
        assertThat(provider.findCandidates(listenerRequest)).extracting(candidate -> candidate.author().profileType())
                .containsExactlyInAnyOrder("MUSICIAN", "LISTENER", "BAND");
        assertThat(provider.findCandidates(request)).extracting(candidate -> candidate.author().profileType())
                .containsExactlyInAnyOrder("MUSICIAN", "LISTENER", "STUDIO", "VENUE", "BAND");
        insertBandMember(sql, band, viewer, "MEMBER");
        assertThat(provider.findCandidates(request)).extracting(candidate -> candidate.author().profileType())
                .containsExactlyInAnyOrder("MUSICIAN", "LISTENER", "STUDIO", "VENUE");
        sql.update("update tbl_user set status='INACTIVE' where id=?", studio);
        assertThat(provider.findCandidates(request)).extracting(candidate -> candidate.author().profileType())
                .containsExactlyInAnyOrder("MUSICIAN", "LISTENER", "VENUE");
    }

    @Test
    void eventFollowingPrefilterKeepsVenueSoloBandAndVisibleMemberPublishers() {
        JdbcTemplate sql = new JdbcTemplate(dataSource);
        Instant anchor = Instant.parse("2026-09-11T12:00:00Z");
        UUID viewer = UUID.randomUUID(), viewerProfile = UUID.randomUUID();
        UUID performer = UUID.randomUUID(), performerProfile = UUID.randomUUID(), venueOwner = UUID.randomUUID();
        insertMusician(sql, viewer, viewerProfile, "prefilter-viewer");
        insertMusician(sql, performer, performerProfile, "prefilter-performer");
        insertAccount(sql, venueOwner, "prefilter-venue", "ACTIVE", true);
        UUID event = insertFutureVenueEvent(sql, venueOwner, anchor);
        insertFollow(sql, viewer, performer, anchor.minusSeconds(600));
        sql.update("update tbl_event set musician_profile_id=?,profile_calendar_approved=true,performer_approval_status='APPROVED' where id=?",
                performerProfile, event);
        var request = new MusicianFeedCandidateRequest(viewer, viewerProfile, UUID.randomUUID(), anchor, anchor, 20,
                Set.of(MusicianFeedItemType.EVENT), MusicianFeedPersonalizationSnapshot.empty(), MusicianFeedFeedbackSnapshot.empty());
        var provider = new MusicianFeedEventCandidateProvider(new NamedParameterJdbcTemplate(dataSource),
                new EventShareUrlBuilder("https://soundconnect.test"));
        assertThat(provider.findCandidates(request)).singleElement().satisfies(candidate -> {
            assertThat(candidate.lane()).isEqualTo(MusicianFeedLane.FOLLOWING);
            assertThat(candidate.author().profileId()).isEqualTo(performerProfile);
        });
        sql.update("update tbl_event set musician_profile_id=null,profile_calendar_approved=false,performer_approval_status='NOT_REQUIRED' where id=?", event);
        sql.update("insert into event_member_publications(event_id,musician_profile_id,visible,version) values (?,?,true,0)",
                event, performerProfile);
        assertThat(provider.findCandidates(request)).singleElement().satisfies(candidate -> {
            assertThat(candidate.lane()).isEqualTo(MusicianFeedLane.FOLLOWING);
            assertThat(candidate.author().profileId()).isEqualTo(performerProfile);
        });
        sql.update("update event_member_publications set visible=false where event_id=?", event);
        assertThat(provider.findCandidates(request)).singleElement()
                .satisfies(candidate -> assertThat(candidate.lane()).isEqualTo(MusicianFeedLane.GENERAL_DISCOVERY));
        UUID band = UUID.randomUUID();
        sql.update("insert into tbl_band(id,created_at,updated_at,name) values (?,?,?,?)", band,
                Timestamp.from(anchor.minusSeconds(600)), Timestamp.from(anchor.minusSeconds(600)), "Prefilter band");
        insertBandMember(sql, band, performer, "FOUNDER");
        insertBandFollow(sql, viewer, band, anchor.minusSeconds(600));
        sql.update("update tbl_event set band_id=?,profile_calendar_approved=true,performer_approval_status='APPROVED' where id=?", band, event);
        assertThat(provider.findCandidates(request)).singleElement().satisfies(candidate -> {
            assertThat(candidate.lane()).isEqualTo(MusicianFeedLane.FOLLOWING);
            assertThat(candidate.author().profileId()).isEqualTo(band);
        });
        sql.update("delete from tbl_band_follow where follower_id=? and band_id=?", viewer, band);
        insertFollow(sql, viewer, venueOwner, anchor.minusSeconds(600));
        assertThat(provider.findCandidates(request)).singleElement()
                .satisfies(candidate -> assertThat(candidate.lane()).isEqualTo(MusicianFeedLane.FOLLOWING));
        Timestamp recorded = Timestamp.from(anchor.minusSeconds(300));
        for (String action : List.of("HIDE", "REPORT")) {
            UUID feedback = UUID.randomUUID();
            sql.update("insert into tbl_musician_feed_feedback(id,viewer_user_id,action,scope_key,item_id,item_type,created_at,updated_at)"
                    + " values (?,?,?,?,?,'EVENT',?,?)", feedback, viewer, action, "ITEM:EVENT:" + event, "EVENT:" + event, recorded, recorded);
            assertThat(provider.findCandidates(request).size()).as(action + " still suppresses followed event").isZero();
            sql.update("delete from tbl_musician_feed_feedback where id=?", feedback);
        }
        insertMute(sql, viewer, "BAND", band, anchor.minusSeconds(300));
        assertThat(provider.findCandidates(request).size()).as("Mute checks the canonical band publisher").isZero();
        sql.update("delete from tbl_musician_feed_feedback where viewer_user_id=? and action='MUTE_AUTHOR'", viewer);
        UUID delivery = UUID.randomUUID();
        sql.update("""
                insert into tbl_musician_feed_delivery(id,viewer_user_id,feed_session_id,item_id,item_type,feed_lane,
                    target_type,target_id,feedback_capabilities,schema_version,algorithm_version,absolute_position,
                    evidence_json,delivered_at,expires_at,purge_after)
                values (?,?,?,?,'ACTIVITY_COMMENT','FOLLOWING','EVENT',?,'HIDE',1,'fixture',0,'{}'::jsonb,?,?,?)
                """, delivery, viewer, request.feedSessionId(), "ACTIVITY_COMMENT:" + UUID.randomUUID(), event,
                recorded, Timestamp.from(anchor.plusSeconds(3600)), Timestamp.from(anchor.plusSeconds(86400)));
        assertThat(provider.findCandidates(request).size()).as("Comment delivery keeps native event eligible").isEqualTo(1);
        sql.update("update tbl_musician_feed_delivery set item_type='EVENT' where id=?", delivery);
        assertThat(provider.findCandidates(request).size()).as("Other delivered event rendering suppresses the target").isZero();
        sql.update("update tbl_musician_feed_delivery set feed_session_id=? where id=?", UUID.randomUUID(), delivery);
        assertThat(provider.findCandidates(request).size()).as("Prior session does not suppress current event").isEqualTo(1);
        UUID poster = UUID.randomUUID();
        insertPublicAudio(sql, poster, performerProfile, anchor.minusSeconds(600));
        sql.update("update tbl_event set poster_image=? where id=?", poster.toString(), event);
        var posterPayload = (com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedPayloads.Event)
                provider.findCandidates(request).getFirst().payload();
        assertThat(posterPayload.event().posterImage()).isEqualTo("https://cdn.test/play.mp3");
        for (String fallback : List.of("https://cdn.test/external-poster.jpg", "not-a-media-uuid",
                poster.toString().toUpperCase(Locale.ROOT))) {
            sql.update("update tbl_event set poster_image=? where id=?", fallback, event);
            var payload = (com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedPayloads.Event)
                    provider.findCandidates(request).getFirst().payload();
            assertThat(payload.event().posterImage()).isEqualTo(fallback);
        }
        sql.update("update tbl_user set status='INACTIVE' where id=?", performer);
        assertThat(provider.findCandidates(request).size()).as("Broad prefilter cannot bypass canonical performer visibility").isZero();
    }

    @Test
    void orderedEligibilityWalkPassesLongHiddenPrefixesBeforeApplyingEitherProviderLimit() {
        JdbcTemplate sql = new JdbcTemplate(dataSource);
        Instant anchor = Instant.parse("2026-09-11T12:00:00Z");
        UUID viewer = UUID.randomUUID(), viewerProfile = UUID.randomUUID(), author = UUID.randomUUID();
        UUID authorProfile = UUID.randomUUID(), venueOwner = UUID.randomUUID();
        insertMusician(sql, viewer, viewerProfile, "prefix-viewer");
        insertMusician(sql, author, authorProfile, "prefix-author");
        insertAccount(sql, venueOwner, "prefix-venue", "ACTIVE", true);
        insertFollow(sql, viewer, author, anchor.minusSeconds(900));
        insertFollow(sql, viewer, venueOwner, anchor.minusSeconds(900));
        UUID lastTrack = null, lastEvent = null;
        for (int index = 0; index <= 40; index++) {
            UUID media = UUID.randomUUID(), track = UUID.randomUUID();
            Timestamp created = Timestamp.from(anchor.minusSeconds(index + 1));
            insertPublicAudio(sql, media, authorProfile, anchor.minusSeconds(index + 1));
            sql.update("insert into tbl_tracks(id,created_at,updated_at,media_asset_id,owner_type,owner_id,title)"
                    + " values (?,?,?,?,'MUSICIAN_PROFILE',?,?)", track, created, created, media, authorProfile, "Prefix track " + index);
            UUID event = insertFutureVenueEvent(sql, venueOwner, anchor);
            sql.update("update tbl_event set event_date=? where id=?", java.time.LocalDate.of(2026, 9, 12).plusDays(index), event);
            if (index < 40) {
                for (var entry : Map.of("TRACK", track, "EVENT", event).entrySet()) {
                    String item = entry.getKey() + ":" + entry.getValue();
                    sql.update("insert into tbl_musician_feed_feedback(id,viewer_user_id,action,scope_key,item_id,item_type,created_at,updated_at)"
                            + " values (?,?,'HIDE',?,?,?,?,?)", UUID.randomUUID(), viewer, "ITEM:" + item, item,
                            entry.getKey(), created, created);
                }
            }
            lastTrack = track; lastEvent = event;
        }
        var request = new MusicianFeedCandidateRequest(viewer, viewerProfile, UUID.randomUUID(), anchor, anchor, 4,
                Set.of(MusicianFeedItemType.TRACK, MusicianFeedItemType.EVENT),
                MusicianFeedPersonalizationSnapshot.empty(), MusicianFeedFeedbackSnapshot.empty());
        var jdbc = new NamedParameterJdbcTemplate(dataSource);
        assertThat(new MusicianFeedTrackCandidateProvider(jdbc).findCandidates(request))
                .extracting(MusicianFeedCandidate::itemId).containsExactly("TRACK:" + lastTrack);
        assertThat(new MusicianFeedEventCandidateProvider(jdbc, new EventShareUrlBuilder("https://soundconnect.test")).findCandidates(request))
                .extracting(MusicianFeedCandidate::itemId).containsExactly("EVENT:" + lastEvent);
    }

    @Test
    void trackSuppressionSeparatesItemMuteAndSessionTargetWithoutLosingCommentException() {
        JdbcTemplate sql = new JdbcTemplate(dataSource);
        UUID viewer = UUID.randomUUID(), viewerProfile = UUID.randomUUID(), otherViewer = UUID.randomUUID();
        UUID session = UUID.randomUUID(), otherSession = UUID.randomUUID();
        Instant anchor = Instant.parse("2026-09-11T12:00:00Z");
        Timestamp created = Timestamp.from(anchor.minusSeconds(600));
        insertMusician(sql, viewer, viewerProfile, "suppression-viewer");
        insertMusician(sql, otherViewer, UUID.randomUUID(), "suppression-other-viewer");
        List<UUID> tracks = new ArrayList<>(), media = new ArrayList<>(), profiles = new ArrayList<>();
        for (int index = 0; index < 10; index++) {
            UUID author = UUID.randomUUID(), profile = UUID.randomUUID(), asset = UUID.randomUUID(), track = UUID.randomUUID();
            insertMusician(sql, author, profile, "suppression-author-" + index);
            if (index % 2 == 0) insertFollow(sql, viewer, author, anchor.minusSeconds(900));
            insertPublicAudio(sql, asset, profile, anchor.minusSeconds(500 + index));
            sql.update("""
                    insert into tbl_tracks(id,created_at,updated_at,media_asset_id,owner_type,owner_id,title)
                    values (?,?,?,?,'MUSICIAN_PROFILE',?,?)
                    """, track, created, created, asset, profile, "Suppression track " + index);
            tracks.add(track); media.add(asset); profiles.add(profile);
        }
        for (int index : List.of(0, 1, 6, 8)) {
            String action = index == 1 ? "REPORT" : index == 8 ? "SHOW_LESS" : "HIDE";
            sql.update("""
                    insert into tbl_musician_feed_feedback(id,viewer_user_id,action,scope_key,item_id,item_type,created_at,updated_at)
                    values (?,?,?,?,?,'TRACK',?,?)
                    """, UUID.randomUUID(), index == 6 ? otherViewer : viewer, action,
                    "ITEM:TRACK:" + tracks.get(index), "TRACK:" + tracks.get(index), created, created);
        }
        insertMute(sql, viewer, "MUSICIAN", profiles.get(2), anchor.minusSeconds(600));
        insertMute(sql, otherViewer, "MUSICIAN", profiles.get(7), anchor.minusSeconds(600));
        for (int index : List.of(3, 4, 5, 6, 7)) {
            String itemType = index == 4 ? "PROFILE_MEDIA" : index == 5 ? "ACTIVITY_COMMENT" : "TRACK";
            String itemId = itemType + ":" + tracks.get(index);
            // Item-id suppression is independent of target suppression; index3
            // deliberately has a different target. Index5 may still show its native track.
            UUID target = index == 3 ? UUID.randomUUID() : media.get(index);
            sql.update("""
                    insert into tbl_musician_feed_delivery(id,viewer_user_id,feed_session_id,item_id,item_type,feed_lane,
                        target_type,target_id,feedback_capabilities,schema_version,algorithm_version,absolute_position,
                        evidence_json,delivered_at,expires_at,purge_after)
                    values (?,?,?,?,?,'FOLLOWING','MEDIA',?,'HIDE',1,'fixture',?,'{}'::jsonb,?,?,?)
                    """, UUID.randomUUID(), index == 6 ? otherViewer : viewer, index == 7 ? otherSession : session,
                    itemId, itemType, target, (long) index, created,
                    Timestamp.from(anchor.plusSeconds(3600)), Timestamp.from(anchor.plusSeconds(86400)));
        }
        var request = new MusicianFeedCandidateRequest(viewer, viewerProfile, session, anchor, anchor, 40,
                Set.of(MusicianFeedItemType.TRACK), MusicianFeedPersonalizationSnapshot.empty(), MusicianFeedFeedbackSnapshot.empty());
        var candidates = new MusicianFeedTrackCandidateProvider(new NamedParameterJdbcTemplate(dataSource)).findCandidates(request);
        assertThat(candidates).extracting(MusicianFeedCandidate::itemId).containsExactlyInAnyOrder(
                "TRACK:" + tracks.get(5), "TRACK:" + tracks.get(6), "TRACK:" + tracks.get(7),
                "TRACK:" + tracks.get(8), "TRACK:" + tracks.get(9));
        assertThat(candidates).filteredOn(candidate -> candidate.itemId().equals("TRACK:" + tracks.get(6)))
                .allMatch(candidate -> candidate.author().followedByViewer());
        assertThat(candidates).filteredOn(candidate -> candidate.itemId().equals("TRACK:" + tracks.get(7)))
                .allMatch(candidate -> !candidate.author().followedByViewer());
    }

    @Test
    void listenerQueriesExcludeBusinessBeforeLimitsAndKeepFollowedListenerMediaAndSocialActivity() {
        JdbcTemplate sql = new JdbcTemplate(dataSource);
        NamedParameterJdbcTemplate jdbc = new NamedParameterJdbcTemplate(dataSource);
        Instant anchor = Instant.parse("2026-09-11T12:00:00Z");
        Timestamp created = Timestamp.from(anchor.minusSeconds(600));
        UUID viewer = UUID.randomUUID(), viewerProfile = UUID.randomUUID();
        UUID musician = UUID.randomUUID(), musicianProfile = UUID.randomUUID();
        UUID listener = UUID.randomUUID(), listenerProfile = UUID.randomUUID();
        UUID studio = UUID.randomUUID(), studioProfile = UUID.randomUUID();
        insertAccount(sql, viewer, "mainstage-viewer", "ACTIVE", true);
        insertMusician(sql, musician, musicianProfile, "mainstage-artist");
        insertListener(sql, listener, listenerProfile, "mainstage-friend");
        insertAccount(sql, studio, "mainstage-studio", "ACTIVE", true);
        sql.update("insert into tbl_studio_profile(id,created_at,updated_at,user_id,name,time_zone,version)"
                + " values (?,?,?,?,?,'Europe/Istanbul',0)", studioProfile, created, created, studio, "Business studio");
        for (UUID publisher : List.of(musician, listener, studio)) insertFollow(sql, viewer, publisher, anchor.minusSeconds(700));
        UUID publicTrack = UUID.randomUUID(), businessTrack = UUID.randomUUID(), listenerMedia = UUID.randomUUID();
        List<UUID> assets = new ArrayList<>(List.of(publicTrack, businessTrack, listenerMedia));
        for (int index = 0; index < 15; index++) assets.add(UUID.randomUUID());
        for (int index = 0; index < assets.size(); index++) {
            UUID id = assets.get(index);
            UUID profile = index < 2 ? musicianProfile : index == 2 ? listenerProfile : studioProfile;
            String type = index < 2 ? "MUSICIAN" : index == 2 ? "LISTENER" : "STUDIO";
            Instant at = anchor.minusSeconds(index < 3 ? 500 : 100 - index);
            insertPublicAudio(sql, id, profile, at);
            sql.update("update tbl_media_asset set owner_type=?,content_audience=? where id=?",
                    type + "_PROFILE", index == 1 ? "BACKSTAGE" : "MAINSTAGE", id);
            sql.update("insert into tbl_tracks(id,created_at,updated_at,media_asset_id,owner_type,owner_id,title)"
                    + " values (?,?,?,?,?,?,?)", UUID.randomUUID(), Timestamp.from(at), Timestamp.from(at), id,
                    type + "_PROFILE", profile, type + " publication");
            sql.update("insert into tbl_profile_media(id,created_at,updated_at,profile_type,profile_id,media_asset_id,role,order_index)"
                    + " values (?,?,?,?,?,?,'GALLERY',0)", UUID.randomUUID(), Timestamp.from(at), Timestamp.from(at), type, profile, id);
            insertLike(sql, listener, id, "MEDIA", at.plusSeconds(10));
        }
        var request = new MusicianFeedCandidateRequest(viewer, viewerProfile, UUID.randomUUID(), anchor, anchor, 6,
                EnumSet.allOf(MusicianFeedItemType.class), MusicianFeedPersonalizationSnapshot.empty(),
                MusicianFeedFeedbackSnapshot.empty()).withAudience(BackstageFeedAudience.LISTENER);
        var tracks = new MusicianFeedTrackCandidateProvider(jdbc).findCandidates(request);
        assertThat(tracks).extracting(value -> value.target().id()).containsExactlyInAnyOrder(publicTrack, listenerMedia);
        assertThat(tracks).filteredOn(value -> value.author().profileType().equals("LISTENER"))
                .singleElement().satisfies(value -> assertThat(value.lane()).isEqualTo(MusicianFeedLane.FOLLOWING));
        assertThat(new MusicianFeedProfileMediaCandidateProvider(jdbc).findCandidates(request))
                .extracting(value -> value.target().id()).containsExactlyInAnyOrder(publicTrack, listenerMedia);
        var activities = mediaActivityProvider().findCandidates(request);
        assertThat(activities).extracting(value -> value.target().id()).containsExactlyInAnyOrder(publicTrack, listenerMedia);
        assertThat(activities).allSatisfy(value -> assertThat(value.author().profileType()).isEqualTo("LISTENER"));
        // A studio actor liking public music is not a Mainstage social story either.
        insertLike(sql, studio, publicTrack, "MEDIA", anchor.minusSeconds(1));
        assertThat(mediaActivityProvider().findCandidates(request)).allSatisfy(value -> {
            assertThat(value.author().profileType()).isEqualTo("LISTENER");
            assertThat(value.reason().secondaryActorCount()).isZero();
        });
        var policy = new com.berkayb.soundconnect.modules.feed.listener.core.ListenerFeedContentPolicy(jdbc,
                new ObjectMapper().findAndRegisterModules());
        assertThat(policy.filterCandidates(viewer, tracks)).containsExactlyElementsOf(tracks);
        var page = new com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedPageResponse(1, "listener-v1.0.0",
                UUID.randomUUID(), anchor, tracks.stream().map(MusicianFeedCandidate::toResponse).toList(), null, false);
        assertThatCode(() -> policy.requireReplayEligible(viewer, page, anchor)).doesNotThrowAnyException();
        sql.update("update tbl_media_asset set content_audience='BACKSTAGE' where id=?", publicTrack);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> policy.requireReplayEligible(viewer, page, anchor))
                .isInstanceOf(com.berkayb.soundconnect.shared.exception.SoundConnectException.class);
    }

    private static UUID insertCollabActor(JdbcTemplate sql, UUID musicianProfile, Instant anchor) {
        UUID actor = UUID.randomUUID();
        Timestamp created = Timestamp.from(anchor.minusSeconds(900));
        sql.update("""
                insert into tbl_collab_actor(id,created_at,updated_at,profile_type,source_profile_id,
                    display_name,rating_sum,review_count,completed_job_count,active,version)
                values (?,?,?,'MUSICIAN',?,?,0,0,0,true,0)
                """, actor, created, created, musicianProfile, "Studio demand fixture publisher");
        return actor;
    }

    private static UUID insertCollabListing(JdbcTemplate sql, UUID publisher, UUID actor, UUID city,
                                            String wantedType, String branch, UUID instrument, Instant publishedAt) {
        UUID listing = UUID.randomUUID();
        Timestamp published = Timestamp.from(publishedAt);
        sql.update("""
                insert into tbl_collab(id,created_at,updated_at,owner_user_id,publisher_actor_id,
                    client_request_id,creation_payload_hash,cadence,wanted_type,branch,instrument_id,
                    title,description,city_id,status,published_at,version)
                values (?,?,?,?,?,?,?,'REGULAR',?,?,?,?,?,?,'OPEN',?,0)
                """, listing, published, published, publisher, actor, UUID.randomUUID(), "0".repeat(64),
                wantedType, branch, instrument, "Studio-related wording is not a typed request",
                "Recording, rehearsal and studio work are mentioned in this ordinary description.", city, published);
        return listing;
    }

    private static UUID insertFutureVenueEvent(JdbcTemplate sql, UUID venueOwner, Instant anchor) {
        UUID city = UUID.randomUUID();
        UUID district = UUID.randomUUID();
        UUID neighborhood = UUID.randomUUID();
        UUID venue = UUID.randomUUID();
        Timestamp createdAt = Timestamp.from(anchor.minusSeconds(1000));
        sql.update("insert into tbl_city(id,created_at,updated_at,name) values (?,?,?,?)",
                city, createdAt, createdAt, "Feed Event City " + city);
        sql.update("insert into tbl_district(id,created_at,updated_at,name,city_id) values (?,?,?,?,?)",
                district, createdAt, createdAt, "Feed Event District", city);
        sql.update("insert into tbl_neighborhood(id,created_at,updated_at,name,district_id) values (?,?,?,?,?)",
                neighborhood, createdAt, createdAt, "Feed Event Neighborhood", district);
        sql.update("""
                insert into tbl_venues(id,created_at,updated_at,name,address,city_id,district_id,
                    neighborhood_id,status,owner_id) values (?,?,?,?,?,?,?,?,?,?)
                """, venue, createdAt, createdAt, "Feed Event Venue", "Fixture address",
                city, district, neighborhood, "APPROVED", venueOwner);
        UUID event = UUID.randomUUID();
        sql.update("""
                insert into tbl_event(id,created_at,updated_at,title,event_date,start_time,venue_id,
                    event_origin,organizer_user_id,venue_approval_status,venue_calendar_approved,
                    performer_approval_status,profile_calendar_approved,profile_publication_version)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, event, createdAt, createdAt, "Future feed event", java.time.LocalDate.of(2026, 9, 12),
                java.time.LocalTime.of(20, 0), venue, "VENUE", venueOwner, "APPROVED", true,
                "NOT_REQUIRED", false, 0L);
        return event;
    }

    private MusicianFeedMediaActivityCandidateProvider mediaActivityProvider() {
        return new MusicianFeedMediaActivityCandidateProvider(
                new NamedParameterJdbcTemplate(dataSource),
                new EventShareUrlBuilder("https://soundconnect.test"), new ObjectMapper(),
                mock(OverthinkingPostService.class));
    }

    private static MusicianFeedCandidateRequest request(Instant anchor, Set<MusicianFeedItemType> types) {
        return new MusicianFeedCandidateRequest(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                anchor, anchor, 20, types, MusicianFeedPersonalizationSnapshot.empty(),
                MusicianFeedFeedbackSnapshot.empty());
    }

    private static void insertMusician(JdbcTemplate sql, UUID userId, UUID profileId, String username) {
        Instant now = Instant.parse("2026-09-11T10:00:00Z");
        insertAccount(sql, userId, username, "ACTIVE", true);
        sql.update("""
                insert into tbl_musician_profile(id,created_at,updated_at,user_id,name,stage_name)
                values (?,?,?,?,?,?)
                """, profileId, Timestamp.from(now), Timestamp.from(now), userId,
                "Legacy Name " + username, "Legacy Stage " + username);
    }

    private static void insertFollow(JdbcTemplate sql, UUID viewer, UUID actor, Instant followedAt) {
        sql.update("""
                insert into tbl_follow(id,created_at,updated_at,follower_id,following_id,followed_at)
                values (?,?,?,?,?,?)
                """, UUID.randomUUID(), Timestamp.from(followedAt), Timestamp.from(followedAt), viewer, actor,
                Timestamp.from(followedAt));
    }

    private static void insertListener(JdbcTemplate sql, UUID userId, UUID profileId, String username) {
        Instant now = Instant.parse("2026-09-11T10:00:00Z");
        insertAccount(sql, userId, username, "ACTIVE", true);
        UUID roleId = UUID.randomUUID();
        sql.update("insert into tbl_role(id,created_at,updated_at,name) values (?,?,?,?)",
                roleId, Timestamp.from(now), Timestamp.from(now), "ROLE_LISTENER");
        sql.update("insert into user_roles(user_id,role_id) values (?,?)", userId, roleId);
        sql.update("""
                insert into \"tbl_listener-profile\"(id,created_at,updated_at,user_id,name,visibility_mode,
                    visibility_choice_completed,version,playlist_revision)
                values (?,?,?,?,?,?,?,?,?)
                """, profileId, Timestamp.from(now), Timestamp.from(now), userId, username,
                "STANDARD", true, 0L, 0L);
    }

    private static void insertAccount(JdbcTemplate sql, UUID userId, String username,
                                      String status, boolean verified) {
        Instant now = Instant.parse("2026-09-11T10:00:00Z");
        sql.update("""
                insert into tbl_user(id,created_at,updated_at,public_code,user_name,password,email,status,
                                     provider,email_verified)
                values (?,?,?,?,?,?,?,?,?,?)
                """, userId, Timestamp.from(now), Timestamp.from(now),
                "SC-" + userId.toString().replace("-", "").substring(0, 20).toUpperCase(Locale.ROOT),
                username, "not-used", username + "@soundconnect.test", status, "LOCAL", verified);
    }

    private static void insertPublicAudio(JdbcTemplate sql, UUID mediaId, UUID ownerId, Instant createdAt) {
        sql.update("""
                insert into tbl_media_asset(id,created_at,updated_at,kind,status,visibility,owner_type,owner_id,
                    source_url,playback_url,mime_type,size,streaming_protocol,transcode_attempt_count,
                    transcode_retry_pending,transcode_retain_source_after_cleanup)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, mediaId, Timestamp.from(createdAt), Timestamp.from(createdAt), "AUDIO", "READY", "PUBLIC",
                "MUSICIAN_PROFILE", ownerId, "https://cdn.test/source.mp3", "https://cdn.test/play.mp3",
                "audio/mpeg", 1234L, "PROGRESSIVE", 0, false, false);
    }

    private static void insertLike(JdbcTemplate sql, UUID actor, UUID targetId,
                                   String targetType, Instant createdAt) {
        insertLike(sql, UUID.randomUUID(), actor, targetId, targetType, createdAt);
    }

    private static void insertLike(JdbcTemplate sql, UUID likeId, UUID actor, UUID targetId,
                                   String targetType, Instant createdAt) {
        sql.update("""
                insert into tbl_like(id,created_at,updated_at,user_id,target_type,target_id)
                values (?,?,?,?,?,?)
                """, likeId, Timestamp.from(createdAt), Timestamp.from(createdAt), actor,
                targetType, targetId);
    }

    private static void insertComment(JdbcTemplate sql, UUID commentId, UUID actor, UUID targetId,
                                      String targetType, Instant createdAt) {
        sql.update("""
                insert into tbl_comment(id,created_at,updated_at,user_id,target_type,target_id,text,is_deleted)
                values (?,?,?,?,?,?,?,?)
                """, commentId, Timestamp.from(createdAt), Timestamp.from(createdAt), actor,
                targetType, targetId, "Visible activity comment", false);
    }

    private static void insertBandMember(JdbcTemplate sql, UUID bandId, UUID userId, String role) {
        Instant now = Instant.parse("2026-09-11T10:00:00Z");
        sql.update("""
                insert into tbl_band_member(id,created_at,updated_at,band_id,user_id,band_role,status,title_version)
                values (?,?,?,?,?,?,?,?)
                """, UUID.randomUUID(), Timestamp.from(now), Timestamp.from(now), bandId, userId,
                role, "ACTIVE", 0L);
    }

    private static void insertBandFollow(JdbcTemplate sql, UUID actor, UUID bandId, Instant followedAt) {
        sql.update("""
                insert into tbl_band_follow(id,created_at,updated_at,follower_id,band_id,followed_at)
                values (?,?,?,?,?,?)
                """, UUID.randomUUID(), Timestamp.from(followedAt), Timestamp.from(followedAt), actor, bandId,
                Timestamp.from(followedAt));
    }

    private static void insertMute(JdbcTemplate sql, UUID viewer, String profileType,
                                   UUID profileId, Instant createdAt) {
        sql.update("""
                insert into tbl_musician_feed_feedback(id,viewer_user_id,action,scope_key,
                    author_profile_type,author_profile_id,created_at,updated_at)
                values (?,?,?,?,?,?,?,?)
                """, UUID.randomUUID(), viewer, "MUTE_AUTHOR", "AUTHOR:" + profileType + ":" + profileId,
                profileType, profileId, Timestamp.from(createdAt), Timestamp.from(createdAt));
    }
}

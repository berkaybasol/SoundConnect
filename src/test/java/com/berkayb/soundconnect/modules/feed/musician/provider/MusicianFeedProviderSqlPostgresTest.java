package com.berkayb.soundconnect.modules.feed.musician.provider;

import com.berkayb.soundconnect.modules.event.support.EventShareUrlBuilder;
import com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedItemType;
import com.berkayb.soundconnect.modules.feed.musician.candidate.*;
import com.berkayb.soundconnect.modules.feed.musician.feedback.MusicianFeedFeedbackSnapshot;
import com.berkayb.soundconnect.modules.feed.musician.personalization.MusicianFeedPersonalizationSnapshot;
import com.berkayb.soundconnect.modules.overthinking.service.OverthinkingPostService;
import com.berkayb.soundconnect.modules.overthinking.dto.response.OverthinkingPostResponseDto;
import com.berkayb.soundconnect.modules.overthinking.enums.OverthinkingVisibilityType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
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
        }
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
        assertThat(candidate.reason().actors()).extracting(actor -> actor.userId())
                .containsExactly(actorTwo, actorOne);
        assertThat(candidate.reason().secondaryActorCount()).isZero();
        assertThat(((com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedPayloads.Activity)
                candidate.payload()).targetItemType()).isEqualTo(MusicianFeedItemType.TRACK);
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
        assertThat(provider.findCandidates(request)).hasSize(1);
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
                """, profileId, Timestamp.from(now), Timestamp.from(now), userId, username, username);
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
        sql.update("""
                insert into tbl_like(id,created_at,updated_at,user_id,target_type,target_id)
                values (?,?,?,?,?,?)
                """, UUID.randomUUID(), Timestamp.from(createdAt), Timestamp.from(createdAt), actor,
                targetType, targetId);
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

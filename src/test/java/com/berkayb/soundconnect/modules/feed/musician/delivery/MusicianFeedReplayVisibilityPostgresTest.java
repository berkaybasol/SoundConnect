package com.berkayb.soundconnect.modules.feed.musician.delivery;

import com.berkayb.soundconnect.modules.event.support.EventShareUrlBuilder;
import com.berkayb.soundconnect.modules.feed.musician.api.*;
import com.berkayb.soundconnect.modules.feed.musician.candidate.*;
import com.berkayb.soundconnect.modules.feed.musician.core.MusicianFeedService;
import com.berkayb.soundconnect.modules.feed.musician.feedback.MusicianFeedFeedbackSnapshot;
import com.berkayb.soundconnect.modules.feed.musician.mixer.MusicianFeedMixer;
import com.berkayb.soundconnect.modules.feed.musician.moderation.MusicianFeedRestrictionRepository;
import com.berkayb.soundconnect.modules.feed.musician.personalization.MusicianFeedPersonalizationSnapshot;
import com.berkayb.soundconnect.modules.feed.musician.provider.*;
import com.berkayb.soundconnect.modules.overthinking.dto.response.OverthinkingPostResponseDto;
import com.berkayb.soundconnect.modules.overthinking.enums.OverthinkingVisibilityType;
import com.berkayb.soundconnect.modules.overthinking.service.OverthinkingPostService;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real provider payloads, serialized as stored replay JSON, against disposable PostgreSQL. */
@DataJpaTest(properties = {
        "spring.config.location=classpath:/application-test.yml", "spring.config.import=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
        "spring.jpa.properties.hibernate.hbm2ddl.halt_on_error=true"
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MusicianFeedReplayVisibilityPostgresTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine")
            .withDatabaseName("feed_replay_visibility").withUsername("soundconnect").withPassword("soundconnect");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    private static final Instant NOW = Instant.parse("2026-09-13T10:00:00Z");
    @Autowired DataSource dataSource;
    private JdbcTemplate sql;
    private NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private OverthinkingPostService posts;
    private MusicianFeedReplayVisibilityGuard guard;
    private Person viewer;

    @BeforeEach
    void setUp() {
        sql = new JdbcTemplate(dataSource);
        jdbc = new NamedParameterJdbcTemplate(dataSource);
        posts = mock(OverthinkingPostService.class);
        guard = new MusicianFeedReplayVisibilityGuard(jdbc, mapper, posts,
                new com.berkayb.soundconnect.modules.feed.musician.moderation.MusicianFeedRestrictionGuard(
                        new com.berkayb.soundconnect.modules.feed.musician.moderation.MusicianFeedRestrictionRepository(jdbc),
                        new com.berkayb.soundconnect.modules.feed.musician.moderation.MusicianFeedModerationScopeResolver(mapper)));
        viewer = person("MUSICIAN");
    }

    @Test
    void nativeMediaRestrictionFiltersBeforeProviderLimitAndRevokesAlreadyStoredReplay() {
        Person publisher = person("MUSICIAN");
        TrackIds older = track(publisher), newer = track(publisher);
        sql.update("update tbl_tracks set created_at=? where id=?", Timestamp.from(NOW.minusSeconds(10)), newer.publication());
        var provider = new MusicianFeedTrackCandidateProvider(jdbc);
        MusicianFeedPageResponse saved = from(provider);
        visible(saved);
        var restrictions = new MusicianFeedRestrictionRepository(jdbc);
        UUID report = UUID.randomUUID();
        restrictions.apply(report, "TARGET:MEDIA:" + newer.media(), UUID.randomUUID(), NOW);

        var limited = new MusicianFeedCandidateRequest(viewer.user(), viewer.profile(), UUID.randomUUID(), NOW, NOW,
                2, Set.of(MusicianFeedItemType.TRACK), MusicianFeedPersonalizationSnapshot.empty(), MusicianFeedFeedbackSnapshot.empty());
        assertThat(provider.findCandidates(limited)).extracting(MusicianFeedCandidate::itemId)
                .containsExactly("TRACK:" + older.publication());
        revoked(saved);
        assertThat(sql.queryForObject("select visibility from tbl_media_asset where id=?", String.class, newer.media()))
                .isEqualTo("PUBLIC");
        restrictions.restore(report, NOW.plusSeconds(1));
        visible(saved);
    }

    @Test
    void commentRestrictionDoesNotRemoveItsMediaAndIsAppliedBeforeActivitySelection() {
        Person publisher = person("MUSICIAN"), actor = person("MUSICIAN");
        TrackIds source = track(publisher);
        UUID older = UUID.randomUUID(), newer = UUID.randomUUID();
        for (UUID comment : List.of(older, newer)) {
            sql.update("""
                    insert into tbl_comment(id,user_id,target_type,target_id,text,is_deleted,created_at,updated_at)
                    values (?,?,?,?,?,?,?,?)
                    """, comment, actor.user(), "MEDIA", source.media(), "Comment", false,
                    Timestamp.from(NOW.minusSeconds(comment.equals(newer) ? 10 : 30)), stamp());
        }
        var provider = new MusicianFeedMediaActivityCandidateProvider(jdbc,
                new EventShareUrlBuilder("https://soundconnect.test"), mapper, posts);
        var limited = new MusicianFeedCandidateRequest(viewer.user(), viewer.profile(), UUID.randomUUID(), NOW, NOW,
                1, Set.of(MusicianFeedItemType.TRACK, MusicianFeedItemType.ACTIVITY_COMMENT),
                MusicianFeedPersonalizationSnapshot.empty(), MusicianFeedFeedbackSnapshot.empty());
        var original = provider.findCandidates(limited);
        assertThat(original).extracting(MusicianFeedCandidate::itemId).containsExactly("ACTIVITY_COMMENT:" + newer);
        var saved = page(original.stream().map(MusicianFeedCandidate::toResponse).toList());
        visible(saved);
        var restrictions = new MusicianFeedRestrictionRepository(jdbc);
        restrictions.apply(UUID.randomUUID(), "ITEM:ACTIVITY_COMMENT:" + newer, UUID.randomUUID(), NOW);

        assertThat(provider.findCandidates(limited)).extracting(MusicianFeedCandidate::itemId)
                .containsExactly("ACTIVITY_COMMENT:" + older);
        assertThat(new MusicianFeedTrackCandidateProvider(jdbc).findCandidates(request())).hasSize(1);
        assertThat(sql.queryForObject("select is_deleted from tbl_comment where id=?", Boolean.class, newer)).isFalse();
        revoked(saved);
        restrictions.apply(UUID.randomUUID(), "TARGET:MEDIA:" + source.media(), UUID.randomUUID(), NOW);
        assertThat(provider.findCandidates(limited)).isEmpty();
        assertThat(new MusicianFeedTrackCandidateProvider(jdbc).findCandidates(request())).isEmpty();
    }

    @Test
    void profileRestrictionDoesNotCrossProfileNamespacesWithTheSameUuid() {
        Person musician = person("MUSICIAN"), listener = person("LISTENER");
        sql.update("update \"tbl_listener-profile\" set id=? where id=?", musician.profile(), listener.profile());
        sql.update("delete from tbl_follow where follower_id=?", viewer.user());
        var provider = new MusicianFeedProfileDiscoveryCandidateProvider(jdbc);
        assertThat(provider.findCandidates(request())).hasSize(2);
        var restrictions = new MusicianFeedRestrictionRepository(jdbc);
        restrictions.apply(UUID.randomUUID(), "TARGET:PROFILE:MUSICIAN:" + musician.profile(), UUID.randomUUID(), NOW);

        var candidates = provider.findCandidates(request());
        assertThat(candidates).hasSize(1);
        assertThat(candidates.getFirst().author().profileType()).isEqualTo("LISTENER");
        visible(page(candidates.stream().map(MusicianFeedCandidate::toResponse).toList()));
    }

    @Test
    void eventRestrictionAlsoBlocksExactEventPublicationsAndTheirSocialActivities() {
        Person venueOwner = accountOnly(), listener = person("LISTENER"), actor = person("MUSICIAN");
        UUID event = event(venueOwner.user()), post = UUID.randomUUID();
        sql.update("""
                insert into tbl_event_audience_intent(user_id,event_id,intent,published_on_profile,note,
                    version,updated_at,published_at,post_id) values (?,?,?,?,?,?,?,?,?)
                """, listener.user(), event, "GOING", true, "Let's go", 0L, stamp(), stamp(), post);
        sql.update("insert into tbl_like(id,user_id,target_type,target_id,created_at,updated_at) values (?,?,?,?,?,?)",
                UUID.randomUUID(), actor.user(), "EVENT_POST", post, stamp(), stamp());
        var urls = new EventShareUrlBuilder("https://soundconnect.test");
        var shares = new MusicianFeedListenerEventShareCandidateProvider(jdbc, urls);
        var activity = new MusicianFeedMediaActivityCandidateProvider(jdbc, urls, mapper, posts);
        var saved = from(shares);
        visible(saved);
        assertThat(activity.findCandidates(request())).isNotEmpty();
        new MusicianFeedRestrictionRepository(jdbc).apply(UUID.randomUUID(), "TARGET:EVENT:" + event,
                UUID.randomUUID(), NOW);

        assertThat(shares.findCandidates(request())).isEmpty();
        assertThat(activity.findCandidates(request())).isEmpty();
        revoked(saved);
    }

    @Test
    void trackReplayRequiresPublicReadyMediaAndTheOriginalLivePublication() {
        Person publisher = person("MUSICIAN");
        TrackIds track = track(publisher);
        MusicianFeedPageResponse page = from(new MusicianFeedTrackCandidateProvider(jdbc));
        visible(page);
        sql.update("update tbl_media_asset set visibility='PRIVATE' where id=?", track.media());
        revoked(page);
        sql.update("update tbl_media_asset set visibility='PUBLIC' where id=?", track.media());
        visible(page);
        sql.update("delete from tbl_tracks where id=?", track.publication());
        revoked(page);
    }

    @Test
    void replayIsNotLimitedToTheLatestCandidateWindowAndEngagementChangesKeepItsExactBody() throws Exception {
        Person publisher = person("MUSICIAN");
        TrackIds original = track(publisher);
        MusicianFeedPageResponse page = from(new MusicianFeedTrackCandidateProvider(jdbc));
        String saved = mapper.writeValueAsString(page);
        for (int index = 0; index < 55; index++) track(publisher);
        sql.update("insert into tbl_like(id,user_id,target_type,target_id,created_at,updated_at) values (?,?,?,?,?,?)",
                UUID.randomUUID(), viewer.user(), "MEDIA", original.media(), stamp(), stamp());
        visible(page);
        assertThat(mapper.writeValueAsString(page)).isEqualTo(saved);
    }

    @Test
    void listenerPublicationRevokesOnGhostAndAmbiguousRoleFootprints() {
        Person publisher = person("LISTENER");
        track(publisher);
        MusicianFeedPageResponse page = from(new MusicianFeedTrackCandidateProvider(jdbc));
        visible(page);
        sql.update("update \"tbl_listener-profile\" set visibility_mode='GHOST' where id=?", publisher.profile());
        revoked(page);
        sql.update("update \"tbl_listener-profile\" set visibility_mode='STANDARD' where id=?", publisher.profile());
        role(publisher.user(), "ROLE_PRODUCER");
        revoked(page);
    }

    @Test
    void inactivePublisherAndHiddenOrMutedItemsCannotReplay() {
        Person publisher = person("MUSICIAN");
        track(publisher);
        MusicianFeedPageResponse page = from(new MusicianFeedTrackCandidateProvider(jdbc));
        visible(page);
        sql.update("update tbl_user set email_verified=false where id=?", publisher.user());
        revoked(page);
        sql.update("update tbl_user set email_verified=true where id=?", publisher.user());
        MusicianFeedItemResponse item = page.items().getFirst();
        UUID feedbackId = UUID.randomUUID();
        sql.update("""
                insert into tbl_musician_feed_feedback(id,viewer_user_id,action,scope_key,item_id,item_type,created_at,updated_at)
                values (?,?,?,?,?,?,?,?)
                """, feedbackId, viewer.user(), "HIDE", "ITEM:" + item.id(), item.id(), item.type().name(), stamp(), stamp());
        revoked(page);
        sql.update("delete from tbl_musician_feed_feedback where id=?", feedbackId);
        sql.update("""
                insert into tbl_musician_feed_feedback(id,viewer_user_id,action,scope_key,author_profile_type,
                    author_profile_id,created_at,updated_at) values (?,?,?,?,?,?,?,?)
                """, feedbackId, viewer.user(), "MUTE_AUTHOR", "AUTHOR:MUSICIAN:" + publisher.profile(),
                "MUSICIAN", publisher.profile(), stamp(), stamp());
        revoked(page);
    }

    @Test
    void galleryAttachmentMustStillExistEvenWhenItsMediaAssetRemainsPublic() {
        Person publisher = person("MUSICIAN");
        TrackIds track = track(publisher);
        UUID attachment = UUID.randomUUID();
        sql.update("""
                insert into tbl_profile_media(id,created_at,updated_at,profile_type,profile_id,media_asset_id,role,order_index)
                values (?,?,?,?,?,?,?,?)
                """, attachment, stamp(), stamp(), "MUSICIAN", publisher.profile(), track.media(), "GALLERY", 0);
        MusicianFeedPageResponse page = from(new MusicianFeedProfileMediaCandidateProvider(jdbc));
        visible(page);
        sql.update("delete from tbl_profile_media where id=?", attachment);
        revoked(page);
    }

    @Test
    void socialReasonCannotReplayTheIdentityOfANowGhostListener() {
        Person publisher = person("MUSICIAN");
        Person actor = person("LISTENER");
        TrackIds track = track(publisher);
        sql.update("insert into tbl_like(id,user_id,target_type,target_id,created_at,updated_at) values (?,?,?,?,?,?)",
                UUID.randomUUID(), actor.user(), "MEDIA", track.media(), stamp(), stamp());
        MusicianFeedPageResponse page = from(new MusicianFeedMediaActivityCandidateProvider(jdbc,
                new EventShareUrlBuilder("https://soundconnect.test"), mapper, posts));
        visible(page);
        sql.update("update \"tbl_listener-profile\" set visibility_mode='GHOST' where id=?", actor.profile());
        revoked(page);
    }

    @Test
    void deletedCommentCannotReplayItsOldActivityText() {
        Person publisher = person("MUSICIAN");
        Person actor = person("MUSICIAN");
        TrackIds track = track(publisher);
        UUID comment = UUID.randomUUID();
        sql.update("""
                insert into tbl_comment(id,user_id,target_type,target_id,text,is_deleted,created_at,updated_at)
                values (?,?,?,?,?,?,?,?)
                """, comment, actor.user(), "MEDIA", track.media(), "This must disappear", false, stamp(), stamp());
        MusicianFeedPageResponse page = from(new MusicianFeedMediaActivityCandidateProvider(jdbc,
                new EventShareUrlBuilder("https://soundconnect.test"), mapper, posts));
        visible(page);
        sql.update("update tbl_comment set is_deleted=true where id=?", comment);
        revoked(page);
    }

    @Test
    void overthinkingUsesCurrentViewerMaskingWhileIgnoringReactionCounters() {
        Person publisher = person("LISTENER");
        UUID source = UUID.randomUUID();
        UUID share = UUID.randomUUID();
        sql.update("""
                insert into tbl_overthinking_post(id,created_at,updated_at,author_id,title,content,visibility_type)
                values (?,?,?,?,?,?,?)
                """, source, stamp(), stamp(), publisher.user(), "Thought", "Body", "ANONYMOUS");
        sql.update("""
                insert into tbl_overthinking_profile_share(id,owner_user_id,listener_profile_id,source_post_id,note,published_at)
                values (?,?,?,?,?,?)
                """, share, publisher.user(), publisher.profile(), source, "Note", stamp());
        when(posts.getByIdsForViewer(eq(viewer.user()), anyList()))
                .thenReturn(Map.of(source, thought(source, publisher.user(), true, 0)));
        MusicianFeedPageResponse page = from(new MusicianFeedOverthinkingShareCandidateProvider(jdbc, posts));
        visible(page);
        when(posts.getByIdsForViewer(eq(viewer.user()), anyList()))
                .thenReturn(Map.of(source, thought(source, publisher.user(), true, 99)));
        visible(page);
        when(posts.getByIdsForViewer(eq(viewer.user()), anyList()))
                .thenReturn(Map.of(source, thought(source, publisher.user(), false, 99)));
        revoked(page);
        when(posts.getByIdsForViewer(eq(viewer.user()), anyList())).thenReturn(Map.of());
        revoked(page);
    }

    @Test
    void tableGroupFrozenHistoryRemainsReadableAfterExpiryButLivePublicationDoesNot() throws Exception {
        Person publisher = person("LISTENER");
        UUID table = UUID.randomUUID();
        UUID share = UUID.randomUUID();
        sql.update("""
                insert into tbl_table_group(id,created_at,updated_at,owner_id,create_request_key,description,
                    max_person_count,age_min,age_max,start_at,meeting_at,expires_at,status,city_id,version)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, table, stamp(), stamp(), publisher.user(), UUID.randomUUID(), "Let's meet", 4, 18, 70,
                stamp(), Timestamp.from(NOW.plusSeconds(3600)), Timestamp.from(NOW.plusSeconds(7200)), "ACTIVE", city(), 0L);
        sql.update("""
                insert into tbl_table_group_profile_share(id,owner_user_id,listener_profile_id,table_group_id,note,published_at)
                values (?,?,?,?,?,?)
                """, share, publisher.user(), publisher.profile(), table, "Meet there", stamp());
        MusicianFeedPageResponse page = from(new MusicianFeedTableGroupShareCandidateProvider(jdbc, mapper));
        visible(page);
        sql.update("update tbl_table_group set expires_at=? where id=?", Timestamp.from(NOW.minusSeconds(1)), table);
        revoked(page);
        String frozen = mapper.valueToTree(page.items().getFirst().payload()).path("source").toString();
        sql.update("update tbl_table_group_profile_share set final_source=cast(? as jsonb),final_source_frozen=true where id=?",
                frozen, share);
        visible(page);
        sql.update("delete from tbl_table_group_profile_share where id=?", share);
        revoked(page);
    }

    @Test
    void eventReplayRevokesAfterVenueApprovalWithdrawalAndAfterStartTime() {
        Person owner = accountOnly();
        UUID event = event(owner.user());
        MusicianFeedPageResponse page = from(new MusicianFeedEventCandidateProvider(jdbc,
                new EventShareUrlBuilder("https://soundconnect.test")));
        visible(page);
        sql.update("update tbl_venues set status='PENDING' where id=(select venue_id from tbl_event where id=?)", event);
        revoked(page);
        sql.update("update tbl_venues set status='APPROVED' where id=(select venue_id from tbl_event where id=?)", event);
        assertThatThrownBy(() -> guard.requireVisible(viewer.user(), page, NOW.plusSeconds(2 * 86400)))
                .isInstanceOfSatisfying(SoundConnectException.class,
                        failure -> assertThat(failure.getErrorType()).isEqualTo(ErrorType.MUSICIAN_FEED_CURSOR_INVALID));
    }

    @Test
    void listenerEventWrapperMustStillBePublished() {
        Person owner = accountOnly();
        UUID event = event(owner.user());
        Person publisher = person("LISTENER");
        UUID post = UUID.randomUUID();
        sql.update("""
                insert into tbl_event_audience_intent(user_id,event_id,intent,published_on_profile,note,
                    version,updated_at,published_at,post_id)
                values (?,?,?,?,?,?,?,?,?)
                """, publisher.user(), event, "GOING", true, "See you", 0L, stamp(), stamp(), post);
        MusicianFeedPageResponse page = from(new MusicianFeedListenerEventShareCandidateProvider(jdbc,
                new EventShareUrlBuilder("https://soundconnect.test")));
        visible(page);
        sql.update("update tbl_event_audience_intent set published_on_profile=false where post_id=?", post);
        revoked(page);
    }

    @Test
    void mergedEventSocialProofDoesNotRequireThePublishingVenueToLikeItsOwnEvent() {
        Person owner = accountOnly();
        Person performer = person("MUSICIAN");
        Person liker = person("MUSICIAN");
        UUID event = event(owner.user());
        sql.update("insert into tbl_follow(id,follower_id,following_id,followed_at,created_at,updated_at) values (?,?,?,?,?,?)",
                UUID.randomUUID(), viewer.user(), owner.user(), stamp(), stamp(), stamp());
        sql.update("""
                update tbl_event set musician_profile_id=?,profile_calendar_approved=true,
                    performer_approval_status='APPROVED',manual_performer_name=null where id=?
                """, performer.profile(), event);
        sql.update("insert into tbl_like(id,user_id,target_type,target_id,created_at,updated_at) values (?,?,?,?,?,?)",
                UUID.randomUUID(), liker.user(), "EVENT", event, stamp(), stamp());
        var urls = new EventShareUrlBuilder("https://soundconnect.test");
        var candidates = new ArrayList<>(new MusicianFeedEventCandidateProvider(jdbc, urls).findCandidates(request()));
        candidates.addAll(new MusicianFeedMediaActivityCandidateProvider(jdbc, urls, mapper, posts).findCandidates(request()));
        var mixed = new MusicianFeedMixer().mix(viewer.user(), NOW, 20,
                EnumSet.allOf(MusicianFeedItemType.class), MusicianFeedFeedbackSnapshot.empty(), candidates,
                List.of(), null, 0);
        assertThat(mixed.items()).hasSize(1);
        assertThat(mixed.items().getFirst().type()).isEqualTo(MusicianFeedItemType.EVENT);
        assertThat(mixed.items().getFirst().reason().actors()).extracting(MusicianFeedItemResponse.Author::userId)
                .contains(owner.user(), liker.user());
        MusicianFeedPageResponse page = page(mixed.items());
        visible(page);
        sql.update("delete from tbl_like where user_id=? and target_type='EVENT' and target_id=?", liker.user(), event);
        revoked(page);
    }

    @Test
    void completionReplayAcceptsOnlyTheViewersOwnProfile() {
        var completion = new MusicianFeedPayloads.Completion(0, 1, List.of());
        var own = new MusicianFeedItemResponse("PROFILE_COMPLETION:" + viewer.profile(),
                MusicianFeedItemType.PROFILE_COMPLETION, 1, NOW,
                new MusicianFeedItemResponse.Reason(MusicianFeedReasonCode.PROFILE_INCOMPLETE, List.of(), 0),
                null, new MusicianFeedItemResponse.Target("PROFILE", viewer.profile()), null, null,
                List.of(MusicianFeedFeedbackAction.HIDE), completion);
        visible(page(List.of(own)));
        Person other = person("MUSICIAN");
        var foreign = new MusicianFeedItemResponse("PROFILE_COMPLETION:" + other.profile(), own.type(), 1, NOW,
                own.reason(), null, new MusicianFeedItemResponse.Target("PROFILE", other.profile()),
                null, null, own.feedbackCapabilities(), completion);
        revoked(page(List.of(foreign)));
    }

    @Test
    void mergedPublicationCannotUseItsAuthorIdentityToKeepARemovedSelfLike() {
        Person publisher = person("MUSICIAN");
        TrackIds track = track(publisher);
        sql.update("insert into tbl_like(id,user_id,target_type,target_id,created_at,updated_at) values (?,?,?,?,?,?)",
                UUID.randomUUID(), publisher.user(), "MEDIA", track.media(), stamp(), stamp());
        var candidates = new ArrayList<>(new MusicianFeedTrackCandidateProvider(jdbc).findCandidates(request()));
        candidates.addAll(new MusicianFeedMediaActivityCandidateProvider(jdbc,
                new EventShareUrlBuilder("https://soundconnect.test"), mapper, posts).findCandidates(request()));
        var mixed = new MusicianFeedMixer().mix(viewer.user(), NOW, 20,
                EnumSet.allOf(MusicianFeedItemType.class), MusicianFeedFeedbackSnapshot.empty(), candidates,
                List.of(), null, 0);
        assertThat(mixed.items()).hasSize(1);
        assertThat(mixed.items().getFirst().reason().code()).isEqualTo(MusicianFeedReasonCode.FOLLOWED_USER_LIKED);
        var page = page(mixed.items());
        visible(page);
        sql.update("delete from tbl_like where user_id=? and target_type='MEDIA' and target_id=?", publisher.user(), track.media());
        revoked(page);
    }

    @Test
    void collabReplayRequiresOpenUnexpiredListing() {
        Person publisher = person("MUSICIAN");
        UUID actor = UUID.randomUUID();
        UUID listing = UUID.randomUUID();
        sql.update("""
                insert into tbl_collab_actor(id,created_at,updated_at,profile_type,source_profile_id,display_name,
                    rating_sum,review_count,completed_job_count,active,version) values (?,?,?,?,?,?,?,?,?,?,?)
                """, actor, stamp(), stamp(), "MUSICIAN", publisher.profile(), "Publisher", 0L, 0L, 0L, true, 0L);
        sql.update("""
                insert into tbl_collab(id,created_at,updated_at,owner_user_id,publisher_actor_id,client_request_id,
                    creation_payload_hash,cadence,wanted_type,title,description,city_id,status,published_at,version)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, listing, stamp(), stamp(), publisher.user(), actor, UUID.randomUUID(), "0".repeat(64),
                "REGULAR", "MUSICIAN", "Join us", "We need a musician", city(), "OPEN", stamp(), 0L);
        MusicianFeedPageResponse page = from(new MusicianFeedCollabCandidateProvider(jdbc));
        visible(page);
        sql.update("update tbl_collab set expires_at=? where id=?", Timestamp.from(NOW.minusSeconds(1)), listing);
        revoked(page);
        sql.update("update tbl_collab set expires_at=null,status='CLOSED' where id=?", listing);
        revoked(page);
    }

    @Test
    void discoveryProfileRevokesWhenItsAccountBecomesUnavailable() {
        Person publisher = person("MUSICIAN");
        sql.update("delete from tbl_follow where follower_id=? and following_id=?", viewer.user(), publisher.user());
        MusicianFeedPageResponse page = from(new MusicianFeedProfileDiscoveryCandidateProvider(jdbc));
        visible(page);
        sql.update("update tbl_user set email_verified=false where id=?", publisher.user());
        revoked(page);
    }

    private MusicianFeedPageResponse from(MusicianFeedCandidateProvider provider) {
        List<MusicianFeedCandidate> candidates = provider.findCandidates(request());
        assertThat(candidates).as(provider.providerId() + " positive fixture").isNotEmpty();
        return page(candidates.stream().map(MusicianFeedCandidate::toResponse).toList());
    }

    private MusicianFeedCandidateRequest request() {
        return new MusicianFeedCandidateRequest(viewer.user(), viewer.profile(), UUID.randomUUID(), NOW, NOW, 50,
                EnumSet.allOf(MusicianFeedItemType.class), MusicianFeedPersonalizationSnapshot.empty(),
                MusicianFeedFeedbackSnapshot.empty());
    }

    private MusicianFeedPageResponse page(List<MusicianFeedItemResponse> items) {
        var page = new MusicianFeedPageResponse(1, MusicianFeedService.ALGORITHM_VERSION, UUID.randomUUID(), NOW,
                items, null, false);
        // Stored replay payloads are Maps; do not accidentally test records only.
        return mapper.convertValue(mapper.valueToTree(page), MusicianFeedPageResponse.class);
    }

    private void visible(MusicianFeedPageResponse page) {
        assertThatCode(() -> guard.requireVisible(viewer.user(), page, NOW)).doesNotThrowAnyException();
    }

    private void revoked(MusicianFeedPageResponse page) {
        assertThatThrownBy(() -> guard.requireVisible(viewer.user(), page, NOW))
                .isInstanceOfSatisfying(SoundConnectException.class,
                        failure -> assertThat(failure.getErrorType()).isEqualTo(ErrorType.MUSICIAN_FEED_CURSOR_INVALID));
    }

    private Person accountOnly() {
        UUID user = UUID.randomUUID();
        String username = "user" + user.toString().replace("-", "").substring(0, 16);
        sql.update("""
                insert into tbl_user(id,created_at,updated_at,public_code,user_name,password,email,status,provider,email_verified)
                values (?,?,?,?,?,?,?,?,?,?)
                """, user, stamp(), stamp(), "SC-" + user.toString().substring(0, 18).toUpperCase(Locale.ROOT),
                username, "not-used", username + "@soundconnect.test", "ACTIVE", "LOCAL", true);
        return new Person(user, null, null);
    }

    private Person person(String type) {
        Person account = accountOnly();
        UUID profile = UUID.randomUUID();
        if (type.equals("MUSICIAN")) {
            sql.update("insert into tbl_musician_profile(id,created_at,updated_at,user_id) values (?,?,?,?)",
                    profile, stamp(), stamp(), account.user());
        } else {
            sql.update("""
                    insert into "tbl_listener-profile"(id,created_at,updated_at,user_id,name,visibility_mode,
                        visibility_choice_completed,version,playlist_revision) values (?,?,?,?,?,?,?,?,?)
                    """, profile, stamp(), stamp(), account.user(), "Listener", "STANDARD", true, 0L, 0L);
        }
        role(account.user(), "ROLE_" + type);
        if (viewer != null) {
            sql.update("insert into tbl_follow(id,follower_id,following_id,followed_at,created_at,updated_at) values (?,?,?,?,?,?)",
                    UUID.randomUUID(), viewer.user(), account.user(), stamp(), stamp(), stamp());
        }
        return new Person(account.user(), profile, type);
    }

    private void role(UUID user, String name) {
        List<UUID> existing = sql.queryForList("select id from tbl_role where name=?", UUID.class, name);
        UUID role = existing.isEmpty() ? UUID.randomUUID() : existing.getFirst();
        if (existing.isEmpty()) sql.update("insert into tbl_role(id,created_at,updated_at,name) values (?,?,?,?)", role, stamp(), stamp(), name);
        sql.update("insert into user_roles(user_id,role_id) values (?,?)", user, role);
    }

    private TrackIds track(Person owner) {
        UUID media = UUID.randomUUID();
        UUID track = UUID.randomUUID();
        sql.update("""
                insert into tbl_media_asset(id,created_at,updated_at,kind,status,visibility,owner_type,owner_id,
                    source_url,playback_url,mime_type,size,streaming_protocol,transcode_attempt_count,
                    transcode_retry_pending,transcode_retain_source_after_cleanup) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, media, stamp(), stamp(), "AUDIO", "READY", "PUBLIC", owner.type() + "_PROFILE", owner.profile(),
                "https://cdn.test/source.mp3", "https://cdn.test/play.mp3", "audio/mpeg", 1234L, "PROGRESSIVE", 0, false, false);
        sql.update("insert into tbl_tracks(id,created_at,updated_at,media_asset_id,owner_type,owner_id,title) values (?,?,?,?,?,?,?)",
                track, stamp(), stamp(), media, owner.type() + "_PROFILE", owner.profile(), "Track");
        return new TrackIds(track, media);
    }

    private UUID city() {
        UUID city = UUID.randomUUID();
        sql.update("insert into tbl_city(id,created_at,updated_at,name) values (?,?,?,?)", city, stamp(), stamp(), "City " + city);
        return city;
    }

    private UUID event(UUID owner) {
        UUID city = city(), district = UUID.randomUUID(), neighborhood = UUID.randomUUID(), venue = UUID.randomUUID();
        sql.update("insert into tbl_district(id,created_at,updated_at,name,city_id) values (?,?,?,?,?)", district, stamp(), stamp(), "District", city);
        sql.update("insert into tbl_neighborhood(id,created_at,updated_at,name,district_id) values (?,?,?,?,?)", neighborhood, stamp(), stamp(), "Neighborhood", district);
        sql.update("""
                insert into tbl_venues(id,created_at,updated_at,name,address,city_id,district_id,neighborhood_id,status,owner_id)
                values (?,?,?,?,?,?,?,?,?,?)
                """, venue, stamp(), stamp(), "Venue", "Address", city, district, neighborhood, "APPROVED", owner);
        sql.update("insert into tbl_venue_profile(id,created_at,updated_at,venue_id) values (?,?,?,?)", UUID.randomUUID(), stamp(), stamp(), venue);
        UUID event = UUID.randomUUID();
        sql.update("""
                insert into tbl_event(id,created_at,updated_at,title,event_date,start_time,venue_id,event_origin,
                    organizer_user_id,venue_approval_status,venue_calendar_approved,performer_approval_status,
                    profile_calendar_approved,manual_performer_name,profile_publication_version) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, event, stamp(), stamp(), "Tomorrow's event", java.time.LocalDate.of(2026, 9, 14),
                java.time.LocalTime.of(20, 0), venue, "VENUE", owner, "APPROVED", true, "NOT_REQUIRED", false, "Guest", 0L);
        return event;
    }

    private OverthinkingPostResponseDto thought(UUID source, UUID author, boolean canView, long likes) {
        return new OverthinkingPostResponseDto(source, author, "Author", "https://cdn.test/avatar.jpg",
                true, canView, OverthinkingVisibilityType.ANONYMOUS, "Thought", "Body",
                null, null, null, null, null, null, null, null, null, likes, 0, false);
    }

    private Timestamp stamp() { return Timestamp.from(NOW.minusSeconds(120)); }
    private record Person(UUID user, UUID profile, String type) { }
    private record TrackIds(UUID publication, UUID media) { }
}

package com.berkayb.soundconnect.modules.overthinking.service;

import com.berkayb.soundconnect.modules.comment.abuse.CommentBurstGuard;
import com.berkayb.soundconnect.modules.comment.dto.request.CommentCreateRequestDto;
import com.berkayb.soundconnect.modules.comment.mapper.CommentMapper;
import com.berkayb.soundconnect.modules.comment.repository.CommentRepository;
import com.berkayb.soundconnect.modules.comment.service.CommentServiceImpl;
import com.berkayb.soundconnect.modules.comment.support.*;
import com.berkayb.soundconnect.modules.engagement.enums.EngagementTargetType;
import com.berkayb.soundconnect.modules.engagement.service.*;
import com.berkayb.soundconnect.modules.like.repository.LikeRepository;
import com.berkayb.soundconnect.modules.like.service.*;
import com.berkayb.soundconnect.modules.media.entity.MediaAsset;
import com.berkayb.soundconnect.modules.media.enums.*;
import com.berkayb.soundconnect.modules.media.repository.MediaAssetRepository;
import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.overthinking.dto.request.OverthinkingPostSaveRequestDto;
import com.berkayb.soundconnect.modules.overthinking.entity.OverthinkingPost;
import com.berkayb.soundconnect.modules.overthinking.enums.*;
import com.berkayb.soundconnect.modules.overthinking.mapper.*;
import com.berkayb.soundconnect.modules.overthinking.repository.*;
import com.berkayb.soundconnect.modules.overthinking.profileshare.*;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.entity.ListenerProfile;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.enums.ListenerVisibilityMode;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.repository.ListenerProfileRepository;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.support.ListenerVisibilityPolicy;
import com.berkayb.soundconnect.modules.role.entity.Role;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.repository.BandRepository;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.service.BandService;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.entity.MusicianProfile;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.repository.MusicianProfileRepository;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.service.MusicianProfileService;
import com.berkayb.soundconnect.modules.profile.StudioProfile.repository.StudioProfileRepository;
import com.berkayb.soundconnect.modules.profile.shared.identity.GhostListenerIdentityBatchResolver;
import com.berkayb.soundconnect.modules.profile.shared.resolver.service.PublicProfileResolverService;
import com.berkayb.soundconnect.modules.spotify.client.SpotifyApiClient;
import com.berkayb.soundconnect.modules.track.entity.Track;
import com.berkayb.soundconnect.modules.track.enums.TrackOwnerType;
import com.berkayb.soundconnect.modules.track.mapper.TrackMapper;
import com.berkayb.soundconnect.modules.track.repository.TrackRepository;
import com.berkayb.soundconnect.modules.track.service.TrackServiceImpl;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.modules.user.support.UserEntityFinder;
import com.berkayb.soundconnect.shared.config.JpaAuditingConfig;
import com.berkayb.soundconnect.shared.exception.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.mapstruct.factory.Mappers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real service transactions and database locks; no application database or storage credentials. */
@DataJpaTest(properties = {"spring.config.location=classpath:/application-test.yml", "spring.config.import=",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.show-sql=false",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect"})
@ActiveProfiles("test") @Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = OverthinkingLifecyclePostgresTest.Config.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OverthinkingLifecyclePostgresTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine")
            .withDatabaseName("overthinking_lifecycle_test").withUsername("overthinking_test")
            .withPassword("overthinking_test").withReuse(false);
    @Autowired DataSource dataSource;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager manager;
    @Autowired OverthinkingPostServiceImpl posts;
    @Autowired OverthinkingPostCommandService commands;
    @Autowired OverthinkingRevealRequestServiceImpl reveals;
    @Autowired CommentServiceImpl comments;
    @Autowired LikeServiceImpl likes;
    @Autowired TrackServiceImpl trackService;
    @Autowired TrackRepository tracks;
    @Autowired OverthinkingProfileShareService shares;
    @Autowired OverthinkingProfileShareRepository shareRepository;
    @Autowired ListenerProfileRepository listenerProfiles;
    @MockitoBean UserEntityFinder users;
    @MockitoBean GhostListenerIdentityBatchResolver ghostIdentities;
    @MockitoBean PublicProfileResolverService profiles;
    @MockitoBean SpotifyApiClient spotify;
    @MockitoBean OverthinkingNotificationService notifications;
    @MockitoBean OverthinkingRevealRateGuard rateGuard;
    @MockitoBean OverthinkingRevealNotificationRetractionService notificationRetraction;
    @MockitoBean CommentAuthorBatchResolver commentAuthors;
    @MockitoBean CommentBurstGuard burst;
    @MockitoBean MediaEngagementNotificationService engagementNotifications;
    @MockitoBean MediaAssetService media;
    @MockitoBean MusicianProfileService musicians;
    @MockitoBean BandService bands;
    @MockitoBean TrackMapper trackMapper;
    JdbcTemplate jdbc;
    UUID author, reader;

    @BeforeEach void setup() throws Exception {
        applyMigration();
        jdbc = new JdbcTemplate(dataSource);
        author = tx(this::user);
        reader = tx(this::user);
        when(users.getUser(any())).thenAnswer(i -> em.getReference(User.class, i.getArgument(0)));
        when(commentAuthors.resolve(any())).thenAnswer(i -> {
            Map<UUID, com.berkayb.soundconnect.modules.comment.dto.support.UserSummaryDto> result = new HashMap<>();
            for (UUID id : i.<Collection<UUID>>getArgument(0)) result.put(id,
                    new com.berkayb.soundconnect.modules.comment.dto.support.UserSummaryDto(id, "test-user", null));
            return result;
        });
        when(musicians.getProfileEntity(any())).thenAnswer(i -> MusicianProfile.builder()
                .id(i.getArgument(0)).user(User.builder().id(author).build()).build());
        doAnswer(i -> {
            jdbc.update("update tbl_media_asset set status='DELETION_PENDING' where id=?", i.<UUID>getArgument(0));
            return null;
        }).when(media).delete(any(), any(), any(), any());
    }

    private void applyMigration() throws Exception {
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getURL()).isEqualTo(POSTGRES.getJdbcUrl());
            assertThat(connection.getCatalog()).isEqualTo(POSTGRES.getDatabaseName());
            try (var statement = connection.createStatement()) {
                statement.execute(Files.readString(Path.of("scripts/db/2026-09-09-overthinking-lifecycle.sql")));
                statement.execute(Files.readString(Path.of("scripts/db/2026-09-10-overthinking-profile-shares.sql")));
                statement.execute(Files.readString(Path.of("scripts/db/2026-09-10-overthinking-production-safety.sql")));
            }
        }
    }

    @Test void deletingPostRemovesRevealRootsRepliesAndBothKindsOfLikesOnlyForThatPost() {
        UUID post = post(null), other = post(null);
        UUID root = comment(post, null), reply = comment(post, root), otherRoot = comment(other, null);
        reveals.createRevealRequest(reader, post);
        reveals.createRevealRequest(reader, other);
        likes.like(reader, EngagementTargetType.OVERTHINKING, post);
        likes.setCommentLike(reader, root, true);
        likes.setCommentLike(reader, reply, true);
        likes.setCommentLike(reader, otherRoot, true);
        posts.delete(post, author);
        assertGone(post, root, reply);
        assertThat(count("tbl_overthinking_post", "id", other)).isEqualTo(1);
        assertThat(count("tbl_overthinking_reveal_request", "post_id", other)).isEqualTo(1);
        assertThat(count("tbl_comment", "id", otherRoot)).isEqualTo(1);
        assertThat(count("tbl_like", "target_id", otherRoot)).isEqualTo(1);
    }

    @Test void failedOrUnauthorizedDeletionPreservesEveryDependentRow() {
        UUID post = post(null), root = comment(post, null);
        reveals.createRevealRequest(reader, post);
        likes.setCommentLike(reader, root, true);
        assertError(() -> posts.delete(post, reader), ErrorType.FORBIDDEN_ACCESS);
        assertThatThrownBy(() -> tx(() -> {
            posts.delete(post, author);
            throw new IllegalStateException("rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(count("tbl_overthinking_post", "id", post)).isEqualTo(1);
        assertThat(count("tbl_overthinking_reveal_request", "post_id", post)).isEqualTo(1);
        assertThat(count("tbl_comment", "id", root)).isEqualTo(1);
        assertThat(count("tbl_like", "target_id", root)).isEqualTo(1);
    }

    @Test void writesWaitingBehindDeletionCannotLeaveOrphans() throws Exception {
        for (String kind : List.of("comment", "reply", "like", "commentLike", "reveal")) {
            UUID post = post(null);
            UUID root = comment(post, null);
            try (var executor = Executors.newSingleThreadExecutor()) {
                var pending = new java.util.concurrent.atomic.AtomicReference<Future<ErrorType>>();
                tx(() -> {
                    posts.delete(post, author);
                    pending.set(executor.submit(() -> errorOf(() -> {
                        switch (kind) {
                            case "comment" -> comment(post, null);
                            case "reply" -> comment(post, root);
                            case "like" -> likes.like(reader, EngagementTargetType.OVERTHINKING, post);
                            case "commentLike" -> likes.setCommentLike(reader, root, true);
                            default -> reveals.createRevealRequest(reader, post);
                        }
                    })));
                    awaitPostWait();
                    return null;
                });
                assertThat(pending.get().get(10, TimeUnit.SECONDS)).isEqualTo(
                        kind.equals("reveal") ? ErrorType.OVERTHINKING_POST_NOT_FOUND : ErrorType.ENGAGEMENT_NOT_FOUND);
                assertGone(post, root);
            }
        }
    }

    @Test void deletionWaitsForAlreadyAdmittedCommentAndRevealThenCleansThem() throws Exception {
        for (boolean reveal : List.of(false, true)) {
            UUID post = post(null);
            try (var executor = Executors.newSingleThreadExecutor()) {
                var pending = new java.util.concurrent.atomic.AtomicReference<Future<?>>();
                tx(() -> {
                    if (reveal) reveals.createRevealRequest(reader, post);
                    else comment(post, null);
                    pending.set(executor.submit(() -> posts.delete(post, author)));
                    awaitPostWait();
                    return null;
                });
                pending.get().get(10, TimeUnit.SECONDS);
                assertGone(post);
            }
        }
    }

    @Test void trackRemovalDetachesMusicButStaleFormCannotEditPublishedText() {
        Track track = track(TrackOwnerType.MUSICIAN_PROFILE);
        UUID post = post(track);
        trackService.deleteTrack(track.getId(), track.getOwnerId(), author, track.getOwnerType());
        assertDetached(post);
        assertError(() -> posts.update(post, author, dto(track, "new title")), ErrorType.OVERTHINKING_POST_IMMUTABLE);
        assertThat(posts.getById(post, reader).title()).isEqualTo("Title");
        assertThat(posts.getById(post, reader).musicianTrackId()).isNull();
    }

    @Test void bandBulkTrackRemovalAlsoDetachesPosts() {
        Track track = track(TrackOwnerType.BAND);
        UUID post = post(track);
        tx(() -> { tracks.deleteAllByOwnerIdAndOwnerType(track.getOwnerId(), TrackOwnerType.BAND); return null; });
        assertDetached(post);
    }

    @Test void missingReplacementCannotDiscardAnExistingLiveAttachmentOrItsText() {
        Track live = track(TrackOwnerType.MUSICIAN_PROFILE);
        Track removed = track(TrackOwnerType.MUSICIAN_PROFILE);
        UUID post = post(live);
        tx(() -> { tracks.deleteById(removed.getId()); return null; });

        assertError(() -> posts.update(post, author, dto(removed, "bad replacement")), ErrorType.OVERTHINKING_POST_IMMUTABLE);

        var unchanged = posts.getById(post, reader);
        assertThat(unchanged.title()).isEqualTo("Title");
        assertThat(unchanged.musicianTrackId()).isEqualTo(live.getId());
        assertThat(unchanged.artistId()).isEqualTo(live.getOwnerId());
    }

    @Test void alreadyDetachedPostRemainsImmutableWhenLegacyClientSubmitsMissingSource() {
        Track removed = track(TrackOwnerType.MUSICIAN_PROFILE);
        UUID post = post(null);
        tx(() -> { tracks.deleteById(removed.getId()); return null; });

        assertError(() -> posts.update(post, author, dto(removed, "saved detached")), ErrorType.OVERTHINKING_POST_IMMUTABLE);
        assertThat(posts.getById(post, reader).title()).isEqualTo("Title");
        assertDetached(post);
    }

    @Test void publishedContentIdentityMusicAndExistingConsentCannotBeRewrittenAfterEngagement() {
        Track original = track(TrackOwnerType.MUSICIAN_PROFILE);
        Track replacement = track(TrackOwnerType.MUSICIAN_PROFILE);
        UUID post = post(original), root = comment(post, null);
        likes.like(reader, EngagementTargetType.OVERTHINKING, post);
        var request = reveals.createRevealRequest(reader, post);
        reveals.approveRevealRequest(author, request.id());
        String before = jdbc.queryForObject("select row_to_json(p)::text from tbl_overthinking_post p where id=?", String.class, post);
        var changed = new OverthinkingPostSaveRequestDto("Rewritten title", "Different meaning", OverthinkingVisibilityType.VISIBLE,
                null, null, null, null, null, replacement.getId(), null);

        assertError(() -> posts.update(post, author, changed), ErrorType.OVERTHINKING_POST_IMMUTABLE);

        assertThat(jdbc.queryForObject("select row_to_json(p)::text from tbl_overthinking_post p where id=?", String.class, post)).isEqualTo(before);
        assertThat(count("tbl_comment", "id", root)).isEqualTo(1);
        assertThat(count("tbl_like", "target_id", post)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from tbl_overthinking_reveal_request where id=?", String.class, request.id())).isEqualTo("APPROVED");
    }

    @Test void spotifyOutageStillCommitsCanonicalLinkAndTextWithoutUnverifiedLocalAttribution() {
        when(spotify.getTracksByIds(any())).thenThrow(new SoundConnectException(ErrorType.SPOTIFY_RATE_LIMITED));
        var dto = new OverthinkingPostSaveRequestDto("Spotify title", "Body", OverthinkingVisibilityType.ANONYMOUS,
                "https://open.spotify.com/intl-tr/track/4uLU6hMCjMI75M1A2tKUQC?si=shared",
                "0TnOYISbd1XYRBk9myaseg", "Offline hint", "Offline artist", null, null, null);
        var created = commands.create(author, dto);
        var stored = posts.getById(created.id(), reader);
        assertThat(stored.spotifyTrackUrl()).isEqualTo("https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC");
        assertThat(stored.title()).isEqualTo("Spotify title");
        assertThat(stored.spotifyTrackName()).isEqualTo("Offline hint");
        assertThat(stored.artistId()).isNull();
        assertThat(stored.artistType()).isNull();
    }

    @Test void spotifySnapshotIsFetchedBeforeAnyDatabaseTransactionAndIsStoredForSubsequentReads() {
        String track = "4uLU6hMCjMI75M1A2tKUQC";
        var snapshot = new com.berkayb.soundconnect.modules.spotify.dto.response.SpotifyTrackItemDto(
                track,"Canonical song",10,false,null,"https://open.spotify.com/track/"+track,null,
                "https://i.scdn.co/image/canonical",List.of("Canonical artist"));
        when(spotify.getTracksByIds(List.of(track))).thenAnswer(i -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return List.of(snapshot);
        });
        var command = new OverthinkingPostSaveRequestDto("Title","Body",OverthinkingVisibilityType.ANONYMOUS,
                "https://open.spotify.com/track/"+track,null,"Hint song","Hint artist",null,null,null,UUID.randomUUID());
        var created = commands.create(author,command);
        var changedProviderSnapshot = new com.berkayb.soundconnect.modules.spotify.dto.response.SpotifyTrackItemDto(
                track,"Later provider name",10,false,null,"https://open.spotify.com/track/"+track,null,
                "https://i.scdn.co/image/later",List.of("Later artist"));
        doReturn(List.of(changedProviderSnapshot)).when(spotify).getTracksByIds(List.of(track));
        var retry = commands.create(author,command);
        assertThat(retry.id()).isEqualTo(created.id());
        assertThat(retry.spotifyTrackName()).isEqualTo("Canonical song");
        assertThat(retry.spotifyAlbumImageUrl()).isEqualTo("https://i.scdn.co/image/canonical");
        clearInvocations(spotify);
        var firstRead = posts.getById(created.id(),reader);
        assertThat(firstRead.spotifyTrackName()).isEqualTo("Canonical song");
        assertThat(firstRead.spotifyAlbumImageUrl()).isEqualTo("https://i.scdn.co/image/canonical");
        assertThat(posts.getMyPosts(author,PageRequest.of(0,20)).getContent()).hasSize(1);
        verifyNoInteractions(spotify);
    }

    @Test void failedMediaRemovalRollsBackTrackAndPostDetachmentTogether() {
        Track track = track(TrackOwnerType.MUSICIAN_PROFILE);
        UUID post = post(track);
        doThrow(new IllegalStateException("storage guard rejected")).when(media).delete(any(), any(), any(), any());
        assertThatThrownBy(() -> trackService.deleteTrack(track.getId(), track.getOwnerId(), author, track.getOwnerType()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(tracks.existsById(track.getId())).isTrue();
        assertThat(posts.getById(post, reader).musicianTrackId()).isEqualTo(track.getId());
        assertThat(posts.getById(post, reader).artistId()).isEqualTo(track.getOwnerId());
    }

    @Test void trackRemovalWaitsForAttachmentThenDetachesTheNewPost() throws Exception {
        Track track = track(TrackOwnerType.MUSICIAN_PROFILE);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var deletion = new java.util.concurrent.atomic.AtomicReference<Future<?>>();
            UUID post = tx(() -> {
                UUID id = posts.create(author, dto(track, "created during deletion")).id();
                deletion.set(executor.submit(() -> trackService.deleteTrack(track.getId(), track.getOwnerId(), author, track.getOwnerType())));
                awaitLock("tbl_media_asset");
                return id;
            });
            deletion.get().get(10, TimeUnit.SECONDS);
            assertDetached(post);
        }
    }

    @Test void publishedPostCannotBeEditedEvenBeforeAnyEngagementAndStillEnforcesOwnership() {
        UUID post = post(null);
        assertError(() -> posts.update(post, author, dto(null, "edited")), ErrorType.OVERTHINKING_POST_IMMUTABLE);
        assertError(() -> posts.update(post, reader, dto(null, "edited")), ErrorType.FORBIDDEN_ACCESS);
        assertThat(posts.getById(post, reader).title()).isEqualTo("Title");
    }

    @Test void newPostCannotAttachADeletedPrivateOrWrongKindOfTrack() {
        Track track = track(TrackOwnerType.MUSICIAN_PROFILE);
        jdbc.update("update tbl_media_asset set visibility='PRIVATE' where id=?", track.getMediaAssetId());
        assertError(() -> posts.create(author, dto(track, "private")), ErrorType.ENGAGEMENT_NOT_FOUND);
        jdbc.update("update tbl_media_asset set visibility='PUBLIC' where id=?", track.getMediaAssetId());
        var wrongType = new OverthinkingPostSaveRequestDto("wrong", "body", OverthinkingVisibilityType.ANONYMOUS,
                null, null, null, null, null, null, track.getId());
        assertError(() -> posts.create(author, wrongType), ErrorType.TRACK_OWNER_INVALID);
        tx(() -> { tracks.deleteById(track.getId()); return null; });
        assertError(() -> posts.create(author, dto(track, "deleted")), ErrorType.TRACK_NOT_FOUND);
    }

    @Test void inactiveActorCannotMutatePostsUsingOldAuthentication() {
        UUID post = post(null);
        jdbc.update("update tbl_user set status='INACTIVE' where id=?", author);
        assertError(() -> posts.create(author, dto(null, "new")), ErrorType.UNAUTHORIZED);
        assertError(() -> posts.update(post, author, dto(null, "edited")), ErrorType.UNAUTHORIZED);
        assertError(() -> posts.delete(post, author), ErrorType.UNAUTHORIZED);
        assertThat(count("tbl_overthinking_post", "id", post)).isEqualTo(1);
    }

    @Test void migrationRepairsLegacyOrphansAndIsRepeatableWithoutChangingValidAttachments() throws Exception {
        Track validTrack = track(TrackOwnerType.MUSICIAN_PROFILE);
        UUID validPost = post(validTrack), damagedPost = post(null), missingPost = UUID.randomUUID();
        UUID orphanRoot = UUID.randomUUID(), orphanReply = UUID.randomUUID();
        jdbc.execute("alter table tbl_overthinking_post drop constraint fk_overthinking_musician_track");
        jdbc.update("update tbl_overthinking_post set musician_track_id=?,artist_id=?,artist_type='MUSICIAN_PROFILE' where id=?",
                UUID.randomUUID(), UUID.randomUUID(), damagedPost);
        for (UUID id : List.of(orphanRoot, orphanReply)) {
            jdbc.update("insert into tbl_comment(id,created_at,updated_at,user_id,target_type,target_id,text,is_deleted,parent_comment_id) "
                            + "values(?,current_timestamp,current_timestamp,?,'OVERTHINKING',?,'orphan',false,?)",
                    id, reader, missingPost, id.equals(orphanRoot) ? null : orphanRoot);
            jdbc.update("insert into tbl_like(id,created_at,updated_at,user_id,target_type,target_id) "
                    + "values(?,current_timestamp,current_timestamp,?,'COMMENT',?)", UUID.randomUUID(), reader, id);
        }
        jdbc.update("insert into tbl_like(id,created_at,updated_at,user_id,target_type,target_id) "
                + "values(?,current_timestamp,current_timestamp,?,'OVERTHINKING',?)", UUID.randomUUID(), reader, missingPost);
        applyMigration();
        applyMigration();
        assertThat(jdbc.queryForObject("select count(*) from soundconnect_schema_migrations where migration_id=?", Long.class,
                "2026-09-09-overthinking-lifecycle")).isEqualTo(1L);
        assertGone(missingPost, orphanRoot, orphanReply);
        assertDetached(damagedPost);
        assertThat(posts.getById(validPost, reader).musicianTrackId()).isEqualTo(validTrack.getId());
        assertThatThrownBy(() -> jdbc.update("update tbl_overthinking_post set musician_track_id=? where id=?",
                UUID.randomUUID(), validPost)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test void migrationClearsLegacySpotifyAccountAttributionOnceButKeepsMusicAndSubsequentVerifiedBinding() throws Exception {
        UUID post = post(null), artist = UUID.randomUUID();
        jdbc.update("delete from soundconnect_schema_migrations where migration_id=?", "2026-09-09-overthinking-lifecycle");
        jdbc.update("update tbl_overthinking_post set spotify_track_url=?,spotify_track_name=?,spotify_artist_id=?,artist_id=?,artist_type='BAND' where id=?",
                "https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC", "Preserved song", "0TnOYISbd1XYRBk9myaseg", artist, post);
        applyMigration();
        var cleared = posts.getById(post, reader);
        assertThat(cleared.artistId()).isNull();
        assertThat(cleared.artistType()).isNull();
        assertThat(cleared.spotifyArtistId()).isEqualTo("0TnOYISbd1XYRBk9myaseg");
        assertThat(cleared.spotifyTrackName()).isEqualTo("Preserved song");
        assertThat(cleared.spotifyTrackUrl()).isNotNull();
        jdbc.update("update tbl_overthinking_post set artist_id=?,artist_type='BAND' where id=?", artist, post);
        applyMigration();
        assertThat(posts.getById(post, reader).artistId()).isEqualTo(artist);
    }

    @Test void pendingStateIsPersistentViewerScopedAndClearsOnWithdrawalOrApproval() {
        UUID post = post(null), outsider = tx(this::user);
        assertThat(posts.getById(post, reader).revealRequestPending()).isFalse();
        var request = reveals.createRevealRequest(reader, post);
        assertThat(reveals.createRevealRequest(reader, post).id()).isEqualTo(request.id());
        assertThat(posts.getById(post, reader).revealRequestPending()).isTrue();
        assertThat(posts.getAll(reader, PageRequest.of(0, 1000), OverthinkingFeedOrder.NEWEST)
                .stream().filter(item -> item.id().equals(post)).findFirst().orElseThrow().revealRequestPending()).isTrue();
        for (UUID viewer : Arrays.asList(null, author, outsider)) {
            assertThat(posts.getById(post, viewer).revealRequestPending()).isFalse();
        }
        assertThat(posts.getById(post, reader).authorId()).isNull();
        reveals.cancelRevealRequest(reader, post);
        assertThat(posts.getById(post, reader).revealRequestPending()).isFalse();
        var replacement = reveals.createRevealRequest(reader, post);
        assertThat(replacement.id()).isNotEqualTo(request.id());
        reveals.approveRevealRequest(author, replacement.id());
        var approved = posts.getById(post, reader);
        assertThat(approved.revealRequestPending()).isFalse();
        assertThat(approved.canViewAuthor()).isTrue();
        assertThat(approved.authorId()).isEqualTo(author);
        assertThat(posts.getById(post, outsider).authorId()).isNull();
    }

    @Test void feedOrderUsesGlobalLikeRankingStableTiesAndKeepsZeroLikePostsAcrossPages() {
        UUID oldestA = post(null), oldestB = post(null), newest = post(null), popular = post(null);
        jdbc.update("update tbl_overthinking_post set created_at='1900-01-01 00:00:00' where id in (?,?)", oldestA, oldestB);
        jdbc.update("update tbl_overthinking_post set created_at='3000-01-01 00:00:00' where id=?", newest);
        jdbc.update("update tbl_overthinking_post set created_at='2999-01-01 00:00:00' where id=?", popular);
        for (int index = 0; index < 3; index++) {
            likes.like(tx(this::user), EngagementTargetType.OVERTHINKING, popular);
        }
        var adversarialPage = PageRequest.of(0, 2, Sort.by("title"));
        var latest = posts.getAll(reader, adversarialPage, OverthinkingFeedOrder.NEWEST);
        assertThat(latest.getContent()).extracting(item -> item.id()).containsExactly(newest, popular);
        var expectedOldest = List.of(oldestA, oldestB).stream().sorted(Comparator.comparing(UUID::toString)).toList();
        assertThat(posts.getAll(reader, adversarialPage, OverthinkingFeedOrder.OLDEST).getContent())
                .extracting(item -> item.id()).containsExactlyElementsOf(expectedOldest);
        var mostLiked = posts.getAll(reader, adversarialPage, OverthinkingFeedOrder.MOST_LIKED);
        assertThat(mostLiked.getContent().getFirst().id()).isEqualTo(popular);
        assertThat(mostLiked.getContent().getFirst().likeCount()).isEqualTo(3);
        long total = jdbc.queryForObject("select count(*) from tbl_overthinking_post", Long.class);
        assertThat(mostLiked.getTotalElements()).isEqualTo(total);
        var pagedIds = new ArrayList<UUID>();
        for (int page = 0; page < mostLiked.getTotalPages(); page++) {
            posts.getAll(reader, PageRequest.of(page, 2, Sort.by("content")), OverthinkingFeedOrder.MOST_LIKED)
                    .forEach(item -> pagedIds.add(item.id()));
        }
        assertThat(pagedIds).hasSize((int) total).doesNotHaveDuplicates().contains(oldestA, oldestB, newest, popular);
        assertThat(pagedIds.indexOf(newest)).isLessThan(pagedIds.indexOf(oldestA));
        assertThat(pagedIds.indexOf(newest)).isLessThan(pagedIds.indexOf(oldestB));
        var expectedOldestDescending = new ArrayList<>(expectedOldest);
        Collections.reverse(expectedOldestDescending);
        assertThat(pagedIds.subList(pagedIds.size() - 2, pagedIds.size())).containsExactlyElementsOf(expectedOldestDescending);
    }

    @Test void listenerSharesOwnAndOtherSourcesIdempotentlyWithoutEditingPublicationNote() {
        var listener = listener(); UUID source = post(null);
        assertThat(shares.get(listener.owner, source).canPublish()).isTrue();
        var first = shares.publish(listener.owner, source, new OverthinkingProfileShareUpdate("  First note  "));
        var duplicate = shares.publish(listener.owner, source, new OverthinkingProfileShareUpdate("First note"));
        assertThat(duplicate).isEqualTo(first);
        assertThat(first.note()).isEqualTo("First note");
        assertError(() -> shares.publish(listener.owner, source, new OverthinkingProfileShareUpdate("Edited meaning")),
                ErrorType.OVERTHINKING_PROFILE_SHARE_ALREADY_EXISTS);
        assertThat(shares.get(listener.owner, source)).isEqualTo(first);
        UUID ownSource = posts.create(listener.owner, dto(null, "My source")).id();
        assertThat(shares.publish(listener.owner, ownSource, new OverthinkingProfileShareUpdate(" \n\t ")).note()).isNull();
        assertThat(shares.list(reader, listener.profile, 0, 20).content()).hasSize(2);
        assertThat(posts.getById(source, reader).content()).isEqualTo("Body");
    }

    @Test void concurrentIdenticalPublicationRetriesShareTheSamePersistedIdentityAndTimestamp() throws Exception {
        var target = listener(); UUID source = post(null);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var pending = new java.util.concurrent.atomic.AtomicReference<Future<OverthinkingProfileShareResponse.State>>();
            var first = tx(() -> {
                var created = shares.publish(target.owner, source, new OverthinkingProfileShareUpdate("Same note"));
                pending.set(pool.submit(() -> shares.publish(target.owner, source, new OverthinkingProfileShareUpdate("Same note"))));
                awaitLock("tbl_user"); return created;
            });
            assertThat(pending.get().get(10, TimeUnit.SECONDS)).isEqualTo(first);
            assertThat(shares.get(target.owner, source)).isEqualTo(first);
            assertThat(count("tbl_overthinking_profile_share", "source_post_id", source)).isEqualTo(1);
        }
    }

    @Test void profileShareRequiresActualSoleListenerRoleAndProfileAndVerifiedActiveAccount() {
        UUID source = post(null);
        for (String additional : List.of("ROLE_ADMIN", "ROLE_OWNER", "ROLE_MUSICIAN", "ROLE_STUDIO", "ROLE_PRODUCER", "ROLE_ORGANIZER", "ROLE_VENUE")) {
            var target = listener(); tx(() -> { addRole(em.find(User.class, target.owner), additional); return null; });
            assertError(() -> shares.publish(target.owner, source, new OverthinkingProfileShareUpdate(null)), ErrorType.FORBIDDEN_ACCESS);
            assertError(() -> shares.get(target.owner, source), ErrorType.FORBIDDEN_ACCESS);
            assertError(() -> shares.list(reader, target.profile, 0, 20), ErrorType.PROFILE_NOT_FOUND);
        }
        var hiddenSecondary = listener();
        tx(() -> persist(MusicianProfile.builder().user(em.getReference(User.class, hiddenSecondary.owner)).build()));
        assertError(() -> shares.publish(hiddenSecondary.owner, source, new OverthinkingProfileShareUpdate(null)), ErrorType.FORBIDDEN_ACCESS);
        var missingRole = listener();
        tx(() -> { em.find(User.class, missingRole.owner).getRoles().clear(); return null; });
        assertError(() -> shares.publish(missingRole.owner, source, new OverthinkingProfileShareUpdate(null)), ErrorType.FORBIDDEN_ACCESS);
        UUID noProfile = tx(() -> { UUID id = user(); addRole(em.find(User.class, id), "ROLE_LISTENER"); return id; });
        assertError(() -> shares.publish(noProfile, source, new OverthinkingProfileShareUpdate(null)), ErrorType.FORBIDDEN_ACCESS);
        for (boolean unverified : List.of(true, false)) {
            var target = listener();
            jdbc.update(unverified ? "update tbl_user set email_verified=false where id=?" : "update tbl_user set status='INACTIVE' where id=?", target.owner);
            assertError(() -> shares.publish(target.owner, source, new OverthinkingProfileShareUpdate(null)), ErrorType.UNAUTHORIZED);
        }
        assertError(() -> shares.publish(null, source, new OverthinkingProfileShareUpdate(null)), ErrorType.UNAUTHORIZED);
        assertError(() -> shares.list(null, listener().profile, 0, 20), ErrorType.UNAUTHORIZED);
    }

    @Test void ghostSharesAreHiddenAndCannotPublishButCanRemoveExactExistingPublication() {
        var target = listener(); UUID source = post(null);
        var publication = shares.publish(target.owner, source, new OverthinkingProfileShareUpdate(null));
        setListenerVisibility(target, true, true);
        assertThat(shares.get(target.owner, source).canPublish()).isFalse();
        assertThat(shares.get(target.owner, source).publishedOnProfile()).isTrue();
        assertThat(shares.list(reader, target.profile, 0, 20).content()).isEmpty();
        assertError(() -> shares.publish(target.owner, source, new OverthinkingProfileShareUpdate(null)), ErrorType.FORBIDDEN_ACCESS);
        setListenerVisibility(target, false, true);
        assertThat(shares.list(reader, target.profile, 0, 20).content()).hasSize(1);
        setListenerVisibility(target, false, false);
        assertError(() -> shares.list(reader, target.profile, 0, 20), ErrorType.PROFILE_NOT_FOUND);
        assertError(() -> shares.publish(target.owner, post(null), new OverthinkingProfileShareUpdate(null)), ErrorType.FORBIDDEN_ACCESS);
        setListenerVisibility(target, true, true);
        shares.delete(target.owner, publication.shareId());
        assertThat(shares.get(target.owner, source).publishedOnProfile()).isFalse();
        assertThat(posts.getById(source, reader).content()).isEqualTo("Body");
    }

    @Test void nestedShareProjectionUsesEachViewerCurrentConsentRatherThanSharerIdentitySnapshot() {
        var target = listener(); UUID source = post(null);
        var permission = reveals.createRevealRequest(target.owner, source);
        reveals.approveRevealRequest(author, permission.id());
        shares.publish(target.owner, source, new OverthinkingProfileShareUpdate("A note"));
        assertThat(shares.list(target.owner, target.profile, 0, 20).content().getFirst().post().authorId()).isEqualTo(author);
        assertThat(shares.list(reader, target.profile, 0, 20).content().getFirst().post().authorId()).isNull();
        var pending = reveals.createRevealRequest(reader, source);
        assertThat(shares.list(reader, target.profile, 0, 20).content().getFirst().post().revealRequestPending()).isTrue();
        reveals.cancelRevealRequest(reader, source);
        var cancelled = shares.list(reader, target.profile, 0, 20).content().getFirst().post();
        assertThat(cancelled.revealRequestPending()).isFalse();
        assertThat(cancelled.authorId()).isNull();
        var renewed = reveals.createRevealRequest(reader, source);
        assertThat(renewed.id()).isNotEqualTo(pending.id());
        reveals.approveRevealRequest(author, renewed.id());
        assertThat(shares.list(reader, target.profile, 0, 20).content().getFirst().post().authorId()).isEqualTo(author);
        assertThat(shares.list(author, target.profile, 0, 20).content().getFirst().post().authorId()).isEqualTo(author);
    }

    @Test void deletingExactShareNeverDeletesSourceOrAnotherOwnersOrLaterPublication() {
        var target = listener(); var other = listener(); UUID source = post(null);
        var first = shares.publish(target.owner, source, new OverthinkingProfileShareUpdate(null));
        var others = shares.publish(other.owner, source, new OverthinkingProfileShareUpdate(null));
        assertError(() -> shares.delete(other.owner, first.shareId()), ErrorType.OVERTHINKING_PROFILE_SHARE_NOT_FOUND);
        shares.delete(target.owner, first.shareId());
        var second = shares.publish(target.owner, source, new OverthinkingProfileShareUpdate("Another note"));
        assertThat(second.shareId()).isNotEqualTo(first.shareId());
        assertError(() -> shares.delete(target.owner, first.shareId()), ErrorType.OVERTHINKING_PROFILE_SHARE_NOT_FOUND);
        assertThat(shares.get(target.owner, source).shareId()).isEqualTo(second.shareId());
        assertThat(shares.get(other.owner, source).shareId()).isEqualTo(others.shareId());
        assertThat(posts.getById(source, reader).content()).isEqualTo("Body");
        posts.delete(source, author);
        assertThat(shareRepository.existsById(second.shareId())).isFalse();
        assertThat(shareRepository.existsById(others.shareId())).isFalse();
        assertThat(shares.list(reader, target.profile, 0, 20).content()).isEmpty();
    }

    @Test void sharePagingHasStableTieBreakerAndUsesStoredSnapshotsWithoutSpotifyReads() {
        var target = listener(); var publicationIds = new ArrayList<UUID>();
        for (char track : List.of('A', 'B', 'C')) {
            UUID source = post(null);
            jdbc.update("update tbl_overthinking_post set spotify_track_url=? where id=?", "https://open.spotify.com/track/" + String.valueOf(track).repeat(22), source);
            publicationIds.add(shares.publish(target.owner, source, new OverthinkingProfileShareUpdate(null)).shareId());
        }
        jdbc.update("update tbl_overthinking_profile_share set published_at='2026-09-10 00:00:00+00' where owner_user_id=?", target.owner);
        clearInvocations(spotify);
        var first = shares.list(reader, target.profile, 0, 2);
        verifyNoInteractions(spotify);
        var second = shares.list(reader, target.profile, 1, 2);
        assertThat(first.totalElements()).isEqualTo(3);
        assertThat(first.totalPages()).isEqualTo(2);
        var actual = new ArrayList<UUID>(); first.content().forEach(value -> actual.add(value.shareId())); second.content().forEach(value -> actual.add(value.shareId()));
        assertThat(actual).containsExactlyElementsOf(publicationIds.stream().sorted(Comparator.comparing(UUID::toString).reversed()).toList());
        for (int[] invalid : List.of(new int[]{-1, 20}, new int[]{1001, 20}, new int[]{0, 0}, new int[]{0, 51})) {
            assertError(() -> shares.list(reader, target.profile, invalid[0], invalid[1]), ErrorType.OVERTHINKING_PROFILE_SHARE_INVALID);
        }
    }

    @Test void shareNoteValidationAndMigrationRerunPreserveStoredRowsAndCascadeProfileRemoval() throws Exception {
        var target = listener(); UUID source = post(null);
        for (String note : List.of("a".repeat(501), "bad\u0001text", "bad\ud800text")) {
            assertError(() -> shares.publish(target.owner, source, new OverthinkingProfileShareUpdate(note)), ErrorType.OVERTHINKING_PROFILE_SHARE_INVALID);
        }
        var value = shares.publish(target.owner, source, new OverthinkingProfileShareUpdate("🙂".repeat(500)));
        String before = jdbc.queryForObject("select to_jsonb(s)::text from tbl_overthinking_profile_share s where id=?", String.class, value.shareId());
        String original = jdbc.queryForObject("select to_jsonb(p)::text from tbl_overthinking_post p where id=?", String.class, source);
        applyMigration();
        assertThat(jdbc.queryForObject("select to_jsonb(s)::text from tbl_overthinking_profile_share s where id=?", String.class, value.shareId())).isEqualTo(before);
        assertThat(jdbc.queryForObject("select to_jsonb(p)::text from tbl_overthinking_post p where id=?", String.class, source)).isEqualTo(original);
        assertThat(jdbc.queryForObject("select count(*) from soundconnect_schema_migrations where migration_id='2026-09-10-overthinking-profile-shares'", Integer.class)).isEqualTo(1);
        jdbc.update("delete from \"tbl_listener-profile\" where id=?", target.profile);
        assertThat(shareRepository.existsById(value.shareId())).isFalse();
        assertThat(posts.getById(source, reader).content()).isEqualTo("Body");
    }

    @Test void publicationAndSourceDeletionSerializeWithoutOrphansInEitherOrder() throws Exception {
        var target = listener();
        for (boolean publishFirst : List.of(true, false)) {
            UUID source = post(null);
            try (var pool = Executors.newSingleThreadExecutor()) {
                var pending = new java.util.concurrent.atomic.AtomicReference<Future<ErrorType>>();
                tx(() -> {
                    if (publishFirst) shares.publish(target.owner, source, new OverthinkingProfileShareUpdate(null));
                    else posts.delete(source, author);
                    pending.set(pool.submit(() -> errorOf(() -> {
                        if (publishFirst) posts.delete(source, author);
                        else shares.publish(target.owner, source, new OverthinkingProfileShareUpdate(null));
                    })));
                    awaitPostWait(); return null;
                });
                assertThat(pending.get().get(10, TimeUnit.SECONDS)).isEqualTo(publishFirst ? null : ErrorType.OVERTHINKING_POST_NOT_FOUND);
                assertThat(count("tbl_overthinking_profile_share", "source_post_id", source)).isZero();
            }
        }
    }

    @Test void ghostTransitionWaitsForPublicationAndPreventsWaitingPublicationAfterItCommits() throws Exception {
        for (boolean publishFirst : List.of(true, false)) {
            var target = listener(); UUID source = post(null);
            try (var pool = Executors.newSingleThreadExecutor()) {
                var pending = new java.util.concurrent.atomic.AtomicReference<Future<ErrorType>>();
                tx(() -> {
                    if (publishFirst) shares.publish(target.owner, source, new OverthinkingProfileShareUpdate(null));
                    else listenerProfiles.findByUserIdForUpdate(target.owner).orElseThrow().setVisibilityMode(ListenerVisibilityMode.GHOST);
                    pending.set(pool.submit(() -> errorOf(() -> {
                        if (publishFirst) tx(() -> { listenerProfiles.findByUserIdForUpdate(target.owner).orElseThrow().setVisibilityMode(ListenerVisibilityMode.GHOST); return null; });
                        else shares.publish(target.owner, source, new OverthinkingProfileShareUpdate(null));
                    })));
                    awaitLock("tbl_listener-profile"); return null;
                });
                assertThat(pending.get().get(10, TimeUnit.SECONDS)).isEqualTo(publishFirst ? null : ErrorType.FORBIDDEN_ACCESS);
                assertThat(shares.list(reader, target.profile, 0, 20).content()).isEmpty();
                assertThat(shares.get(target.owner, source).publishedOnProfile()).isEqualTo(publishFirst);
            }
        }
    }

    @Test void privateAuthorSortCannotLinkAnonymousRowsToVisibleAuthorAnchors() {
        UUID artistId = UUID.randomUUID();
        tx(() -> {
            for (UUID owner : List.of(author,reader)) for (var visibility : OverthinkingVisibilityType.values())
                persist(OverthinkingPost.builder().author(em.getReference(User.class,owner)).title("Ordinary post").content("Body")
                        .artistId(artistId).artistType(OverthinkingArtistType.MUSICIAN_PROFILE).visibilityType(visibility).build());
            return null;
        });
        var first = posts.getPostsByArtist(artistId,null,PageRequest.of(0,20,Sort.by(
                Sort.Order.asc("author.id"),Sort.Order.desc("visibilityType")))).getContent();
        var second = posts.getPostsByArtist(artistId,null,PageRequest.of(0,20,Sort.by(
                Sort.Order.asc("author.id"),Sort.Order.asc("visibilityType")))).getContent();
        var email = posts.getPostsByArtist(artistId,null,PageRequest.of(0,20,Sort.by("author.email"))).getContent();
        assertThat(first).extracting("id").containsExactlyElementsOf(second.stream().map(p -> p.id()).toList());
        assertThat(first).extracting("id").containsExactlyElementsOf(email.stream().map(p -> p.id()).toList());
        assertThat(first.stream().filter(p -> p.anonymous())).allSatisfy(p -> assertThat(p.authorId()).isNull());
    }

    @Test void creationReceiptReplaysSamePostRejectsChangedPayloadAndNeverResurrectsDeletedPost() {
        UUID key = UUID.randomUUID(); var request = keyed(key,"Original");
        var first = posts.create(author,request);
        var replay = posts.create(author,request);
        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(jdbc.queryForObject("select count(*) from tbl_overthinking_post where author_id=?",Integer.class,author)).isEqualTo(1);
        assertError(() -> posts.create(author,keyed(key,"Changed")),ErrorType.OVERTHINKING_CREATE_KEY_CONFLICT);
        assertThat(posts.create(reader,request).id()).isNotEqualTo(first.id()); // Key namespace belongs to the actor.
        posts.delete(first.id(),author);
        assertError(() -> posts.create(author,request),ErrorType.OVERTHINKING_CREATE_ALREADY_DELETED);
        assertThat(count("tbl_overthinking_post","id",first.id())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from tbl_overthinking_create_receipt where owner_user_id=?",Integer.class,author)).isEqualTo(1);
    }

    @Test void concurrentSameKeyCreatesOnePostAndOneReceiptAcrossSeparateTransactions() throws Exception {
        var request = keyed(UUID.randomUUID(),"Concurrent");
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            Callable<UUID> create = () -> { start.await(); return posts.create(author,request).id(); };
            var first = pool.submit(create); var second = pool.submit(create); start.countDown();
            assertThat(first.get(10,TimeUnit.SECONDS)).isEqualTo(second.get(10,TimeUnit.SECONDS));
        }
        assertThat(jdbc.queryForObject("select count(*) from tbl_overthinking_post where author_id=?",Integer.class,author)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from tbl_overthinking_create_receipt where owner_user_id=?",Integer.class,author)).isEqualTo(1);
    }

    @Test void rolledBackCreationDoesNotReserveItsLogicalRequest() {
        var request = keyed(UUID.randomUUID(),"Rollback");
        assertThatThrownBy(() -> tx(() -> { posts.create(author,request); throw new IllegalStateException("rollback"); }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("select count(*) from tbl_overthinking_create_receipt where owner_user_id=?",Integer.class,author)).isZero();
        var retry = posts.create(author,request);
        assertThat(posts.create(author,request).id()).isEqualTo(retry.id());
    }

    @Test void legacyArtworkIsSanitizedOnEveryReadAndMigrationScrubsRetainedSnapshot() throws Exception {
        UUID id = post(null);
        jdbc.update("update tbl_overthinking_post set spotify_track_url=?,spotify_track_name='Stored',spotify_album_image_url=? where id=?",
                "https://open.spotify.com/track/0000000000000000000000","https://tracker.example/per-reader-pixel",id);
        clearInvocations(spotify);
        assertThat(posts.getById(id,reader).spotifyAlbumImageUrl()).isNull();
        assertThat(posts.getAll(reader,PageRequest.of(0,20)).stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow().spotifyAlbumImageUrl()).isNull();
        verifyNoInteractions(spotify);
        applyMigration();
        assertThat(jdbc.queryForObject("select spotify_album_image_url from tbl_overthinking_post where id=?",String.class,id)).isNull();
    }

    @Test void withdrawalAndSourceDeletionCannotResetRevealCooldownAndSamePendingRetryDoesNotConsumeQuota() {
        useRealRateGuard(); UUID source = post(null);
        var request = reveals.createRevealRequest(reader,source);
        assertThat(reveals.createRevealRequest(reader,source).id()).isEqualTo(request.id());
        assertThat(count("tbl_overthinking_reveal_attempt","requester_id",reader)).isEqualTo(1);
        reveals.cancelRevealRequest(reader,source);
        assertThatThrownBy(() -> reveals.createRevealRequest(reader,source)).isInstanceOfSatisfying(RateLimitedException.class,
                error -> { assertThat(error.getErrorType()).isEqualTo(ErrorType.OVERTHINKING_REVEAL_RATE_LIMITED);
                    assertThat(error.getRetryAfterSeconds()).isBetween(1L,60L); });
        UUID otherAuthor = tx(this::user);
        UUID otherAuthorPost = posts.create(otherAuthor,dto(null,"Another author")).id();
        // A hidden author's identity must not change the observable quota response.
        assertError(() -> reveals.createRevealRequest(reader,otherAuthorPost),ErrorType.OVERTHINKING_REVEAL_RATE_LIMITED);
        posts.delete(source,author); UUID replacement = post(null);
        assertError(() -> reveals.createRevealRequest(reader,replacement),ErrorType.OVERTHINKING_REVEAL_RATE_LIMITED);
        jdbc.update("update tbl_overthinking_reveal_attempt set created_at=clock_timestamp()-interval '61 seconds' where requester_id=?",reader);
        assertThat(reveals.createRevealRequest(reader,replacement).id()).isNotNull();
    }

    @Test void concurrentRevealRequestsAcrossAuthorsUseOneSharedQuotaAndRejectedTransactionsDoNotConsumeIt() throws Exception {
        useRealRateGuard(); UUID firstSource = post(null);
        UUID secondSource = posts.create(tx(this::user),dto(null,"Another author")).id();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var first = pool.submit(() -> { start.await(); return errorOf(() -> reveals.createRevealRequest(reader,firstSource)); });
            var second = pool.submit(() -> { start.await(); return errorOf(() -> reveals.createRevealRequest(reader,secondSource)); });
            start.countDown();
            assertThat(Arrays.asList(first.get(10,TimeUnit.SECONDS),second.get(10,TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(null,ErrorType.OVERTHINKING_REVEAL_RATE_LIMITED);
        }
        assertThat(count("tbl_overthinking_reveal_attempt","requester_id",reader)).isEqualTo(1);
        jdbc.update("delete from tbl_overthinking_reveal_attempt where requester_id=?",reader);
        doThrow(new IllegalStateException("outbox failure")).when(notifications).sendRevealRequestReceivedNotification(any());
        UUID source = post(null);
        assertThatThrownBy(() -> reveals.createRevealRequest(reader,source)).isInstanceOf(IllegalStateException.class);
        assertThat(count("tbl_overthinking_reveal_attempt","requester_id",reader)).isZero();
    }

    private void useRealRateGuard() {
        var real = new OverthinkingRevealRateGuard(jdbc);
        doAnswer(i -> { real.lockRequester(i.getArgument(0)); return null; }).when(rateGuard).lockRequester(any());
        doAnswer(i -> { real.reserve(i.getArgument(0),i.getArgument(1)); return null; }).when(rateGuard).reserve(any(),any());
    }
    private OverthinkingPostSaveRequestDto keyed(UUID key,String title) {
        return new OverthinkingPostSaveRequestDto(title,"Body",OverthinkingVisibilityType.ANONYMOUS,null,null,null,null,null,null,null,key);
    }

    private ListenerFixture listener() {
        return tx(() -> {
            UUID owner = user(); var user = em.find(User.class, owner); addRole(user, "ROLE_LISTENER");
            UUID profile = persist(ListenerProfile.builder().user(user).visibilityChoiceCompleted(true).build()).getId();
            return new ListenerFixture(owner, profile);
        });
    }
    private void addRole(User user, String roleName) {
        var found = em.createQuery("select role from Role role where role.name=:name", Role.class).setParameter("name", roleName).getResultList();
        user.getRoles().add(found.isEmpty() ? persist(Role.builder().name(roleName).build()) : found.getFirst());
    }
    private void setListenerVisibility(ListenerFixture target, boolean ghost, boolean completed) {
        tx(() -> { var profile = listenerProfiles.findByUserIdForUpdate(target.owner).orElseThrow();
            profile.setVisibilityMode(ghost ? ListenerVisibilityMode.GHOST : ListenerVisibilityMode.STANDARD);
            profile.setVisibilityChoiceCompleted(completed); return null; });
    }
    private record ListenerFixture(UUID owner, UUID profile) { }

    private UUID post(Track track) { return posts.create(author, dto(track, "Title")).id(); }
    private OverthinkingPostSaveRequestDto dto(Track track, String title) {
        return new OverthinkingPostSaveRequestDto(title, "Body", OverthinkingVisibilityType.ANONYMOUS,
                null, null, null, null, null,
                track != null && track.getOwnerType() == TrackOwnerType.MUSICIAN_PROFILE ? track.getId() : null,
                track != null && track.getOwnerType() == TrackOwnerType.BAND ? track.getId() : null);
    }
    private Track track(TrackOwnerType ownerType) {
        return tx(() -> {
            UUID owner = UUID.randomUUID();
            var asset = persist(MediaAsset.builder().kind(MediaKind.AUDIO).status(MediaStatus.READY).visibility(MediaVisibility.PUBLIC)
                    .ownerType(ownerType == TrackOwnerType.BAND ? MediaOwnerType.BAND : MediaOwnerType.MUSICIAN_PROFILE)
                    .ownerId(owner).size(100L).mimeType("audio/mpeg").sourceUrl("https://test.invalid/audio").build());
            return persist(Track.builder().title("Track").ownerType(ownerType).ownerId(owner).mediaAssetId(asset.getId()).build());
        });
    }
    private UUID user() {
        return persist(User.builder().username("u" + UUID.randomUUID().toString().replace("-", "").substring(0, 16))
                .email(UUID.randomUUID() + "@test.invalid").password("unused").status(UserStatus.ACTIVE).emailVerified(true).build()).getId();
    }
    private UUID comment(UUID post, UUID parent) {
        return comments.createComment(reader, EngagementTargetType.OVERTHINKING, post, new CommentCreateRequestDto("Comment", parent)).id();
    }
    private void assertDetached(UUID post) {
        var actual = posts.getById(post, reader);
        assertThat(actual.musicianTrackId()).isNull();
        assertThat(actual.bandTrackId()).isNull();
        assertThat(actual.artistId()).isNull();
        assertThat(actual.artistType()).isNull();
        assertThat(actual.title()).isNotBlank();
        assertThat(actual.content()).isEqualTo("Body");
    }
    private void assertGone(UUID post, UUID... commentIds) {
        assertThat(count("tbl_overthinking_post", "id", post)).isZero();
        assertThat(count("tbl_overthinking_reveal_request", "post_id", post)).isZero();
        assertThat(count("tbl_comment", "target_id", post)).isZero();
        assertThat(count("tbl_like", "target_id", post)).isZero();
        for (UUID id : commentIds) assertThat(count("tbl_like", "target_id", id)).isZero();
    }
    private long count(String table, String column, UUID id) {
        return jdbc.queryForObject("select count(*) from " + table + " where " + column + "=?", Long.class, id);
    }
    private void awaitPostWait() { awaitLock("tbl_overthinking_post"); }
    private void awaitLock(String table) {
        await().atMost(Duration.ofSeconds(5)).until(() -> jdbc.queryForObject(
                "select count(*) from pg_stat_activity where datname=current_database() and wait_event_type='Lock' and query like ?",
                Long.class, "%" + table + "%") > 0);
    }
    private ErrorType errorOf(Runnable operation) {
        try { operation.run(); return null; } catch (SoundConnectException failure) { return failure.getErrorType(); }
    }
    private void assertError(Runnable action, ErrorType type) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(SoundConnectException.class,
                failure -> assertThat(failure.getErrorType()).isEqualTo(type));
    }
    private <T> T persist(T entity) { em.persist(entity); return entity; }
    private <T> T tx(Supplier<T> action) { return new TransactionTemplate(manager).execute(status -> action.get()); }

    @Configuration(proxyBeanMethods = false)
    @EnableJpaRepositories(basePackageClasses = {OverthinkingPostRepository.class, CommentRepository.class, LikeRepository.class,
            TrackRepository.class, MediaAssetRepository.class, MusicianProfileRepository.class, BandRepository.class, StudioProfileRepository.class,
            UserRepository.class, ListenerProfileRepository.class, OverthinkingProfileShareRepository.class})
    @EntityScan(basePackages = "com.berkayb.soundconnect")
    @Import({JpaAuditingConfig.class, OverthinkingPostServiceImpl.class, OverthinkingPostCreationReceipts.class, OverthinkingPostCommandService.class,
            OverthinkingRevealRequestServiceImpl.class, OverthinkingRevealParticipantGuard.class,
            OverthinkingArtistResolverServiceImpl.class, TrackServiceImpl.class, CommentServiceImpl.class,
            CommentEntityFinder.class, CommentTargetAccessGuard.class, LikeServiceImpl.class,
            CommentLikeAccessGuard.class, EngagementTargetValidatorImpl.class, OverthinkingProfileShareService.class, ListenerVisibilityPolicy.class})
    static class Config {
        @Bean DataSource dataSource() {
            if (!POSTGRES.isRunning()) throw new IllegalStateException("Disposable PostgreSQL must be running");
            return new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        }
        @Bean CommentMapper commentMapper() { return Mappers.getMapper(CommentMapper.class); }
        @Bean OverthinkingPostMapper postMapper() { return Mappers.getMapper(OverthinkingPostMapper.class); }
        @Bean OverthinkingRevealRequestMapper revealMapper() { return Mappers.getMapper(OverthinkingRevealRequestMapper.class); }
    }
}

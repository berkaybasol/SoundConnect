package com.berkayb.soundconnect.modules.promotion.announcement;

import com.berkayb.soundconnect.modules.analytics.AnnouncementAnalyticsStore;
import com.berkayb.soundconnect.modules.analytics.AnalyticsIdentity;
import com.berkayb.soundconnect.modules.comment.abuse.CommentBurstGuard;
import com.berkayb.soundconnect.modules.comment.dto.request.CommentCreateRequestDto;
import com.berkayb.soundconnect.modules.comment.mapper.CommentMapper;
import com.berkayb.soundconnect.modules.comment.repository.CommentRepository;
import com.berkayb.soundconnect.modules.comment.service.CommentServiceImpl;
import com.berkayb.soundconnect.modules.comment.support.*;
import com.berkayb.soundconnect.modules.engagement.enums.EngagementTargetType;
import com.berkayb.soundconnect.modules.engagement.service.*;
import com.berkayb.soundconnect.modules.like.repository.*;
import com.berkayb.soundconnect.modules.like.service.*;
import com.berkayb.soundconnect.modules.media.entity.MediaAsset;
import com.berkayb.soundconnect.modules.media.enums.*;
import com.berkayb.soundconnect.modules.media.repository.MediaAssetRepository;
import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.media.transcode.MediaAssetStatusUpdater;
import com.berkayb.soundconnect.modules.media.transcode.config.MediaTranscodeLeaseProperties;
import com.berkayb.soundconnect.modules.overthinking.repository.OverthinkingPostRepository;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.entity.ListenerProfile;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.entity.MusicianProfile;
import com.berkayb.soundconnect.modules.profile.shared.media.enums.ProfileType;
import com.berkayb.soundconnect.modules.promotion.entity.Promotion;
import com.berkayb.soundconnect.modules.promotion.enums.*;
import com.berkayb.soundconnect.modules.promotion.mapper.PromotionMapper;
import com.berkayb.soundconnect.modules.promotion.repository.PromotionRepository;
import com.berkayb.soundconnect.modules.promotion.service.PromotionServiceImpl;
import com.berkayb.soundconnect.modules.role.entity.Permission;
import com.berkayb.soundconnect.modules.role.entity.Role;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
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
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real authorization/locking/lifecycle SQL, exclusively in a non-reused disposable PostgreSQL. */
@DataJpaTest(properties = {"spring.config.location=classpath:/application-test.yml", "spring.config.import=",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.show-sql=false",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect"})
@ActiveProfiles("test") @Testcontainers @AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = AnnouncementPostgresTest.Config.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AnnouncementPostgresTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine")
            .withDatabaseName("announcements_test").withUsername("announcements_test").withPassword("announcements_test").withReuse(false);
    @Autowired DataSource dataSource; @Autowired EntityManager em; @Autowired PlatformTransactionManager manager;
    @Autowired AnnouncementAdminService admin; @Autowired AnnouncementReadService reads; @Autowired AnnouncementAccess access;
    @Autowired PromotionServiceImpl legacy; @Autowired MediaAssetRepository assets; @Autowired MediaAssetStatusUpdater updater;
    @Autowired LikeServiceImpl likes; @Autowired CommentServiceImpl comments; @Autowired LikeUsersReadService likerList;
    @MockitoBean MediaAssetService media;
    @MockitoBean MediaEngagementNotificationService notifications;
    @MockitoBean AnnouncementAnalyticsStore analytics;
    @MockitoBean AnalyticsIdentity analyticsIdentity;
    @MockitoBean CommentBurstGuard burst;
    @MockitoBean UserEntityFinder users;
    @MockitoBean CommentAuthorBatchResolver authors;
    JdbcTemplate jdbc; UUID administrator, musician, listener;
    static boolean migrated;

    @BeforeEach void setup() throws Exception {
        assertThat(POSTGRES.isRunning()).isTrue();
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getURL()).isEqualTo(POSTGRES.getJdbcUrl());
            assertThat(connection.getCatalog()).isEqualTo(POSTGRES.getDatabaseName());
        }
        jdbc = new JdbcTemplate(dataSource);
        if (!migrated) {
            String migration = Files.readString(Path.of("scripts/db/2026-09-13-feed-announcements.sql"));
            jdbc.execute(migration);
            jdbc.execute(migration); // Rollout is intentionally repeatable, including generated enum checks.
            migrated = true;
        }
        when(users.getUser(any())).thenAnswer(call -> em.getReference(User.class, call.getArgument(0)));
        tx(() -> {
            User managerUser = user();
            Permission permission = em.createQuery("select p from Permission p where p.name='MANAGE_PROMOTIONS'", Permission.class)
                    .getResultStream().findFirst().orElseGet(() -> persist(Permission.builder().name("MANAGE_PROMOTIONS").build()));
            managerUser.getPermissions().add(permission); administrator = managerUser.getId();
            musician = profile(true); listener = profile(false); return null;
        });
    }

    @Test void draftSchedulePublishEditEndArchiveAndDeletePreserveStableIdentityAndVersion() {
        var draft = draft();
        assertThat(draft.status()).isEqualTo(AnnouncementStatus.DRAFT);
        hidden(() -> reads.get(musician, draft.id()));
        var scheduled = admin.publish(administrator, draft.id(), new AnnouncementPublish(draft.version(), Instant.now().plusSeconds(3600), null));
        assertThat(scheduled.status()).isEqualTo(AnnouncementStatus.SCHEDULED);
        hidden(() -> reads.get(musician, draft.id()));
        var live = admin.publish(administrator, draft.id(), new AnnouncementPublish(scheduled.version(), null, null));
        assertThat(reads.get(musician, live.id()).status()).isEqualTo(AnnouncementStatus.PUBLISHED);
        hidden(() -> reads.get(listener, live.id()));
        var edited = admin.update(administrator, live.id(), write("Edited", live.version(), null));
        assertThat(edited.firstPublishedAt()).isEqualTo(live.firstPublishedAt());
        assertThat(edited.version()).isGreaterThan(live.version());
        error(ErrorType.ANNOUNCEMENT_VERSION_CONFLICT, () -> admin.update(administrator, live.id(), write("Stale", live.version(), null)));
        error(ErrorType.ANNOUNCEMENT_FORBIDDEN, () -> admin.get(musician, live.id()));
        var ended = admin.end(administrator, live.id(), new AnnouncementVersionAction(edited.version()));
        hidden(() -> reads.get(musician, live.id()));
        var archived = admin.archive(administrator, live.id(), new AnnouncementVersionAction(ended.version()));
        error(ErrorType.ANNOUNCEMENT_STATE_CONFLICT, () -> admin.publish(administrator, live.id(), new AnnouncementPublish(archived.version(), null, null)));
        admin.delete(administrator, live.id(), archived.version());
        hidden(() -> admin.get(administrator, live.id()));
        assertThat(jdbc.queryForObject("select count(*) from tbl_promotion_audience where promotion_id=?", Long.class, live.id())).isZero();
    }

    @Test void everyGenericEngagementAndReplyRouteRetainsAudienceAndLifecycleAuthorization() {
        var live = live(); UUID id = live.id();
        likes.like(musician, EngagementTargetType.ANNOUNCEMENT, id);
        likes.like(musician, EngagementTargetType.ANNOUNCEMENT, id);
        assertThat(likes.countLikes(musician, EngagementTargetType.ANNOUNCEMENT, id)).isEqualTo(1);
        verify(analytics, times(1)).recordEngagement(eq(musician), eq(id), any(), eq(AnnouncementAnalyticsStore.EngagementMetric.LIKE), any());
        UUID root = comments.createComment(musician, EngagementTargetType.ANNOUNCEMENT, id, new CommentCreateRequestDto("Root", null)).id();
        UUID reply = comments.createComment(musician, EngagementTargetType.ANNOUNCEMENT, id, new CommentCreateRequestDto("Reply", root)).id();
        verify(analytics, times(2)).recordEngagement(eq(musician), eq(id), any(), eq(AnnouncementAnalyticsStore.EngagementMetric.COMMENT), any());
        likes.setCommentLike(musician, reply, true);
        assertThat(reads.get(musician, id).engagement()).isEqualTo(new AnnouncementResponse.Engagement(1, 2, true));
        assertEngagementHidden(listener, id, root, reply);
        error(ErrorType.UNAUTHORIZED, () -> likes.countLikes(EngagementTargetType.ANNOUNCEMENT, id));
        admin.archive(administrator, id, new AnnouncementVersionAction(live.version()));
        assertEngagementHidden(musician, id, root, reply);
        assertThat(access.visibleProfile(musician, id)).isEmpty();
        // Own removal remains available after unpublication, matching existing comment semantics.
        comments.deleteComment(musician, root);
        assertThat(jdbc.queryForObject("select is_deleted from tbl_comment where id=?", Boolean.class, root)).isTrue();
    }

    @Test void canceledFutureScheduleCanPublishImmediatelyWithoutWaitingForItsOldDate() {
        var draft = draft();
        var scheduled = admin.publish(administrator, draft.id(),
                new AnnouncementPublish(draft.version(), Instant.now().plusSeconds(86400), null));
        var ended = admin.end(administrator, draft.id(), new AnnouncementVersionAction(scheduled.version()));
        var published = admin.publish(administrator, draft.id(), new AnnouncementPublish(ended.version(), null, null));
        assertThat(published.firstPublishedAt()).isBefore(Instant.now());
        assertThat(published.firstPublishedAt()).isEqualTo(published.startsAt());
        assertThat(reads.directory(musician, null, 50).items()).extracting(AnnouncementResponse::id).contains(draft.id());
        assertThat(reads.findForFeedBatch(musician, "MUSICIAN", Instant.now(), null, 160).items())
                .extracting(AnnouncementResponse::id).contains(draft.id());
    }

    @Test void expectedMetricsAccessMissDoesNotMarkTheSurroundingBatchRollbackOnly() {
        var value = live();
        tx(() -> {
            assertThat(access.visibleProfile(listener, value.id())).isEmpty();
            jdbc.update("update tlb_promotion set title='Completed batch' where id=?", value.id());
            return null;
        });
        assertThat(reads.get(musician, value.id()).title()).isEqualTo("Completed batch");
    }

    @Test void unsupportedPublishAndCursorTimestampsFailAsClientErrorsBeforeDatabaseConversion() {
        var draft = draft();
        error(ErrorType.ANNOUNCEMENT_INVALID, () -> admin.publish(administrator, draft.id(),
                new AnnouncementPublish(draft.version(), Instant.MAX, null)));
        error(ErrorType.ANNOUNCEMENT_INVALID, () -> admin.publish(administrator, draft.id(),
                new AnnouncementPublish(draft.version(), null, Instant.MAX)));
        String scope = "DIRECTORY:" + musician + ":MUSICIAN";
        for (Instant invalid : List.of(Instant.MIN, Instant.MAX, Instant.now().plusSeconds(86400))) {
            String cursor = new AnnouncementReadService.Cursor(scope, invalid, invalid, UUID.randomUUID()).encode();
            error(ErrorType.ANNOUNCEMENT_CURSOR_INVALID, () -> reads.directory(musician, cursor, 20));
        }
    }

    @Test void unavailableAnalyticsPreventsSuccessfulPublicationWithoutChangingDraft() {
        var draft = draft();
        doThrow(new ServiceUnavailableRetryException(ErrorType.ANALYTICS_UNAVAILABLE, 30))
                .when(analyticsIdentity).requireEnabled();
        error(ErrorType.ANALYTICS_UNAVAILABLE, () -> admin.publish(administrator, draft.id(),
                new AnnouncementPublish(draft.version(), null, null)));
        var unchanged = admin.get(administrator, draft.id());
        assertThat(unchanged.status()).isEqualTo(AnnouncementStatus.DRAFT);
        assertThat(unchanged.version()).isEqualTo(draft.version());
        assertThat(unchanged.firstPublishedAt()).isNull();
    }

    @Test void eligibilityIsAppliedBeforePageLimitAndContinuationsCanReadOlderCurrentIds() {
        var first = live(); var second = live(); var third = live();
        Instant anchor = Instant.now();
        var page = reads.findForFeedBatch(musician, "MUSICIAN", anchor, null, 2);
        assertThat(page.items()).hasSize(2); assertThat(page.hasMore()).isTrue();
        var next = reads.findForFeedBatch(musician, "MUSICIAN", anchor, page.nextCursor(), 2);
        assertThat(next.items()).extracting(AnnouncementResponse::id).contains(first.id());
        assertThat(reads.findForFeedByIds(musician, "MUSICIAN", List.of(first.id()), Instant.now()))
                .extracting(AnnouncementResponse::id).containsExactly(first.id());
        error(ErrorType.ANNOUNCEMENT_CURSOR_INVALID, () -> reads.findForFeedBatch(listener, "LISTENER", anchor, page.nextCursor(), 2));
        error(ErrorType.ANNOUNCEMENT_FORBIDDEN, () -> reads.findForFeedBatch(listener, "MUSICIAN", anchor, null, 2));
        var changed = admin.update(administrator, third.id(), new AnnouncementWrite("Other audience", "Body", Set.of(ProfileType.LISTENER), null, third.version()));
        var visible = reads.findForFeedBatch(musician, "MUSICIAN", Instant.now(), null, 2);
        assertThat(visible.items()).extracting(AnnouncementResponse::id).contains(second.id()).doesNotContain(changed.id());
        assertThat(reads.directory(listener, null, 50).items()).extracting(AnnouncementResponse::id).contains(changed.id());
    }

    @Test void legacyPromotionEndpointsCannotReadWriteOrDeleteFeedAnnouncements() {
        var value = live();
        hiddenLegacy(() -> legacy.getById(value.id()));
        hiddenLegacy(() -> legacy.deleteById(value.id()));
        error(ErrorType.ANNOUNCEMENT_FORBIDDEN, () -> legacy.getDisplayableByPlacement(PromotionPlacement.FEED));
        error(ErrorType.ANNOUNCEMENT_FORBIDDEN, () -> legacy.getAllByPlacement(PromotionPlacement.FEED));
        assertThat(legacy.getAllByType(PromotionType.ANNOUNCEMENT)).isEmpty();
        assertThat(legacy.getAllByStatus(PromotionStatus.ACTIVE)).isEmpty();
        assertThat(reads.get(musician, value.id()).id()).isEqualTo(value.id());
    }

    @Test void attachedPrivateMediaBelongsToExactAnnouncementAndArchiveRevokesFreshAccess() {
        var value = draft(); var other = draft();
        UUID asset = asset(value.id(), MediaOwnerType.PROMOTION, MediaVisibility.PRIVATE, MediaKind.IMAGE, MediaStatus.READY);
        UUID foreign = asset(other.id(), MediaOwnerType.PROMOTION, MediaVisibility.PRIVATE, MediaKind.IMAGE, MediaStatus.READY);
        error(ErrorType.ANNOUNCEMENT_MEDIA_INVALID, () -> admin.update(administrator, value.id(), write("Wrong owner", value.version(), foreign)));
        var attached = admin.update(administrator, value.id(), write("Photo", value.version(), asset));
        access.requireMediaAccess(administrator, value.id(), asset);
        hidden(() -> access.requireMediaAccess(musician, value.id(), asset));
        var published = admin.publish(administrator, value.id(), new AnnouncementPublish(attached.version(), null, null));
        access.requireMediaAccess(musician, value.id(), asset);
        hidden(() -> access.requireMediaAccess(listener, value.id(), asset));
        hidden(() -> access.requireMediaAccess(musician, value.id(), foreign));
        admin.archive(administrator, value.id(), new AnnouncementVersionAction(published.version()));
        hidden(() -> access.requireMediaAccess(musician, value.id(), asset));
        access.requireMediaAccess(administrator, value.id(), asset);
        hidden(() -> access.requireMediaAccess(administrator, value.id(), foreign));
        hidden(() -> access.requireUploadOwner(administrator, value.id()));
    }

    @Test void privateVideoUsesSameLeaseFencesButCannotBeFinalizedAsPublicHls() {
        UUID asset = asset(draft().id(), MediaOwnerType.PROMOTION, MediaVisibility.PRIVATE, MediaKind.VIDEO, MediaStatus.TRANSCODE_QUEUED);
        UUID unrelated = asset(UUID.randomUUID(), MediaOwnerType.USER, MediaVisibility.PRIVATE, MediaKind.VIDEO, MediaStatus.TRANSCODE_QUEUED);
        assertThat(tx(() -> assets.findByKindAndVisibilityAndStatusOrderByCreatedAtAsc(MediaKind.VIDEO, MediaVisibility.PUBLIC,
                MediaStatus.TRANSCODE_QUEUED, PageRequest.of(0, 50)).stream().map(MediaAsset::getId).toList())).contains(asset).doesNotContain(unrelated);
        assertThat(updater.tryClaimQueuedTranscode(unrelated)).isEmpty();
        var claim = updater.tryClaimQueuedTranscode(asset).orElseThrow();
        assertThat(updater.renewTranscodeLease(asset, claim.attemptToken())).isTrue();
        assertThat(updater.tryFinalizeReadyHls(asset, claim.attemptToken(), "https://public.invalid/video", null, 10, 720, 480)).isFalse();
        assertThat(updater.tryFinalizeReadyPrivateVideo(asset, UUID.randomUUID(), 10, 720, 480)).isFalse();
        assertThat(updater.tryFinalizeReadyPrivateVideo(asset, claim.attemptToken(), 10, 720, 480)).isTrue();
        MediaAsset ready = assets.findById(asset).orElseThrow();
        assertThat(ready.getStreamingProtocol()).isEqualTo(MediaStreamingProtocol.PROGRESSIVE);
        assertThat(ready.getPlaybackUrl()).isNull(); assertThat(ready.getThumbnailUrl()).isNull();
        assertThat(ready.getStatus()).isEqualTo(MediaStatus.READY);
    }

    @Test void audienceLockPreventsArchiveFromCrossingAnAcceptedEngagementTransaction() throws Exception {
        var value = live(); CountDownLatch locked = new CountDownLatch(1), release = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var holder = executor.submit(() -> tx(() -> {
                access.requireVisible(musician, value.id()); locked.countDown();
                try { if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("release timeout"); }
                catch (InterruptedException interrupted) { throw new RuntimeException(interrupted); }
                return null;
            }));
            try {
                assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> jdbc.queryForObject("select id from tlb_promotion where id=? for update nowait", UUID.class, value.id()))
                        .isInstanceOf(org.springframework.dao.DataAccessException.class);
            } finally { release.countDown(); }
            holder.get(10, TimeUnit.SECONDS);
        }
        admin.archive(administrator, value.id(), new AnnouncementVersionAction(value.version()));
        assertThat(access.visibleProfile(musician, value.id())).isEmpty();
    }

    private void assertEngagementHidden(UUID viewer, UUID id, UUID root, UUID reply) {
        hidden(() -> likes.like(viewer, EngagementTargetType.ANNOUNCEMENT, id));
        hidden(() -> likes.unlike(viewer, EngagementTargetType.ANNOUNCEMENT, id));
        hidden(() -> likes.isLiked(viewer, EngagementTargetType.ANNOUNCEMENT, id));
        hidden(() -> likes.countLikes(viewer, EngagementTargetType.ANNOUNCEMENT, id));
        hidden(() -> likerList.get(viewer, EngagementTargetType.ANNOUNCEMENT, id, 20, null));
        hidden(() -> comments.getComments(viewer, EngagementTargetType.ANNOUNCEMENT, id, PageRequest.of(0, 20)));
        hidden(() -> comments.getReplies(viewer, root, PageRequest.of(0, 20)));
        hidden(() -> comments.createComment(viewer, EngagementTargetType.ANNOUNCEMENT, id, new CommentCreateRequestDto("Hidden", root)));
        hidden(() -> likes.setCommentLike(viewer, reply, true));
        hidden(() -> likes.readCommentLike(viewer, reply));
        hidden(() -> likes.countLikes(viewer, EngagementTargetType.COMMENT, reply));
        hidden(() -> likerList.get(viewer, EngagementTargetType.COMMENT, reply, 20, null));
    }
    private AnnouncementResponse draft() { return admin.create(administrator, write("Announcement", null, null)); }
    private AnnouncementResponse live() { var draft = draft(); return admin.publish(administrator, draft.id(), new AnnouncementPublish(draft.version(), null, null)); }
    private AnnouncementWrite write(String title, Long version, UUID asset) { return new AnnouncementWrite(title, "Body", Set.of(ProfileType.MUSICIAN), asset, version); }
    private UUID asset(UUID owner, MediaOwnerType ownerType, MediaVisibility visibility, MediaKind kind, MediaStatus status) {
        return tx(() -> persist(MediaAsset.builder().ownerType(ownerType).ownerId(owner).kind(kind).visibility(visibility).status(status)
                .streamingProtocol(MediaStreamingProtocol.PROGRESSIVE).storageKey("protected/private-verified/media/" + UUID.randomUUID() + "/source.mp4")
                .mimeType(kind == MediaKind.VIDEO ? "video/mp4" : "image/jpeg").size(128L).build()).getId());
    }
    private UUID profile(boolean music) {
        User viewer = user(); String name = music ? "ROLE_MUSICIAN" : "ROLE_LISTENER";
        Role role = em.createQuery("select r from Role r where r.name=:name", Role.class).setParameter("name", name)
                .getResultStream().findFirst().orElseGet(() -> persist(Role.builder().name(name).build()));
        viewer.getRoles().add(role);
        if (music) persist(MusicianProfile.builder().user(viewer).build());
        else persist(ListenerProfile.builder().user(viewer).build());
        return viewer.getId();
    }
    private User user() { return persist(User.builder().username("ann" + UUID.randomUUID().toString().substring(0, 10))
            .email(UUID.randomUUID() + "@test.invalid").password("unused").status(UserStatus.ACTIVE).emailVerified(true).build()); }
    private <T> T persist(T value) { em.persist(value); return value; }
    private <T> T tx(Supplier<T> action) { return new TransactionTemplate(manager).execute(status -> action.get()); }
    private void hidden(Runnable action) { error(ErrorType.ANNOUNCEMENT_NOT_FOUND, action); }
    private void hiddenLegacy(Runnable action) { error(ErrorType.PROMOTION_NOT_FOUND, action); }
    private void error(ErrorType expected, Runnable action) { assertThatThrownBy(action::run).isInstanceOfSatisfying(SoundConnectException.class,
            error -> assertThat(error.getErrorType()).isEqualTo(expected)); }

    @Configuration(proxyBeanMethods = false)
    @EnableJpaRepositories(basePackageClasses = {PromotionRepository.class, MediaAssetRepository.class, UserRepository.class,
            LikeRepository.class, CommentRepository.class, OverthinkingPostRepository.class})
    @EntityScan(basePackages = "com.berkayb.soundconnect")
    @Import({JpaAuditingConfig.class, AnnouncementAdminService.class, AnnouncementReadService.class, AnnouncementAccess.class,
            PromotionServiceImpl.class, CommentTargetAccessGuard.class, EngagementTargetValidatorImpl.class,
            CommentLikeAccessGuard.class, LikeServiceImpl.class, LikeUsersReadService.class, LikeUsersReadRepository.class, CommentServiceImpl.class,
            CommentEntityFinder.class, MediaAssetStatusUpdater.class})
    static class Config {
        @Bean DataSource dataSource() {
            if (!POSTGRES.isRunning()) throw new IllegalStateException("Disposable PostgreSQL must be running");
            return new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        }
        @Bean CommentMapper commentMapper() { return Mappers.getMapper(CommentMapper.class); }
        @Bean PromotionMapper promotionMapper() { return Mappers.getMapper(PromotionMapper.class); }
        @Bean MediaTranscodeLeaseProperties leaseProperties() { return new MediaTranscodeLeaseProperties(); }
    }
}

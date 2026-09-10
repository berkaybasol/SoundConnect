package com.berkayb.soundconnect.modules.user.deletion;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.comment.entity.Comment;
import com.berkayb.soundconnect.modules.comment.support.CommentAuthorBatchResolver;
import com.berkayb.soundconnect.modules.engagement.enums.EngagementTargetType;
import com.berkayb.soundconnect.modules.event.audience.EventAudienceIntent;
import com.berkayb.soundconnect.modules.event.audience.EventIntent;
import com.berkayb.soundconnect.modules.follow.entity.Follow;
import com.berkayb.soundconnect.modules.like.entity.Like;
import com.berkayb.soundconnect.modules.media.entity.MediaAsset;
import com.berkayb.soundconnect.modules.media.deletion.MediaDeletionStateService;
import com.berkayb.soundconnect.modules.media.enums.*;
import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.message.dm.entity.DMConversation;
import com.berkayb.soundconnect.modules.message.dm.entity.DMMessage;
import com.berkayb.soundconnect.modules.notification.entity.Notification;
import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.modules.overthinking.entity.*;
import com.berkayb.soundconnect.modules.overthinking.enums.*;
import com.berkayb.soundconnect.modules.overthinking.outbox.*;
import com.berkayb.soundconnect.modules.overthinking.profileshare.OverthinkingProfileShare;
import com.berkayb.soundconnect.modules.overthinking.service.OverthinkingRevealParticipantGuard;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.entity.*;
import com.berkayb.soundconnect.modules.profile.shared.media.entity.ProfileMedia;
import com.berkayb.soundconnect.modules.profile.shared.media.enums.*;
import com.berkayb.soundconnect.modules.role.entity.*;
import com.berkayb.soundconnect.modules.role.entity.Role;
import com.berkayb.soundconnect.modules.spotify.dto.response.SpotifyPlaylistMetadataDto;
import com.berkayb.soundconnect.modules.tablegroup.game.service.TableGroupGameLifecycleService;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.*;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.berkayb.soundconnect.shared.config.JpaAuditingConfig;
import com.berkayb.soundconnect.shared.exception.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
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
import java.sql.Connection;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Runs the erasure inventory and fencing triggers against real PostgreSQL and real repositories. */
@DataJpaTest(properties = {"spring.config.location=classpath:/application-test.yml", "spring.config.import=",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.show-sql=false",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect"})
@ActiveProfiles("test") @Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = ListenerAccountDeletionPostgresTest.Config.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ListenerAccountDeletionPostgresTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine")
            .withDatabaseName("listener_erasure_test").withUsername("listener_test")
            .withPassword("listener_test").withReuse(false);
    static final String PASSWORD = "test-password-123!";
    @Autowired DataSource dataSource;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager manager;
    @Autowired ListenerAccountDeletionService deletion;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder passwords;
    @Autowired MediaDeletionStateService mediaDeletion;
    @Autowired CommentAuthorBatchResolver identities;
    @Autowired OverthinkingRevealParticipantGuard revealParticipants;
    @MockitoBean MediaAssetService media;
    @MockitoBean TableGroupGameLifecycleService games;
    JdbcTemplate jdbc;
    UUID owner;

    @BeforeEach void setup() throws Exception {
        jdbc = new JdbcTemplate(dataSource);
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            assertThat(connection.getMetaData().getURL()).isEqualTo(POSTGRES.getJdbcUrl());
            assertThat(connection.getCatalog()).isEqualTo(POSTGRES.getDatabaseName());
            // Hibernate does not install this pre-existing schema invariant. Use the real
            // migration's function/trigger so erasure is checked against deployed behavior.
            String studioMigration = Files.readString(Path.of("scripts/db/2026-07-21-studio-domain.sql"));
            String publicCodeTriggerEnd = "EXECUTE FUNCTION soundconnect_assign_user_public_code();";
            int publicCodeStart = studioMigration.indexOf("CREATE OR REPLACE FUNCTION soundconnect_assign_user_public_code()");
            int publicCodeEnd = studioMigration.indexOf(publicCodeTriggerEnd, publicCodeStart);
            assertThat(publicCodeStart).isGreaterThanOrEqualTo(0);
            assertThat(publicCodeEnd).isGreaterThan(publicCodeStart);
            statement.execute(studioMigration.substring(publicCodeStart, publicCodeEnd + publicCodeTriggerEnd.length()));
            for (String migration : List.of("2026-09-09-overthinking-lifecycle.sql",
                    "2026-09-10-overthinking-inbox-seen.sql", "2026-09-10-overthinking-profile-shares.sql",
                    "2026-09-10-overthinking-production-safety.sql", "2026-09-10-listener-account-erasure.sql")) {
                statement.execute(Files.readString(Path.of("scripts/db", migration)));
            }
        }
        owner = tx(() -> listener().getId());
        // Storage deletion itself is independently covered by its durable worker tests. Simulate
        // only its transactional database reservation here, so rollback remains observable.
        doAnswer(invocation -> {
            jdbc.update("update tbl_media_asset set status='DELETION_PENDING' where id=?", invocation.<UUID>getArgument(0));
            return null;
        }).when(media).delete(any(), any(), any(), any());
    }

    @AfterEach void removeFailureTrigger() {
        jdbc.execute("drop trigger if exists test_fail_listener_cleanup on tbl_listener_spotify_playlist");
        jdbc.execute("drop function if exists test_fail_listener_cleanup()");
    }

    @Test void erasureRemovesTheFullPublicationGraphAndRetainsAnUnauthenticatableIdentityFreeTombstone() {
        Graph graph = graph();
        User before = users.findById(owner).orElseThrow();
        String oldName = before.getUsername(), oldEmail = before.getEmail(), oldCode = before.getPublicCode();
        assertThatThrownBy(() -> jdbc.update("update tbl_user set public_code=? where id=?",
                "SC-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase(Locale.ROOT), owner))
                .hasStackTraceContaining("user public_code is immutable");

        deletion.deleteSelf(owner, PASSWORD, null);

        User erased = users.findById(owner).orElseThrow();
        assertThat(erased.getErasedAt()).isNotNull();
        assertThat(erased.getStatus()).isEqualTo(UserStatus.INACTIVE);
        assertThat(erased.getUsername()).startsWith("deleted_").isNotEqualTo(oldName);
        assertThat(erased.getEmail()).endsWith("@account.invalid").isNotEqualTo(oldEmail);
        assertThat(erased.getPublicCode()).isEqualTo(oldCode);
        assertThat(erased.getProvider()).isEqualTo(AuthProvider.LOCAL);
        assertThat(erased.getProviderSubject()).isNull();
        assertThat(erased.getPhone()).isNull();
        assertThat(erased.getDescription()).isNull();
        assertThat(erased.getProfilePicture()).isNull();
        assertThat(erased.getGender()).isNull();
        assertThat(erased.getCity()).isNull();
        assertThat(erased.getEmailVerificationToken()).isNull();
        assertThat(erased.getEmailVerificationExpiry()).isNull();
        assertThat(erased.getUsernameChangedAt()).isNull();
        assertThat(erased.getRoles()).isEmpty();
        assertThat(erased.getPermissions()).isEmpty();
        assertThat(passwords.matches(PASSWORD, erased.getPassword())).isFalse();
        assertThat(UserDetailsImpl.fromUser(erased).isEnabled()).isFalse();
        assertThat(users.findByUsername(oldName)).isEmpty();
        assertThat(users.findByEmail(oldEmail)).isEmpty();
        var identity = tx(() -> identities.resolve(Set.of(owner)).get(owner));
        assertThat(identity.username()).isEqualTo("Silinmiş hesap");
        assertThat(identity.avatarUrl()).isNull();
        assertThat(identity.visibilityMode()).isEqualTo(com.berkayb.soundconnect.modules.profile.ListenerProfile.enums.ListenerVisibilityMode.GHOST);

        assertThat(count("\"tbl_listener-profile\"", "user_id", owner)).isZero();
        assertThat(count("tbl_listener_spotify_playlist", "listener_profile_id", graph.profile)).isZero();
        assertThat(count("tbl_profile_media", "profile_id", graph.profile)).isZero();
        assertThat(count("tbl_overthinking_post", "author_id", owner)).isZero();
        assertThat(count("tbl_overthinking_reveal_request", "author_id", owner)).isZero();
        assertThat(count("tbl_overthinking_reveal_request", "requester_id", owner)).isZero();
        assertThat(count("tbl_overthinking_reveal_inbox", "author_id", owner)).isZero();
        assertThat(count("tbl_overthinking_create_receipt", "owner_user_id", owner)).isZero();
        assertThat(count("tbl_overthinking_reveal_attempt", "author_id", owner)).isZero();
        assertThat(count("tbl_overthinking_reveal_attempt", "requester_id", owner)).isZero();
        assertThat(count("tbl_overthinking_profile_share", "owner_user_id", owner)).isZero();
        assertThat(count("tbl_overthinking_profile_share", "source_post_id", graph.post)).isZero();
        assertThat(count("tbl_comment", "target_id", graph.post)).isZero();
        assertThat(count("tbl_comment", "target_id", graph.eventPost)).isZero();
        for (UUID target : List.of(graph.post, graph.rootComment, graph.reply, graph.eventPost, graph.eventComment, graph.retainedComment)) {
            assertThat(count("tbl_like", "target_id", target)).as("likes for %s", target).isZero();
        }
        assertThat(count("tbl_event_audience_intent", "user_id", owner)).isZero();
        assertThat(count("tbl_follow", "follower_id", owner)).isZero();
        assertThat(count("tbl_follow", "following_id", owner)).isZero();
        for (UUID event : List.of(graph.receivedEvent, graph.outgoingEvent, graph.delayedEvent)) {
            assertThat(count("tbl_notification", "source_event_id", event)).isZero();
            assertThat(count("tbl_overthinking_notification_outbox", "event_id", event)).isZero();
        }
        assertThat(count("tbl_notification_receipt", "source_event_id", graph.receivedEvent)).isEqualTo(1);
        assertThat(count("tbl_notification", "source_event_id", graph.unrelatedEvent)).isEqualTo(1);
        assertThat(count("tbl_overthinking_post", "id", graph.otherPost)).isEqualTo(1);
        assertThat(count("tbl_comment", "id", graph.retainedComment)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select text from tbl_comment where id=?", String.class, graph.retainedComment)).isEqualTo("[Silinmiş yorum]");
        assertThat(jdbc.queryForObject("select is_deleted from tbl_comment where id=?", Boolean.class, graph.retainedComment)).isTrue();
        assertThat(count("tbl_comment", "id", graph.retainedReply)).isEqualTo(1);
        assertThat(count("tbl_like", "target_id", graph.otherPost)).isEqualTo(1);
        for (UUID asset : List.of(graph.userAsset, graph.profileAsset)) {
            assertThat(jdbc.queryForObject("select status from tbl_media_asset where id=?", String.class, asset)).isEqualTo("DELETION_PENDING");
            assertThat(jdbc.queryForObject("select title from tbl_media_asset where id=?", String.class, asset)).isNull();
            assertThat(jdbc.queryForObject("select description from tbl_media_asset where id=?", String.class, asset)).isNull();
        }
        verify(media).delete(graph.userAsset, owner, MediaOwnerType.USER, owner);
        verify(media).delete(graph.profileAsset, owner, MediaOwnerType.LISTENER_PROFILE, graph.profile);
    }

    @Test void aLateCleanupFailureRollsBackIdentityAvatarMediaReservationsAndAllEarlierDeletes() {
        Graph graph = graph();
        String before = row(owner);
        jdbc.execute("create function test_fail_listener_cleanup() returns trigger language plpgsql as $$ begin "
                + "if old.listener_profile_id='" + graph.profile + "'::uuid then raise exception 'Injected late cleanup failure'; end if; return old; end $$");
        jdbc.execute("create trigger test_fail_listener_cleanup before delete on tbl_listener_spotify_playlist for each row execute function test_fail_listener_cleanup()");

        assertThatThrownBy(() -> deletion.deleteSelf(owner, PASSWORD, null)).hasStackTraceContaining("Injected late cleanup failure");

        assertThat(row(owner)).isEqualTo(before);
        assertThat(count("tbl_overthinking_post", "id", graph.post)).isEqualTo(1);
        assertThat(count("tbl_comment", "id", graph.rootComment)).isEqualTo(1);
        assertThat(count("tbl_like", "target_id", graph.rootComment)).isEqualTo(1);
        assertThat(count("tbl_overthinking_profile_share", "source_post_id", graph.post)).isEqualTo(1);
        assertThat(count("tbl_notification", "source_event_id", graph.receivedEvent)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select profile_picture_media_id from \"tbl_listener-profile\" where id=?", UUID.class, graph.profile)).isEqualTo(graph.userAsset);
        assertThat(jdbc.queryForObject("select status from tbl_media_asset where id=?", String.class, graph.userAsset)).isEqualTo("READY");
        assertThat(count("tbl_profile_media", "profile_id", graph.profile)).isEqualTo(1);
    }

    @Test void wrongPasswordAndUnverifiedGoogleSubjectDoNotMutateAnything() {
        UUID post = tx(() -> post(em.getReference(User.class, owner)).getId());
        String localBefore = row(owner);
        assertError(() -> deletion.deleteSelf(owner, "wrong-password", null), ErrorType.ACCOUNT_DELETION_REAUTH_REQUIRED);
        assertThat(row(owner)).isEqualTo(localBefore);
        jdbc.update("update tbl_user set provider='GOOGLE', provider_subject='verified-google-subject' where id=?", owner);
        String googleBefore = row(owner);
        assertError(() -> deletion.deleteSelf(owner, PASSWORD, null), ErrorType.ACCOUNT_DELETION_REAUTH_REQUIRED);
        assertError(() -> deletion.deleteSelf(owner, PASSWORD, "another-account-subject"), ErrorType.ACCOUNT_DELETION_REAUTH_REQUIRED);
        assertThat(row(owner)).isEqualTo(googleBefore);
        assertThat(count("tbl_overthinking_post", "id", post)).isEqualTo(1);
        verifyNoInteractions(media, games);
        deletion.deleteSelf(owner, null, "verified-google-subject");
        assertThat(users.findById(owner).orElseThrow().getErasedAt()).isNotNull();
    }

    @Test void listenerErasureCannotRemovePrivilegedRolesOrLegacyProfessionalFootprints() {
        for (String privilegedRole : List.of("ROLE_OWNER", "ROLE_ADMIN", "ROLE_MUSICIAN")) {
            UUID mixed = tx(() -> {
                User user = listener();
                user.getRoles().add(role(privilegedRole));
                profile(user);
                post(user);
                return user.getId();
            });
            String before = row(mixed);
            assertError(() -> deletion.deleteSelf(mixed, PASSWORD, null), ErrorType.ACCOUNT_DELETION_UNSUPPORTED_PROFILE);
            assertThat(row(mixed)).isEqualTo(before);
            assertThat(count("tbl_overthinking_post", "author_id", mixed)).isEqualTo(1);
            assertThat(count("\"tbl_listener-profile\"", "user_id", mixed)).isEqualTo(1);
        }
        tx(() -> {
            User user = em.find(User.class, owner);
            profile(user);
            persist(com.berkayb.soundconnect.modules.profile.MusicianProfile.entity.MusicianProfile.builder().user(user).build());
            return null;
        });
        String before = row(owner);
        assertError(() -> deletion.deleteSelf(owner, PASSWORD, null), ErrorType.ACCOUNT_DELETION_UNSUPPORTED_PROFILE);
        assertThat(row(owner)).isEqualTo(before);
        assertThat(count("tbl_musician_profile", "user_id", owner)).isEqualTo(1);
        verifyNoInteractions(media, games);
        UUID ordinary = tx(() -> { User user = listener(); user.getRoles().add(role("ROLE_USER")); return user.getId(); });
        deletion.deleteSelf(ordinary, PASSWORD, null);
        assertThat(users.findById(ordinary).orElseThrow().getErasedAt()).isNotNull();
    }

    @Test void administratorRetriesAreIdempotentAndNeitherSqlNorStaleEntitiesCanReviveTheAccount() {
        User stale = users.findById(owner).orElseThrow();
        deletion.deleteByAdministrator(owner);
        String erased = row(owner);
        deletion.deleteByAdministrator(owner);
        assertThat(row(owner)).isEqualTo(erased);
        assertError(() -> deletion.deleteSelf(owner, PASSWORD, null), ErrorType.ACCOUNT_DELETED);
        assertThatThrownBy(() -> jdbc.update("update tbl_user set status='ACTIVE', email_verified=true where id=?", owner))
                .hasStackTraceContaining("Account was permanently erased").satisfies(ListenerAccountDeletionPostgresTest::assertErasedSqlState);
        stale.setDescription("stale write must never restore old identity");
        assertThatThrownBy(() -> tx(() -> { em.merge(stale); em.flush(); return null; }))
                .hasStackTraceContaining("Account was permanently erased").satisfies(ListenerAccountDeletionPostgresTest::assertErasedSqlState);
        assertThat(row(owner)).isEqualTo(erased);
    }

    @Test void retainedDmReadMarkersAndDurableMediaCleanupContinueAfterErasureWhileNewMessagesAreFenced() {
        Graph graph = graph();
        UUID peer = jdbc.queryForObject("select author_id from tbl_overthinking_post where id=?", UUID.class, graph.otherPost);
        UUID messageId = tx(() -> {
            DMConversation conversation = persist(DMConversation.builder().userAId(owner).userBId(peer).build());
            return persist(DMMessage.builder().conversationId(conversation.getId()).senderId(owner).recipientId(peer)
                    .content("Shared message history").messageType("TEXT").build()).getId();
        });
        deletion.deleteSelf(owner, PASSWORD, null);
        // Exercise the same managed-entity updates as DMMessageServiceImpl.markMessageAsRead.
        // Hibernate includes unchanged sender/recipient fields, unlike a hand-written read_at-only UPDATE.
        UUID conversationId = tx(() -> {
            DMMessage message = em.find(DMMessage.class, messageId);
            message.setReadAt(LocalDateTime.now());
            DMConversation conversation = em.find(DMConversation.class, message.getConversationId());
            conversation.setLastReadMessageId(messageId);
            em.flush();
            return conversation.getId();
        });
        assertThat(jdbc.queryForObject("select content from tbl_dm_message where id=?", String.class, messageId)).isEqualTo("Shared message history");
        assertThat(jdbc.queryForObject("select read_at from tbl_dm_message where id=?", LocalDateTime.class, messageId)).isNotNull();
        assertThat(jdbc.queryForObject("select last_read_message_id from tbl_dm_conversation where id=?", UUID.class, conversationId)).isEqualTo(messageId);
        assertThatThrownBy(() -> tx(() -> persist(DMMessage.builder().conversationId(conversationId).senderId(peer).recipientId(owner)
                .content("Late message must not reach an erased account").messageType("TEXT").build())))
                .satisfies(ListenerAccountDeletionPostgresTest::assertErasedSqlState);
        for (UUID asset : List.of(graph.userAsset, graph.profileAsset)) {
            assertThat(mediaDeletion.getPendingTarget(asset)).isPresent();
            mediaDeletion.defer(asset);
            assertThat(mediaDeletion.finish(asset)).isTrue();
            assertThat(count("tbl_media_asset", "id", asset)).isZero();
        }
    }

    @Test void erasureCommittedBeforeAConcurrentSourceInsertRejectsTheStaleWriter() throws Exception {
        var erasedButUncommitted = new CountDownLatch(1);
        var allowCommit = new CountDownLatch(1);
        UUID latePost = UUID.randomUUID();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var erase = executor.submit(() -> tx(() -> {
                deletion.deleteSelf(owner, PASSWORD, null);
                erasedButUncommitted.countDown();
                waitFor(allowCommit);
                return null;
            }));
            try {
                waitFor(erasedButUncommitted);
                var insert = executor.submit(() -> jdbc.update(insertPostSql(), latePost, owner));
                awaitLock("tbl_overthinking_post");
                allowCommit.countDown();
                erase.get(15, TimeUnit.SECONDS);
                assertThatThrownBy(() -> insert.get(15, TimeUnit.SECONDS)).hasStackTraceContaining("Account was permanently erased")
                        .satisfies(ListenerAccountDeletionPostgresTest::assertErasedSqlState);
                assertThat(count("tbl_overthinking_post", "id", latePost)).isZero();
            } finally { allowCommit.countDown(); }
        }
    }

    @Test void aSourceInsertCommittedFirstIsIncludedInTheFollowingErasure() throws Exception {
        UUID concurrentPost = UUID.randomUUID();
        try (Connection connection = dataSource.getConnection(); var executor = Executors.newSingleThreadExecutor()) {
            connection.setAutoCommit(false);
            try {
                try (var insert = connection.prepareStatement(insertPostSql())) {
                    insert.setObject(1, concurrentPost); insert.setObject(2, owner); insert.executeUpdate();
                }
                var erase = executor.submit(() -> deletion.deleteSelf(owner, PASSWORD, null));
                awaitLock("tbl_user");
                connection.commit();
                erase.get(15, TimeUnit.SECONDS);
                assertThat(count("tbl_overthinking_post", "id", concurrentPost)).isZero();
                assertThat(users.findById(owner).orElseThrow().getErasedAt()).isNotNull();
            } finally { connection.rollback(); }
        }
    }

    @Test void revealCreationFencesTheAnonymousAuthorBeforeTakingItsPostLock() throws Exception {
        assertRevealFence(false);
    }

    @Test void revealDecisionFencesTheRequesterBeforeTakingItsRequestOrProfileLock() throws Exception {
        assertRevealFence(true);
    }

    private void assertRevealFence(boolean decision) throws Exception {
        Graph graph = graph();
        UUID peer = jdbc.queryForObject("select author_id from tbl_overthinking_post where id=?", UUID.class, graph.otherPost);
        UUID target = decision ? jdbc.queryForObject("select id from tbl_overthinking_reveal_request where requester_id=? and post_id=?",
                UUID.class, owner,graph.otherPost) : graph.post;
        String targetTable = decision ? "tbl_overthinking_reveal_request" : "tbl_overthinking_post";
        var pending = new java.util.concurrent.atomic.AtomicReference<Future<Boolean>>();
        try (var pool = Executors.newSingleThreadExecutor()) {
            tx(() -> {
                jdbc.queryForObject("select id from tbl_user where id=? for update",UUID.class,owner);
                pending.set(pool.submit(() -> {
                    try {
                        return tx(() -> {
                            if (decision) revealParticipants.lockRequesterForDecision(peer,target);
                            else revealParticipants.lockPostAuthor(target);
                            return jdbc.queryForList("select id from "+targetTable+" where id=? for update",UUID.class,target).isEmpty();
                        });
                    } catch (SoundConnectException failure) {
                        assertThat(failure.getErrorType()).isEqualTo(decision ? ErrorType.OVERTHINKING_REVEAL_REQUEST_NOT_FOUND : ErrorType.OVERTHINKING_POST_NOT_FOUND);
                        return true;
                    }
                }));
                awaitLock("join "+targetTable);
                deletion.deleteSelf(owner,PASSWORD,null);
                return null;
            });
            assertThat(pending.get().get(15,TimeUnit.SECONDS)).isTrue();
            assertThat(count(targetTable,"id",target)).isZero();
        }
    }

    @Test void erasureWaitingForGameplayDoesNotHoldTheListenerProfileLock() throws Exception {
        TableFixture fixture = tableFixture();
        var pending = new java.util.concurrent.atomic.AtomicReference<Future<?>>();
        try (var pool = Executors.newSingleThreadExecutor()) {
            tx(() -> {
                jdbc.queryForObject("select id from tbl_table_group where id=? for update",UUID.class,fixture.table);
                pending.set(pool.submit(() -> deletion.deleteSelf(owner,PASSWORD,null)));
                awaitLock("tbl_table_group");
                // A normal gameplay projection must be able to finish while erasure waits for its aggregate.
                assertThat(jdbc.queryForObject("select id from \"tbl_listener-profile\" where id=? for share nowait",UUID.class,fixture.ownerProfile))
                        .isEqualTo(fixture.ownerProfile);
                return null;
            });
            pending.get().get(15,TimeUnit.SECONDS);
            assertThat(users.findById(owner).orElseThrow().getErasedAt()).isNotNull();
        }
    }

    @Test void twoListenerErasuresSharingATableAcquireAggregatesBeforeEitherProfile() throws Exception {
        TableFixture fixture = tableFixture();
        var pending = new java.util.concurrent.atomic.AtomicReference<Future<?>>();
        try (var pool = Executors.newSingleThreadExecutor()) {
            doAnswer(invocation -> {
                pending.set(pool.submit(() -> deletion.deleteSelf(fixture.peer,PASSWORD,null)));
                awaitLock("tbl_table_group");
                assertThat(jdbc.queryForObject("select id from \"tbl_listener-profile\" where id=? for share nowait",UUID.class,fixture.peerProfile))
                        .isEqualTo(fixture.peerProfile);
                return null;
            }).when(games).ownerErased(any());
            deletion.deleteSelf(owner,PASSWORD,null);
            pending.get().get(15,TimeUnit.SECONDS);
            assertThat(users.findById(owner).orElseThrow().getErasedAt()).isNotNull();
            assertThat(users.findById(fixture.peer).orElseThrow().getErasedAt()).isNotNull();
            verify(games,never()).tableClosed(any(),any());
            verify(games,never()).participantRemoved(any(),any(),any());
        }
    }

    private TableFixture tableFixture() {
        return tx(() -> {
            User user = em.find(User.class,owner), peer = listener();
            ListenerProfile profile = profile(user), peerProfile = profile(peer);
            var city = persist(com.berkayb.soundconnect.modules.location.entity.City.builder().name("Test-"+UUID.randomUUID()).build());
            Instant now = Instant.now();
            var table = persist(com.berkayb.soundconnect.modules.tablegroup.entity.TableGroup.builder()
                    .ownerId(owner).createRequestKey(UUID.randomUUID()).description("Gathering").maxPersonCount(4).ageMin(18).ageMax(99)
                    .startAt(now).meetingAt(now.plusSeconds(3600)).expiresAt(now.plusSeconds(7200))
                    .status(com.berkayb.soundconnect.modules.tablegroup.enums.TableGroupStatus.ACTIVE).city(city)
                    .participants(new HashSet<>(Set.of(
                            com.berkayb.soundconnect.modules.tablegroup.entity.TableGroupParticipant.builder().userId(owner)
                                    .status(com.berkayb.soundconnect.modules.tablegroup.enums.ParticipantStatus.ACCEPTED).joinedAt(now).build(),
                            com.berkayb.soundconnect.modules.tablegroup.entity.TableGroupParticipant.builder().userId(peer.getId())
                                    .status(com.berkayb.soundconnect.modules.tablegroup.enums.ParticipantStatus.ACCEPTED).joinedAt(now).build())))
                    .build());
            return new TableFixture(table.getId(),peer.getId(),profile.getId(),peerProfile.getId());
        });
    }
    private record TableFixture(UUID table,UUID peer,UUID ownerProfile,UUID peerProfile) { }

    private Graph graph() {
        Graph graph = tx(() -> {
            User user = em.find(User.class, owner), other = listener();
            ListenerProfile profile = profile(user), otherProfile = profile(other);
            MediaAsset userAsset = asset(MediaOwnerType.USER, owner), profileAsset = asset(MediaOwnerType.LISTENER_PROFILE, profile.getId());
            profile.setProfilePictureMediaId(userAsset.getId());
            user.setProfilePicture("https://test.invalid/personal-photo");
            persist(ProfileMedia.builder().profileType(ProfileType.LISTENER).profileId(profile.getId()).mediaAssetId(userAsset.getId())
                    .role(ProfileMediaRole.GALLERY).build());
            persist(ListenerSpotifyPlaylist.create(profile, new SpotifyPlaylistMetadataDto("37i9dQZF1DXcBWIGoYBM5M", "Personal music",
                    "https://i.scdn.co/image/abc123", "https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M"), 0));
            OverthinkingPost post = post(user), otherPost = post(other);
            Comment root = comment(other, EngagementTargetType.OVERTHINKING, post.getId(), null);
            Comment reply = comment(user, EngagementTargetType.OVERTHINKING, post.getId(), root);
            Comment retained = comment(user, EngagementTargetType.OVERTHINKING, otherPost.getId(), null);
            Comment retainedReply = comment(other, EngagementTargetType.OVERTHINKING, otherPost.getId(), retained);
            EventAudienceIntent intent = new EventAudienceIntent(owner, UUID.randomUUID());
            intent.setIntent(EventIntent.GOING); intent.setPublishedOnProfile(true); intent.setNote("Personal note");
            intent.setPostId(UUID.randomUUID()); intent.setUpdatedAt(Instant.now()); intent.setPublishedAt(Instant.now()); persist(intent);
            Comment eventComment = comment(other, EngagementTargetType.EVENT_POST, intent.getPostId(), null);
            for (UUID target : List.of(root.getId(), reply.getId(), retained.getId(), eventComment.getId())) like(other, EngagementTargetType.COMMENT, target);
            like(other, EngagementTargetType.OVERTHINKING, post.getId());
            like(other, EngagementTargetType.OVERTHINKING, otherPost.getId());
            like(user, EngagementTargetType.OVERTHINKING, otherPost.getId());
            like(other, EngagementTargetType.EVENT_POST, intent.getPostId());
            persist(OverthinkingRevealRequest.builder().post(post).author(user).requester(other).build());
            persist(OverthinkingRevealRequest.builder().post(otherPost).author(other).requester(user).build());
            persist(new OverthinkingProfileShare(other.getId(), otherProfile.getId(), post.getId(), "Shared by friend", Instant.now()));
            persist(new OverthinkingProfileShare(owner, profile.getId(), otherPost.getId(), "Personal share note", Instant.now()));
            persist(Follow.builder().follower(user).following(other).followedAt(LocalDateTime.now()).build());
            persist(Follow.builder().follower(other).following(user).followedAt(LocalDateTime.now()).build());
            UUID received = notification(owner, Map.of("postId", post.getId().toString()));
            UUID outgoing = notification(other.getId(), Map.of("requesterId", owner.toString()));
            UUID unrelated = notification(other.getId(), Map.of("requesterId", other.getId().toString()));
            UUID delayed = UUID.randomUUID();
            persist(OverthinkingNotificationOutbox.builder().eventId(delayed).recipientId(other.getId())
                    .notificationType(NotificationType.OVERTHINKING_REVEAL_REQUEST_APPROVED)
                    .title("Personal title").message("Personal snapshot").payload(Map.of("authorId", owner.toString()))
                    .occurredAt(Instant.now()).status(OverthinkingNotificationOutboxStatus.PENDING).attemptCount(0)
                    .nextAttemptAt(Instant.now()).createdAt(Instant.now()).updatedAt(Instant.now()).build());
            return new Graph(profile.getId(), post.getId(), otherPost.getId(), root.getId(), reply.getId(), retained.getId(),
                    retainedReply.getId(), intent.getPostId(), eventComment.getId(), userAsset.getId(), profileAsset.getId(), received, outgoing, delayed, unrelated);
        });
        jdbc.update("insert into tbl_notification_receipt(source_event_id,recipient_id,recorded_at) values (?,?,now())", graph.receivedEvent, owner);
        jdbc.update("insert into tbl_overthinking_create_receipt(owner_user_id,client_request_id,request_hash,post_id,created_at) values (?,?,?,?,now())",
                owner, UUID.randomUUID(), "a".repeat(64), graph.post);
        UUID other = jdbc.queryForObject("select author_id from tbl_overthinking_post where id=?", UUID.class, graph.otherPost);
        jdbc.update("insert into tbl_overthinking_reveal_attempt(id,requester_id,author_id,created_at) values (?,?,?,now()),(?,?,?,now())",
                UUID.randomUUID(), owner, other, UUID.randomUUID(), other, owner);
        return graph;
    }

    private User listener() {
        Role role = role("ROLE_LISTENER");
        Permission permission = persist(Permission.builder().name("test-permission-" + UUID.randomUUID()).build());
        return persist(User.builder().username("u" + UUID.randomUUID().toString().replace("-", "").substring(0, 16))
                .email(UUID.randomUUID() + "@test.invalid").password(passwords.encode(PASSWORD)).status(UserStatus.ACTIVE)
                .roles(new HashSet<>(Set.of(role))).permissions(new HashSet<>(Set.of(permission))).emailVerified(true)
                .phone("+905555555555").description("Personal bio").gender(Gender.MALE)
                .emailVerificationToken("old-token").emailVerificationExpiry(LocalDateTime.now().plusDays(1))
                .usernameChangedAt(LocalDateTime.now()).build());
    }
    private Role role(String name) {
        return em.createQuery("select r from Role r where r.name=:name", Role.class).setParameter("name",name)
                .getResultStream().findFirst().orElseGet(() -> persist(Role.builder().name(name).build()));
    }
    private ListenerProfile profile(User user) {
        return persist(ListenerProfile.builder().user(user).name("Personal name").description("Personal profile bio")
                .address("Personal address").phone("Personal phone").visibilityChoiceCompleted(true).build());
    }
    private OverthinkingPost post(User user) {
        return persist(OverthinkingPost.builder().author(user).title("Personal title").content("Personal post body")
                .visibilityType(OverthinkingVisibilityType.ANONYMOUS).build());
    }
    private Comment comment(User user, EngagementTargetType type, UUID target, Comment parent) {
        return persist(Comment.builder().user(user).targetType(type).targetId(target).parentComment(parent).text("Personal comment").build());
    }
    private void like(User user, EngagementTargetType type, UUID target) {
        persist(Like.builder().user(user).targetType(type).targetId(target).build());
    }
    private MediaAsset asset(MediaOwnerType type, UUID ownerId) {
        return persist(MediaAsset.builder().kind(MediaKind.IMAGE).status(MediaStatus.READY).visibility(MediaVisibility.PUBLIC)
                .ownerType(type).ownerId(ownerId).title("Personal title").description("Personal description")
                .mimeType("image/jpeg").size(100L).playbackUrl("https://test.invalid/photo.jpg").build());
    }
    private UUID notification(UUID recipient, Map<String, Object> payload) {
        UUID event = UUID.randomUUID();
        persist(Notification.builder().sourceEventId(event).recipientId(recipient).type(NotificationType.OVERTHINKING_REVEAL_REQUEST_RECEIVED)
                .title("Personal title").message("Personal notification snapshot").occurredAt(Instant.now()).payload(payload).build());
        return event;
    }
    private String row(UUID id) { return jdbc.queryForObject("select to_jsonb(u)::text from tbl_user u where id=?", String.class, id); }
    private long count(String table, String column, UUID id) { return jdbc.queryForObject("select count(*) from " + table + " where " + column + "=?", Long.class, id); }
    private static String insertPostSql() {
        return "insert into tbl_overthinking_post(id,author_id,title,content,visibility_type,created_at,updated_at) values (?,?,'Concurrent title','Concurrent body','VISIBLE',now(),now())";
    }
    private void awaitLock(String table) {
        await().atMost(Duration.ofSeconds(10)).until(() -> jdbc.queryForObject(
                "select count(*) from pg_stat_activity where datname=current_database() and wait_event_type='Lock' and query like ?",
                Long.class, "%" + table + "%") > 0);
    }
    private static void waitFor(CountDownLatch latch) {
        try { assertThat(latch.await(15, TimeUnit.SECONDS)).isTrue(); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
    }
    private static void assertError(Runnable action, ErrorType error) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(SoundConnectException.class,
                failure -> assertThat(failure.getErrorType()).isEqualTo(error));
    }
    private static void assertErasedSqlState(Throwable failure) {
        while (failure != null && !(failure instanceof java.sql.SQLException)) failure = failure.getCause();
        assertThat(failure).isInstanceOf(java.sql.SQLException.class);
        assertThat(((java.sql.SQLException) failure).getSQLState()).isEqualTo("23514");
    }
    private <T> T persist(T entity) { em.persist(entity); return entity; }
    private <T> T tx(Supplier<T> action) { return new TransactionTemplate(manager).execute(status -> action.get()); }
    private record Graph(UUID profile, UUID post, UUID otherPost, UUID rootComment, UUID reply, UUID retainedComment,
                         UUID retainedReply, UUID eventPost, UUID eventComment, UUID userAsset, UUID profileAsset,
                         UUID receivedEvent, UUID outgoingEvent, UUID delayedEvent, UUID unrelatedEvent) { }

    @Configuration(proxyBeanMethods = false)
    @EnableJpaRepositories(basePackages = "com.berkayb.soundconnect")
    @EntityScan(basePackages = "com.berkayb.soundconnect")
    @Import({JpaAuditingConfig.class, ListenerAccountDeletionService.class, ListenerAccountDataCleaner.class,
            MediaDeletionStateService.class, CommentAuthorBatchResolver.class, OverthinkingRevealParticipantGuard.class})
    static class Config {
        @Bean DataSource dataSource() {
            if (!POSTGRES.isRunning()) throw new IllegalStateException("Disposable PostgreSQL must be running");
            return new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        }
        @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(4); }
    }
}

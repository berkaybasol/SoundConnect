package com.berkayb.soundconnect.modules.user.block;

import com.berkayb.soundconnect.modules.follow.repository.FollowRepository;
import com.berkayb.soundconnect.modules.profile.shared.identity.GhostListenerIdentityBatchResolver;
import com.berkayb.soundconnect.modules.user.support.AccountDeliveryFence;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfSystemProperty(named="push.test.jdbc-url", matches="jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost):\\d+/.*")
class UserBlockServicePostgresTest {
    private final UUID first = UUID.randomUUID(), second = UUID.randomUUID(), third = UUID.randomUUID();
    private final FollowRepository follows = mock(FollowRepository.class);
    private final GhostListenerIdentityBatchResolver names = mock(GhostListenerIdentityBatchResolver.class);
    private JdbcTemplate admin, jdbc;
    private String schema;
    private TransactionTemplate tx;
    private UserBlockService blocks;
    private BlockedAccountQuery blockedAccounts;

    @BeforeEach void setup() throws Exception {
        String url = System.getProperty("push.test.jdbc-url"), user = System.getProperty("push.test.username","postgres"),
                password = System.getProperty("push.test.password","");
        var base = new DriverManagerDataSource(url, user, password);
        try (var c = base.getConnection()) { assertThat(c.getCatalog()).containsIgnoringCase("test"); }
        admin = new JdbcTemplate(base);
        schema = "user_block_" + UUID.randomUUID().toString().replace("-","");
        admin.execute("create schema " + schema);
        var source = new DriverManagerDataSource(url + (url.contains("?")?"&":"?") + "currentSchema=" + schema, user, password);
        jdbc = new JdbcTemplate(source);
        jdbc.execute("create table tbl_user(id uuid primary key, erased_at timestamp, status text, email_verified boolean, access_suspended boolean default false)");
        var migration = Files.readString(Path.of("scripts/db/2026-11-24-user-blocks.sql"));
        jdbc.execute(migration); jdbc.execute(migration);
        for (UUID id : List.of(first,second,third))
            jdbc.update("insert into tbl_user(id,status,email_verified) values (?,'ACTIVE',true)", id);
        var sql = new NamedParameterJdbcTemplate(source);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        blocks = new UserBlockService(sql, new AccountDeliveryFence(sql), follows);
        blockedAccounts = new BlockedAccountQuery(sql, names);
    }

    @AfterEach void close() {
        if (schema != null) admin.execute("drop schema " + schema + " cascade");
    }

    @Test void namedSourcesDisappearInBothDirectionsAndAnonymousPostsRemainAvailableAfterReveal() {
        jdbc.execute("create table tbl_overthinking_post(id uuid primary key, author_id uuid, visibility_type text)");
        jdbc.execute("create table tbl_overthinking_reveal_request(post_id uuid, requester_id uuid, status text)");
        UUID named = UUID.randomUUID(), anonymous = UUID.randomUUID(), revealed = UUID.randomUUID(), other = UUID.randomUUID();
        jdbc.update("insert into tbl_overthinking_post values (?,?,'VISIBLE'),(?,?,'ANONYMOUS'),(?,?,'ANONYMOUS'),(?,?,'VISIBLE')",
                named,second, anonymous,second, revealed,second, other,third);
        jdbc.update("insert into tbl_overthinking_reveal_request values (?,?,'APPROVED')", revealed,first);
        var namedJdbc = new NamedParameterJdbcTemplate(jdbc);
        var policy = new com.berkayb.soundconnect.modules.profile.shared.visibility.ProfileBlockVisibility(blocks, namedJdbc);
        var ids = List.of(named, anonymous, revealed, other);
        tx.executeWithoutResult(s -> blocks.block(second, first));
        assertThat(policy.hiddenTargets(first, "OVERTHINKING", ids)).containsExactly(named);
        String visiblePosts = "select post.id from tbl_overthinking_post post where "
                + com.berkayb.soundconnect.modules.overthinking.support.OverthinkingBlockVisibility.VISIBLE_SOURCE + " order by post.id";
        assertThat(namedJdbc.queryForList(visiblePosts, Map.of("viewer", first), UUID.class))
                .containsExactlyInAnyOrder(anonymous, revealed, other);
        tx.executeWithoutResult(s -> blocks.block(first, second));
        tx.executeWithoutResult(s -> blocks.unblock(second, first));
        assertThat(policy.hiddenTargets(first, "OVERTHINKING", ids)).containsExactly(named);
        tx.executeWithoutResult(s -> blocks.unblock(first, second));
        assertThat(policy.hiddenTargets(first, "OVERTHINKING", ids)).isEmpty();
    }

    @Test void publicMediaAndItsProfileResolveToTheSameBlockedAccount() {
        jdbc.execute("create table tbl_media_asset(id uuid primary key, owner_type text, owner_id uuid)");
        jdbc.execute("create table tbl_musician_profile(id uuid primary key, user_id uuid)");
        jdbc.execute("create table \"tbl_listener-profile\"(id uuid primary key, user_id uuid)");
        jdbc.execute("create table tbl_studio_profile(id uuid primary key, user_id uuid)");
        jdbc.execute("create table tbl_venues(id uuid primary key, owner_id uuid)");
        jdbc.execute("create table tbl_venue_profile(id uuid primary key, venue_id uuid)");
        UUID musician = UUID.randomUUID(), venue = UUID.randomUUID(), venueProfile = UUID.randomUUID();
        UUID track = UUID.randomUUID(), galleryImage = UUID.randomUUID(), avatar = UUID.randomUUID(), bandAsset = UUID.randomUUID();
        jdbc.update("insert into tbl_musician_profile values (?,?)", musician,second);
        jdbc.update("insert into tbl_venues values (?,?)", venue,second);
        jdbc.update("insert into tbl_venue_profile values (?,?)", venueProfile,venue);
        jdbc.update("insert into tbl_media_asset values (?, 'MUSICIAN_PROFILE', ?),(?, 'VENUE_PROFILE', ?),(?, 'USER', ?),(?, 'BAND', ?)",
                track,musician, galleryImage,venueProfile, avatar,second, bandAsset,UUID.randomUUID());
        var policy = new com.berkayb.soundconnect.modules.profile.shared.visibility.ProfileBlockVisibility(blocks,
                new NamedParameterJdbcTemplate(jdbc));
        tx.executeWithoutResult(s -> blocks.block(first, second));
        assertThat(policy.hiddenTargets(first, "MEDIA", List.of(track,galleryImage,avatar,bandAsset)))
                .containsExactlyInAnyOrder(track,galleryImage,avatar);
        assertThat(policy.hiddenTargets(first, "VENUE", List.of(venue, venueProfile))).containsExactlyInAnyOrder(venue, venueProfile);
    }

    @Test void retriesAndReversedBlocksPreserveOwnershipAndSafeNames() {
        tx.executeWithoutResult(s -> blocks.block(first,second));
        tx.executeWithoutResult(s -> blocks.block(first,second));
        verify(follows,times(1)).deleteBetween(first,second);
        assertThat(blocks.hasBlocks(second)).isTrue();
        assertThat(blocks.hasBlocks(third)).isFalse();
        assertThat(blocks.status(second,first)).isEqualTo(new BlockStatus(false,true));
        assertThat(blocks.blockedAmong(second,List.of(first,third))).containsExactly(first);
        when(names.resolveCurrentCanonicalNames(List.of(second))).thenReturn(Map.of(second,"berna"));
        var page = tx.execute(s -> blockedAccounts.list(first,0,1));
        assertThat(page.content()).containsExactly(new BlockedAccountQuery.BlockedAccount(second,"berna"));
        assertThat(page.hasNext()).isFalse();
        assertThat(tx.execute(s -> blockedAccounts.list(second,0,1)).content()).isEmpty();
        tx.executeWithoutResult(s -> blocks.block(second,first));
        tx.executeWithoutResult(s -> blocks.unblock(first,second));
        tx.executeWithoutResult(s -> blocks.unblock(first,second));
        assertThat(blocks.status(first,second)).isEqualTo(new BlockStatus(false,true));
        assertThat(blocks.status(second,first)).isEqualTo(new BlockStatus(true,true));
        tx.executeWithoutResult(s -> blocks.unblock(second,first));
        assertThat(blocks.status(first,second)).isEqualTo(BlockStatus.AVAILABLE);
        assertThat(blocks.hasBlocks(second)).isFalse();
        tx.executeWithoutResult(s -> blocks.block(first,second));
        assertThat(blocks.status(first,second)).isEqualTo(new BlockStatus(true,true));
        assertThat(blocks.blockedAmong(first,List.of(third))).isEmpty();
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> blocks.block(first,first))).isInstanceOf(SoundConnectException.class);
    }

    @Test void committedBlockPreventsOverlappingContactInEitherDirection() throws Exception {
        var inserted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var writer = pool.submit(() -> tx.executeWithoutResult(s -> {
                blocks.block(first,second);
                inserted.countDown();
                try { if (!release.await(5,TimeUnit.SECONDS)) throw new AssertionError("commit timeout"); }
                catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
            }));
            assertThat(inserted.await(5,TimeUnit.SECONDS)).isTrue();
            var contact = pool.submit(() -> catchThrowableOfType(() -> tx.executeWithoutResult(s ->
                    blocks.requireInteractionAllowed(second,first)),SoundConnectException.class));
            try { assertThatThrownBy(() -> contact.get(150,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class); }
            finally { release.countDown(); }
            writer.get(5,TimeUnit.SECONDS);
            assertThat(contact.get(5,TimeUnit.SECONDS).getErrorType()).isEqualTo(ErrorType.USER_INTERACTION_UNAVAILABLE);
        }
    }
}

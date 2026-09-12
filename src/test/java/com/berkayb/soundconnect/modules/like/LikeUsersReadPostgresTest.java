package com.berkayb.soundconnect.modules.like;

import com.berkayb.soundconnect.modules.comment.repository.CommentAuthorRepository;
import com.berkayb.soundconnect.modules.comment.support.CommentAuthorBatchResolver;
import com.berkayb.soundconnect.modules.engagement.enums.EngagementTargetType;
import com.berkayb.soundconnect.modules.engagement.service.EngagementTargetValidator;
import com.berkayb.soundconnect.modules.like.repository.*;
import com.berkayb.soundconnect.modules.like.service.*;
import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.enums.ListenerVisibilityMode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import javax.sql.DataSource;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

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
class LikeUsersReadPostgresTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine")
            .withDatabaseName("like_users_read").withUsername("soundconnect").withPassword("soundconnect");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }
    @Autowired DataSource dataSource;
    @Autowired LikeRepository likes;
    @Autowired CommentAuthorRepository authors;
    final LocalDateTime now = LocalDateTime.parse("2026-09-12T10:00:00.123456");

    @Test void keysetHandlesEqualTimestampsRemovedRowsAndNewLikesWithoutDuplicatesOrCrossTargetRows() {
        JdbcTemplate sql = new JdbcTemplate(dataSource);
        var reader = reader();
        UUID target = UUID.randomUUID(), other = UUID.randomUUID();
        List<UUID> userIds = new ArrayList<>(), likeIds = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            UUID user = account(sql, "user" + i);
            UUID like = new UUID(0L, i);
            like(sql, like, user, "EVENT_POST", target, now);
            userIds.add(user); likeIds.add(like);
        }
        like(sql, UUID.randomUUID(), userIds.getFirst(), "EVENT", target, now.plusDays(1));
        like(sql, UUID.randomUUID(), userIds.getFirst(), "EVENT_POST", other, now.plusDays(1));
        var first = reader.page(EngagementTargetType.EVENT_POST, target, null, 2);
        assertThat(first).extracting(LikeUsersReadRepository.Row::id).containsExactly(likeIds.get(4), likeIds.get(3));
        var last = first.getLast();
        String cursor = new LikeUsersCursor(last.createdAt(), last.id()).encode(EngagementTargetType.EVENT_POST, target);
        sql.update("delete from tbl_like where id=?", last.id());
        like(sql, UUID.randomUUID(), account(sql, "new_liker"), "EVENT_POST", target, now.plusSeconds(1));

        var next = reader.page(EngagementTargetType.EVENT_POST, target,
                LikeUsersCursor.decode(cursor, EngagementTargetType.EVENT_POST, target), 10);
        assertThat(next).extracting(LikeUsersReadRepository.Row::id).containsExactly(likeIds.get(2), likeIds.get(1), likeIds.get(0));
    }

    @Test void deletedInactiveAndUnverifiedUsersAreExcludedAndLegacyNullTimestampsCanBePaged() {
        JdbcTemplate sql = new JdbcTemplate(dataSource);
        UUID target = UUID.randomUUID();
        UUID nullOne = new UUID(0, 1), nullTwo = new UUID(0, 2), dated = new UUID(0, 3);
        like(sql, nullOne, account(sql, "null_one"), "MEDIA", target, null);
        like(sql, nullTwo, account(sql, "null_two"), "MEDIA", target, null);
        like(sql, dated, account(sql, "dated"), "MEDIA", target, now);
        UUID inactive = account(sql, "inactive"), unverified = account(sql, "unverified"), erased = account(sql, "erased");
        sql.update("update tbl_user set status='INACTIVE' where id=?", inactive);
        sql.update("update tbl_user set email_verified=false where id=?", unverified);
        sql.update("update tbl_user set erased_at=? where id=?", Timestamp.valueOf(now), erased);
        for (UUID id : List.of(inactive, unverified, erased)) like(sql, UUID.randomUUID(), id, "MEDIA", target, now.plusDays(1));

        var first = reader().page(EngagementTargetType.MEDIA, target, null, 1);
        assertThat(first.getFirst().id()).isEqualTo(nullTwo);
        var afterNull = reader().page(EngagementTargetType.MEDIA, target, new LikeUsersCursor(null, nullTwo), 10);
        assertThat(afterNull).extracting(LikeUsersReadRepository.Row::id).containsExactly(nullOne, dated);
        assertThat(reader().page(EngagementTargetType.MEDIA, target, new LikeUsersCursor(now, dated), 10)).isEmpty();
    }

    @Test void realBatchedIdentityKeepsMusicianUsernameAndMasksPendingOrGhostAlternateProfiles() {
        JdbcTemplate sql = new JdbcTemplate(dataSource);
        UUID target = UUID.randomUUID(), viewer = account(sql, "viewer");
        UUID musician = account(sql, "canonical_musician"), ghost = account(sql, "ghost_listener"), pending = account(sql, "private_pending");
        for (UUID user : List.of(musician, ghost, pending)) {
            sql.update("insert into tbl_musician_profile(id,user_id,name,stage_name) values (?,?,?,?)",
                    UUID.randomUUID(), user, "Hidden real name", "Do not use this stage name");
            like(sql, UUID.randomUUID(), user, "OVERTHINKING_PROFILE_SHARE", target, now);
        }
        listener(sql, ghost, "GHOST", true);
        listener(sql, pending, "STANDARD", false);
        sql.update("update tbl_user set profile_picture='https://private.test/old-identity.jpg' where id in (?,?)", ghost, pending);
        var media = mock(MediaAssetService.class);
        var identities = new CommentAuthorBatchResolver(authors, media);
        var validator = mock(EngagementTargetValidator.class);
        var service = new LikeUsersReadService(likes, reader(), validator, mock(CommentLikeAccessGuard.class), identities);

        var page = service.get(viewer, EngagementTargetType.OVERTHINKING_PROFILE_SHARE, target, 20, null);
        var users = page.items().stream().collect(java.util.stream.Collectors.toMap(u -> u.id(), u -> u));
        assertThat(users.get(musician).username()).isEqualTo("canonical_musician");
        assertThat(users.get(ghost).username()).isEqualTo("ghost_listener");
        assertThat(users.get(ghost).visibilityMode()).isEqualTo(ListenerVisibilityMode.GHOST);
        assertThat(users.get(ghost).avatarUrl()).isNull();
        assertThat(users.get(pending).username()).isEqualTo("Kullanici");
        assertThat(users.get(pending).visibilityMode()).isEqualTo(ListenerVisibilityMode.GHOST);
        assertThat(users.get(pending).avatarUrl()).isNull();
        assertThat(page.hasMore()).isFalse();
        verify(validator).validateExists(EngagementTargetType.OVERTHINKING_PROFILE_SHARE, target);
        verifyNoInteractions(media);
    }

    @Test void onlineMigrationIsReplayableAndMatchesTheEntityKeysetIndex() throws Exception {
        String migration = Files.readString(Path.of("scripts/db/2026-09-12-like-users-pagination.sql"));
        String uncommented = migration.lines().filter(line -> !line.stripLeading().startsWith("--"))
                .collect(java.util.stream.Collectors.joining("\n"));
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            connection.setAutoCommit(true);
            for (int repeat = 0; repeat < 2; repeat++) {
                for (String command : uncommented.split(";")) if (!command.isBlank()) statement.execute(command);
            }
            try (var result = statement.executeQuery("select pg_get_indexdef(indexrelid) from pg_index where indexrelid='idx_like_target_created'::regclass and indisvalid and indisready")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).contains("target_type, target_id, created_at DESC, id DESC");
            }
            try (var result = statement.executeQuery("select count(*) from soundconnect_schema_migrations where migration_id='2026-09-12-like-users-pagination'")) {
                result.next(); assertThat(result.getInt(1)).isEqualTo(1);
            }
        }
    }

    private LikeUsersReadRepository reader() { return new LikeUsersReadRepository(new NamedParameterJdbcTemplate(dataSource)); }
    private UUID account(JdbcTemplate sql, String username) {
        UUID id = UUID.randomUUID();
        sql.update("""
                insert into tbl_user(id,created_at,updated_at,public_code,user_name,password,email,status,provider,email_verified)
                values (?,?,?,?,?,?,?,'ACTIVE','LOCAL',true)
                """, id, Timestamp.valueOf(now), Timestamp.valueOf(now),
                "SC-" + id.toString().replace("-", "").substring(0, 20).toUpperCase(Locale.ROOT), username, "unused", username + "@test.invalid");
        return id;
    }
    private void like(JdbcTemplate sql, UUID id, UUID user, String type, UUID target, LocalDateTime time) {
        sql.update("insert into tbl_like(id,user_id,target_type,target_id,created_at,updated_at) values (?,?,?,?,?,?)",
                id, user, type, target, time == null ? null : Timestamp.valueOf(time), Timestamp.valueOf(now));
    }
    private void listener(JdbcTemplate sql, UUID user, String mode, boolean complete) {
        sql.update("""
                insert into "tbl_listener-profile"(id,user_id,name,visibility_mode,visibility_choice_completed,version,playlist_revision)
                values (?,?,?,?,?,0,0)
                """, UUID.randomUUID(), user, "Private real name", mode, complete);
    }
}

package com.berkayb.soundconnect.modules.feed.musician.provider;

import com.berkayb.soundconnect.modules.comment.support.CommentAuthorBatchResolver;
import com.berkayb.soundconnect.modules.event.support.EventShareUrlBuilder;
import com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedItemType;
import com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedPayloads;
import com.berkayb.soundconnect.modules.feed.musician.core.MusicianFeedProperties;
import com.berkayb.soundconnect.modules.feed.musician.delivery.MusicianFeedDeliveryService;
import com.berkayb.soundconnect.modules.feed.musician.delivery.MusicianFeedDeliveryTokenCodec;
import com.berkayb.soundconnect.modules.feed.musician.delivery.MusicianFeedReplayVisibilityGuard;
import com.berkayb.soundconnect.modules.feed.musician.candidate.MusicianFeedCandidate;
import com.berkayb.soundconnect.modules.feed.musician.candidate.MusicianFeedCandidateRequest;
import com.berkayb.soundconnect.modules.feed.musician.feedback.MusicianFeedFeedbackSnapshot;
import com.berkayb.soundconnect.modules.feed.musician.personalization.MusicianFeedPersonalizationSnapshot;
import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.overthinking.dto.response.OverthinkingPostResponseDto;
import com.berkayb.soundconnect.modules.overthinking.enums.OverthinkingVisibilityType;
import com.berkayb.soundconnect.modules.overthinking.service.OverthinkingPostService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/** Protects the canonical Overthinking projection's PostgreSQL shared-lock boundary. */
@DataJpaTest(properties = {
        "spring.config.location=classpath:/application-test.yml",
        "spring.config.import=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect"
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        MusicianFeedOverthinkingShareCandidateProvider.class,
        MusicianFeedMediaActivityCandidateProvider.class,
        MusicianFeedDeliveryService.class,
        com.berkayb.soundconnect.modules.feed.musician.delivery.MusicianFeedDeliveryLookup.class,
        MusicianFeedReplayVisibilityGuard.class,
        com.berkayb.soundconnect.modules.feed.musician.moderation.MusicianFeedRestrictionGuard.class,
        com.berkayb.soundconnect.modules.feed.musician.moderation.MusicianFeedRestrictionRepository.class,
        com.berkayb.soundconnect.modules.feed.musician.moderation.MusicianFeedModerationScopeResolver.class,
        CommentAuthorBatchResolver.class,
        MusicianFeedOverthinkingProviderTransactionPostgresTest.ProviderConfiguration.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MusicianFeedOverthinkingProviderTransactionPostgresTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine")
            .withDatabaseName("musician_feed_overthinking_transaction")
            .withUsername("musician_feed_overthinking_transaction")
            .withPassword("musician_feed_overthinking_transaction")
            .withReuse(false);

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate sql;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private CommentAuthorBatchResolver authorResolver;
    @Autowired private MusicianFeedOverthinkingShareCandidateProvider provider;
    @Autowired private MusicianFeedMediaActivityCandidateProvider activityProvider;
    @Autowired private MusicianFeedDeliveryService deliveries;
    @Autowired private ObjectMapper objectMapper;

    @MockitoBean private OverthinkingPostService posts;
    @MockitoBean private MediaAssetService media;
    @MockitoBean private com.berkayb.soundconnect.modules.promotion.announcement.AnnouncementReadService announcements;

    private final Instant anchor = Instant.parse("2026-09-11T12:00:00Z");
    private UUID viewerId;
    private UUID viewerProfileId;
    private UUID publisherId;
    private UUID activityActorId;
    private UUID sourceId;
    private UUID shareId;

    @BeforeEach
    void persistCommittedFeedFixture() throws SQLException {
        assertThat(POSTGRES.isRunning()).isTrue();
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getURL()).isEqualTo(POSTGRES.getJdbcUrl());
            assertThat(connection.getCatalog()).isEqualTo(POSTGRES.getDatabaseName());
        }

        viewerId = UUID.randomUUID();
        viewerProfileId = UUID.randomUUID();
        publisherId = UUID.randomUUID();
        activityActorId = UUID.randomUUID();
        sourceId = UUID.randomUUID();
        shareId = UUID.randomUUID();
        UUID listenerProfileId = UUID.randomUUID();

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            insertAccount(viewerId, "feed-viewer");
            sql.update("""
                    insert into tbl_musician_profile(id,created_at,updated_at,user_id,name,stage_name)
                    values (?,?,?,?,?,?)
                    """, viewerProfileId, Timestamp.from(anchor.minusSeconds(900)),
                    Timestamp.from(anchor.minusSeconds(900)), viewerId, "Feed Viewer", "Feed Viewer");

            insertAccount(publisherId, "listener-publisher");
            var listenerRoles = sql.queryForList("select id from tbl_role where name='ROLE_LISTENER'", UUID.class);
            UUID listenerRoleId = listenerRoles.isEmpty() ? UUID.randomUUID() : listenerRoles.getFirst();
            if (listenerRoles.isEmpty()) sql.update("insert into tbl_role(id,created_at,updated_at,name) values (?,?,?,?)",
                    listenerRoleId, Timestamp.from(anchor.minusSeconds(900)),
                    Timestamp.from(anchor.minusSeconds(900)), "ROLE_LISTENER");
            sql.update("insert into user_roles(user_id,role_id) values (?,?)", publisherId, listenerRoleId);
            sql.update("""
                    insert into "tbl_listener-profile"(id,created_at,updated_at,user_id,name,visibility_mode,
                        visibility_choice_completed,version,playlist_revision)
                    values (?,?,?,?,?,?,?,?,?)
                    """, listenerProfileId, Timestamp.from(anchor.minusSeconds(900)),
                    Timestamp.from(anchor.minusSeconds(900)), publisherId, "Listener Publisher",
                    "STANDARD", true, 0L, 0L);
            sql.update("""
                    insert into tbl_follow(id,created_at,updated_at,follower_id,following_id,followed_at)
                    values (?,?,?,?,?,?)
                    """, UUID.randomUUID(), Timestamp.from(anchor.minusSeconds(800)),
                    Timestamp.from(anchor.minusSeconds(800)), viewerId, publisherId,
                    Timestamp.from(anchor.minusSeconds(800)));

            UUID activityActorProfileId = UUID.randomUUID();
            insertAccount(activityActorId, "activity-actor");
            sql.update("""
                    insert into tbl_musician_profile(id,created_at,updated_at,user_id,name,stage_name)
                    values (?,?,?,?,?,?)
                    """, activityActorProfileId, Timestamp.from(anchor.minusSeconds(900)),
                    Timestamp.from(anchor.minusSeconds(900)), activityActorId,
                    "Activity Actor", "Activity Actor");
            sql.update("""
                    insert into tbl_follow(id,created_at,updated_at,follower_id,following_id,followed_at)
                    values (?,?,?,?,?,?)
                    """, UUID.randomUUID(), Timestamp.from(anchor.minusSeconds(800)),
                    Timestamp.from(anchor.minusSeconds(800)), viewerId, activityActorId,
                    Timestamp.from(anchor.minusSeconds(800)));
            sql.update("""
                    insert into tbl_overthinking_post(id,created_at,updated_at,author_id,title,content,visibility_type)
                    values (?,?,?,?,?,?,?)
                    """, sourceId, Timestamp.from(anchor.minusSeconds(700)),
                    Timestamp.from(anchor.minusSeconds(700)), publisherId,
                    "Visible thought", "Canonical source body", "VISIBLE");
            sql.update("""
                    insert into tbl_overthinking_profile_share(
                        id,owner_user_id,listener_profile_id,source_post_id,note,published_at)
                    values (?,?,?,?,?,?)
                    """, shareId, publisherId, listenerProfileId, sourceId,
                    "Profile share note", Timestamp.from(anchor.minusSeconds(600)));
            sql.update("""
                    insert into tbl_like(id,created_at,updated_at,user_id,target_type,target_id)
                    values (?,?,?,?,?,?)
                    """, UUID.randomUUID(), Timestamp.from(anchor.minusSeconds(500)),
                    Timestamp.from(anchor.minusSeconds(500)), activityActorId,
                    "OVERTHINKING_PROFILE_SHARE", shareId);
        });

        when(posts.getByIdsForViewer(eq(viewerId), anyList())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
            assertThat(invocation.<List<UUID>>getArgument(1)).containsExactly(sourceId);

            var identity = authorResolver.resolve(Set.of(publisherId)).get(publisherId);
            assertThat(identity).isNotNull();
            return Map.of(sourceId, new OverthinkingPostResponseDto(
                    sourceId, publisherId, identity.username(), identity.avatarUrl(),
                    false, true, OverthinkingVisibilityType.VISIBLE,
                    "Visible thought", "Canonical source body",
                    null, null, null, null, null, null, null, null, null,
                    0, 0, false));
        });
    }

    @Test
    void proxiedProvidersAllowTheCanonicalSharedLockInTheirOwnTransactions() {
        var request = new MusicianFeedCandidateRequest(
                viewerId, viewerProfileId, UUID.randomUUID(), anchor, anchor, 20,
                Set.of(MusicianFeedItemType.OVERTHINKING_PROFILE_SHARE),
                MusicianFeedPersonalizationSnapshot.empty(), MusicianFeedFeedbackSnapshot.empty());

        assertThat(AopUtils.isAopProxy(provider)).isTrue();
        List<MusicianFeedCandidate> candidates = provider.findCandidates(request);

        assertThat(candidates).singleElement().satisfies(candidate -> {
            assertThat(candidate.itemId()).isEqualTo("OVERTHINKING_PROFILE_SHARE:" + shareId);
            assertThat(candidate.type()).isEqualTo(MusicianFeedItemType.OVERTHINKING_PROFILE_SHARE);
            var payload = (MusicianFeedPayloads.ProfileShare) candidate.payload();
            assertThat(payload.shareId()).isEqualTo(shareId);
            assertThat(((OverthinkingPostResponseDto) payload.source()).id()).isEqualTo(sourceId);
        });

        var activityRequest = new MusicianFeedCandidateRequest(
                viewerId, viewerProfileId, UUID.randomUUID(), anchor, anchor, 20,
                Set.of(MusicianFeedItemType.ACTIVITY_LIKE,
                        MusicianFeedItemType.OVERTHINKING_PROFILE_SHARE),
                MusicianFeedPersonalizationSnapshot.empty(), MusicianFeedFeedbackSnapshot.empty());

        assertThat(AopUtils.isAopProxy(activityProvider)).isTrue();
        assertThat(activityProvider.findCandidates(activityRequest)).singleElement().satisfies(candidate -> {
            assertThat(candidate.itemId())
                    .isEqualTo("ACTIVITY_LIKE:OVERTHINKING_PROFILE_SHARE:" + shareId);
            assertThat(candidate.type()).isEqualTo(MusicianFeedItemType.ACTIVITY_LIKE);
            var activity = (MusicianFeedPayloads.Activity) candidate.payload();
            assertThat(activity.actor().userId()).isEqualTo(activityActorId);
            assertThat(activity.targetItemType())
                    .isEqualTo(MusicianFeedItemType.OVERTHINKING_PROFILE_SHARE);
        });
    }

    @Test
    void proxiedReplayAllowsCanonicalIdentityLocksAndPreservesTheCommittedResponse() throws Exception {
        UUID session = UUID.randomUUID();
        Set<MusicianFeedItemType> types = Set.of(MusicianFeedItemType.OVERTHINKING_PROFILE_SHARE);
        var request = new MusicianFeedCandidateRequest(viewerId, viewerProfileId, session,
                anchor, anchor, 20, types, MusicianFeedPersonalizationSnapshot.empty(),
                MusicianFeedFeedbackSnapshot.empty());
        var candidate = provider.findCandidates(request).getFirst();
        var committed = deliveries.recordPageAndReplay(viewerId, session, anchor, 1, "musician-v1",
                0, "r".repeat(43), 1, types, List.of(candidate.toResponse()), List.of(candidate.lane()),
                null, false, anchor);
        assertThat(AopUtils.isAopProxy(deliveries)).isTrue();
        var replayed = deliveries.requireReplay(viewerId, session, 0, "r".repeat(43), 1, types,
                anchor.plusSeconds(1));
        assertThat(objectMapper.readTree(objectMapper.writeValueAsBytes(replayed)))
                .isEqualTo(objectMapper.readTree(objectMapper.writeValueAsBytes(committed)));
        assertThat(deliveries.replay(viewerId, session, 0, "r".repeat(43), 1, types,
                anchor.plusSeconds(2))).isPresent();
    }

    private void insertAccount(UUID id, String username) {
        username += "-" + id.toString().substring(0, 8);
        String publicCode = "SC-" + id.toString().replace("-", "")
                .substring(0, 20).toUpperCase(Locale.ROOT);
        sql.update("""
                insert into tbl_user(id,created_at,updated_at,public_code,user_name,password,email,status,
                    provider,email_verified)
                values (?,?,?,?,?,?,?,?,?,?)
                """, id, Timestamp.from(anchor.minusSeconds(900)), Timestamp.from(anchor.minusSeconds(900)),
                publicCode, username, "not-used", username + "@soundconnect.test", "ACTIVE", "LOCAL", true);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProviderConfiguration {

        @Bean
        EventShareUrlBuilder eventShareUrlBuilder() {
            return new EventShareUrlBuilder("https://soundconnect.test");
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules()
                    .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        }

        @Bean MusicianFeedProperties musicianFeedProperties() { return new MusicianFeedProperties(); }

        @Bean MusicianFeedDeliveryTokenCodec musicianFeedDeliveryTokenCodec(
                ObjectMapper mapper, MusicianFeedProperties properties) {
            return new MusicianFeedDeliveryTokenCodec(mapper, properties);
        }
    }
}

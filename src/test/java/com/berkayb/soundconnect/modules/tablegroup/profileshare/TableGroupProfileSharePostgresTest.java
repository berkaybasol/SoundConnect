package com.berkayb.soundconnect.modules.tablegroup.profileshare;

import com.berkayb.soundconnect.modules.comment.repository.CommentRepository;
import com.berkayb.soundconnect.modules.comment.support.CommentTargetAccessGuard;
import com.berkayb.soundconnect.modules.engagement.enums.EngagementTargetType;
import com.berkayb.soundconnect.modules.like.repository.LikeRepository;
import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.entity.ListenerProfile;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.enums.ListenerVisibilityMode;
import com.berkayb.soundconnect.modules.role.entity.Role;
import com.berkayb.soundconnect.modules.tablegroup.entity.TableGroup;
import com.berkayb.soundconnect.modules.tablegroup.entity.TableGroupParticipant;
import com.berkayb.soundconnect.modules.tablegroup.enums.ParticipantStatus;
import com.berkayb.soundconnect.modules.tablegroup.enums.TableGroupStatus;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.shared.config.JpaAuditingConfig;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/** Real PostgreSQL fences and migrations; never reads the application's database configuration. */
@DataJpaTest(properties = {"spring.config.location=classpath:/application-test.yml", "spring.config.import=",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.show-sql=false",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect"})
@ActiveProfiles("test")
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = TableGroupProfileSharePostgresTest.Config.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TableGroupProfileSharePostgresTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine")
            .withDatabaseName("table_profile_shares").withUsername("table_test").withPassword("table_test").withReuse(false);

    @Autowired DataSource dataSource;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager manager;
    @Autowired TableGroupProfileShareService shares;
    @Autowired CommentTargetAccessGuard engagement;
    private JdbcTemplate jdbc;
    private Actor owner;
    private Actor participant;
    private UUID tableId;

    @BeforeEach void setup() throws Exception {
        jdbc = new JdbcTemplate(dataSource);
        // Both scripts are deliberately rerun, exercising Hibernate-created
        // installations and repeat deployment without deleting existing shares.
        migrate("2026-09-10-listener-account-erasure.sql");
        migrate("2026-09-10-tablegroup-profile-shares.sql");
        migrate("2026-09-10-tablegroup-profile-share-history.sql");
        owner = actor();
        participant = actor();
        tableId = table(owner.userId(), participant.userId(), ParticipantStatus.ACCEPTED);
    }

    @Test void ownerAndAcceptedParticipantPublishIndependentPostsWithPublicMetadataOnly() {
        var first = publish(owner, tableId, "  Merhaba  ");
        var second = publish(participant, tableId, null);
        assertThat(first.shareId()).isNotEqualTo(second.shareId());
        assertThat(first.note()).isEqualTo("Merhaba");
        assertThat(first.canPublish()).isTrue();
        assertThat(first.tableGroup().acceptedCount()).isEqualTo(2);
        assertThat(first.tableGroup().cityName()).isEqualTo("Ankara");
        assertThat(first.tableGroup().venueName()).isNull();
        assertThat(shares.list(participant.userId(), owner.profileId(), 0, 20).content()).singleElement()
                .satisfies(post -> assertThat(post.shareId()).isEqualTo(first.shareId()));
        assertThat(shares.get(owner.userId(), tableId).shareId()).isEqualTo(first.shareId());
        assertReadable(second.shareId());
    }

    @ParameterizedTest
    @EnumSource(value = ParticipantStatus.class, names = {"PENDING", "REJECTED", "KICKED", "LEFT"})
    void nonAcceptedMembershipCannotPublishOrExposeAnExistingPost(ParticipantStatus status) {
        UUID shareId = publish(participant, tableId, "Kaybolmalı").shareId();
        membership(status);
        assertThat(shares.get(participant.userId(), tableId).canPublish()).isFalse();
        assertThat(shares.get(participant.userId(), tableId).tableGroup()).isNull();
        assertError(() -> publish(participant, tableId, "Kaybolmalı"), ErrorType.TABLE_GROUP_PROFILE_SHARE_FORBIDDEN);
        assertThat(shares.list(owner.userId(), participant.profileId(), 0, 1).totalElements()).isZero();
        assertHidden(shareId);
        shares.delete(participant.userId(), shareId);
        assertThat(count("tbl_table_group", "id", tableId)).isEqualTo(1);
    }

    @Test void outsiderCannotPublishOrReadPrivateMembershipThroughState() {
        var outsider = actor();
        var state = shares.get(outsider.userId(), tableId);
        assertThat(state.canPublish()).isFalse();
        assertThat(state.tableGroup()).isNull();
        assertError(() -> publish(outsider, tableId, null), ErrorType.TABLE_GROUP_PROFILE_SHARE_FORBIDDEN);
    }

    @ParameterizedTest
    @EnumSource(value = TableGroupStatus.class, names = {"INACTIVE", "CANCELLED"})
    void terminalSourceKeepsFinalPublicationAndEngagement(TableGroupStatus status) {
        UUID shareId = publish(owner, tableId, null).shareId();
        UUID root = comment(shareId, null);
        comment(shareId, root);
        like("TABLE_GROUP_POST", shareId);
        jdbc.update("update tbl_table_group set status=? where id=?", status.name(), tableId);
        var state = shares.get(owner.userId(), tableId);
        assertThat(state.canPublish()).isFalse();
        assertThat(state.tableGroup().status()).isEqualTo(status);
        assertThat(state.tableGroup().acceptedCount()).isEqualTo(2);
        assertError(() -> publish(owner, tableId, null), ErrorType.TABLE_GROUP_PROFILE_SHARE_FORBIDDEN);
        assertThat(shares.list(participant.userId(), owner.profileId(), 0, 20).content()).singleElement().satisfies(post -> {
            assertThat(post.shareId()).isEqualTo(shareId);
            assertThat(post.likeCount()).isEqualTo(1);
            assertThat(post.commentCount()).isEqualTo(2);
        });
        assertReadable(shareId);
        shares.delete(owner.userId(), shareId);
        assertThat(count("tbl_comment", "target_id", shareId)).isZero();
        assertThat(count("tbl_like", "target_id", shareId)).isZero();
        assertThat(count("tbl_table_group", "id", tableId)).isEqualTo(1);
    }

    @Test void expiredActiveRowsRemainVisibleBeforeSchedulerRunsAndBeforePagination() {
        UUID archived = publish(participant, tableId, null).shareId();
        jdbc.update("update tbl_table_group set expires_at=now()-interval '1 second' where id=?", tableId);
        UUID otherTable = table(owner.userId(), participant.userId(), ParticipantStatus.ACCEPTED);
        UUID visible = publish(participant, otherTable, "Görünür").shareId();
        var page = shares.list(owner.userId(), participant.profileId(), 0, 1);
        assertThat(page.totalElements()).isEqualTo(2);
        assertThat(page.totalPages()).isEqualTo(2);
        assertThat(page.content()).singleElement().satisfies(post -> assertThat(post.shareId()).isEqualTo(visible));
        assertThat(shares.list(owner.userId(), participant.profileId(), 1, 1).content()).singleElement().satisfies(post -> {
            assertThat(post.shareId()).isEqualTo(archived);
            assertThat(post.tableGroup().status()).isEqualTo(TableGroupStatus.INACTIVE);
        });
        assertReadable(archived);
        assertError(() -> publish(owner, tableId, null), ErrorType.TABLE_GROUP_PROFILE_SHARE_FORBIDDEN);
    }

    @Test void visibleLookupReadsCurrentAcceptedCountAndOnlyRequestedProfilePublications() {
        UUID own = publish(owner, tableId, null).shareId();
        UUID peer = publish(participant, tableId, null).shareId();
        membership(ParticipantStatus.PENDING);
        assertThat(shares.lookup(participant.userId(), owner.profileId(), List.of(own, peer, UUID.randomUUID())))
                .singleElement().satisfies(post -> assertThat(post.tableGroup().acceptedCount()).isEqualTo(1));
        membership(ParticipantStatus.ACCEPTED);
        assertThat(shares.lookup(participant.userId(), owner.profileId(), List.of(own)))
                .singleElement().satisfies(post -> assertThat(post.tableGroup().acceptedCount()).isEqualTo(2));
        shares.delete(owner.userId(), own);
        assertThat(shares.lookup(participant.userId(), owner.profileId(), List.of(own))).isEmpty();
        for (List<UUID> invalid : List.of(List.<UUID>of(), List.of(peer, peer),
                java.util.stream.IntStream.range(0,51).mapToObj(index -> UUID.randomUUID()).toList(), List.of(new UUID(0,0)))) {
            assertError(() -> shares.lookup(owner.userId(), participant.profileId(), invalid), ErrorType.TABLE_GROUP_PROFILE_SHARE_INVALID);
        }
    }

    @Test void expiryBeforeReadFreezesCountBeforeParticipantErasureAndNeverChangesSnapshot() throws Exception {
        var original = publish(owner, tableId, "Hatıra");
        jdbc.update("update tbl_table_group set expires_at=now()-interval '1 second' where id=?", tableId);
        // Account erasure and collection retention can run before the expiry scheduler.
        jdbc.update("delete from tbl_table_group_participants where table_group_id=? and user_id=?", tableId, participant.userId());
        jdbc.update("update tbl_table_group set description='Changed later',max_person_count=6 where id=?", tableId);
        var archived = shares.lookup(participant.userId(), owner.profileId(), List.of(original.shareId())).getFirst();
        assertThat(archived.tableGroup().acceptedCount()).isEqualTo(2);
        assertThat(archived.tableGroup().maxPersonCount()).isEqualTo(4);
        assertThat(archived.tableGroup().description()).isEqualTo(original.tableGroup().description());
        assertThat(archived.tableGroup().status()).isEqualTo(TableGroupStatus.INACTIVE);
        assertThat(archived.publishedAt()).isEqualTo(original.publishedAt());
        assertThat(archived.note()).isEqualTo("Hatıra");
        migrate("2026-09-10-tablegroup-profile-share-history.sql");
        assertThat(shares.lookup(participant.userId(), owner.profileId(), List.of(original.shareId())).getFirst()).isEqualTo(archived);
        assertThatThrownBy(() -> jdbc.update("update tbl_table_group_profile_share set final_source=null where id=?",original.shareId()))
                .hasStackTraceContaining("Final table profile snapshot is immutable");
        assertReadable(original.shareId());
    }

    @Test void terminalEligibilityIsFrozenEvenForHiddenFormerParticipant() {
        UUID own = publish(owner, tableId, null).shareId();
        UUID departed = publish(participant, tableId, null).shareId();
        membership(ParticipantStatus.LEFT);
        jdbc.update("update tbl_table_group set status='CANCELLED' where id=?", tableId);
        membership(ParticipantStatus.ACCEPTED);
        assertThat(shares.get(participant.userId(), tableId).tableGroup()).isNull();
        assertThat(shares.lookup(owner.userId(), participant.profileId(), List.of(departed))).isEmpty();
        assertHidden(departed);
        assertThat(shares.lookup(participant.userId(), owner.profileId(), List.of(own))).singleElement()
                .satisfies(post -> assertThat(post.tableGroup().acceptedCount()).isEqualTo(1));
        shares.delete(participant.userId(), departed);
    }

    @Test void managedHibernateCollectionCleanupPreservesFinalAcceptedCount() {
        UUID shareId = publish(owner, tableId, null).shareId();
        jdbc.update("update tbl_table_group set expires_at=now()-interval '1 second' where id=?", tableId);
        tx(() -> {
            TableGroup group = em.find(TableGroup.class, tableId, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
            group.getParticipants().removeIf(row -> row.getUserId().equals(participant.userId()));
            em.flush();
            return null;
        });
        assertThat(shares.get(owner.userId(), tableId).tableGroup().acceptedCount()).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from tbl_table_group_participants where table_group_id=?",Long.class,tableId)).isEqualTo(1);
        assertReadable(shareId);
    }

    @Test void managedHibernateClosureFreezesBeforeCollectionReplacement() {
        UUID shareId = publish(owner, tableId, null).shareId();
        tx(() -> {
            TableGroup group = em.find(TableGroup.class, tableId, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
            group.setStatus(TableGroupStatus.CANCELLED);
            group.setDescription("Replaced during cleanup");
            group.getParticipants().clear();
            em.flush();
            return null;
        });
        var source = shares.get(owner.userId(), tableId).tableGroup();
        assertThat(source.acceptedCount()).isEqualTo(2);
        assertThat(source.description()).isEqualTo("Birlikte müzik dinleyelim");
        assertThat(source.status()).isEqualTo(TableGroupStatus.CANCELLED);
        assertReadable(shareId);
    }

    @Test void expiryIsFinalizedByEngagementAndGhostStillHidesArchive() {
        UUID shareId = publish(participant, tableId, null).shareId();
        jdbc.update("update tbl_table_group set expires_at=now()-interval '1 second' where id=?", tableId);
        assertReadable(shareId);
        assertThat(jdbc.queryForObject("select final_source_frozen from tbl_table_group_profile_share where id=?",Boolean.class,shareId)).isTrue();
        jdbc.update("update \"tbl_listener-profile\" set visibility_mode='GHOST' where id=?", participant.profileId());
        assertThat(shares.lookup(owner.userId(), participant.profileId(), List.of(shareId))).isEmpty();
        assertHidden(shareId);
        shares.delete(participant.userId(), shareId);
    }

    @Test void migrationBackfillsRetainedHistoryAndRejectsInvalidOrPrivateSnapshotFields() throws Exception {
        UUID archived = publish(owner, tableId, null).shareId();
        jdbc.update("update tbl_table_group set expires_at=now()-interval '1 second' where id=?", tableId);
        assertThat(jdbc.queryForObject("select final_source_frozen from tbl_table_group_profile_share where id=?",Boolean.class,archived)).isFalse();
        migrate("2026-09-10-tablegroup-profile-share-history.sql");
        assertThat(shares.get(owner.userId(),tableId).tableGroup().acceptedCount()).isEqualTo(2);
        UUID openTable = table(owner.userId(),participant.userId(),ParticipantStatus.ACCEPTED);
        UUID openShare = publish(owner,openTable,null).shareId();
        for (String invalid : List.of("'{\"status\":null}'::jsonb", "'{\"participantIds\":[\"private\"]}'::jsonb",
                "'{\"status\":\"ACTIVE\"}'::jsonb", "'{\"acceptedCount\":7}'::jsonb", "'{\"cityName\":null}'::jsonb")) {
            assertThatThrownBy(() -> jdbc.update("update tbl_table_group_profile_share set final_source_frozen=true, final_source="
                    + "(select final_source from tbl_table_group_profile_share where id=?) || jsonb_build_object('id',?::text) || "
                    + invalid + " where id=?",archived,openTable,openShare))
                    .hasStackTraceContaining("ck_table_profile_final_source");
        }
        assertThat(shares.get(owner.userId(),openTable).canPublish()).isTrue();
    }

    @Test void privacyAndRoleCorruptionFailClosedWhileGhostCanRemove() {
        UUID shareId = publish(participant, tableId, null).shareId();
        jdbc.update("update \"tbl_listener-profile\" set visibility_mode='GHOST' where id=?", participant.profileId());
        assertThat(shares.get(participant.userId(), tableId).canPublish()).isFalse();
        assertThat(shares.list(owner.userId(), participant.profileId(), 0, 20).content()).isEmpty();
        assertError(() -> publish(participant, tableId, null), ErrorType.FORBIDDEN_ACCESS);
        assertHidden(shareId);
        shares.delete(participant.userId(), shareId);
        jdbc.update("update \"tbl_listener-profile\" set visibility_mode='STANDARD',visibility_choice_completed=false where id=?", participant.profileId());
        assertError(() -> shares.list(owner.userId(), participant.profileId(), 0, 20), ErrorType.PROFILE_NOT_FOUND);
        jdbc.update("update \"tbl_listener-profile\" set visibility_choice_completed=true where id=?", participant.profileId());
        tx(() -> { em.find(User.class, participant.userId()).getRoles().add(role("ROLE_MUSICIAN")); return null; });
        assertError(() -> publish(participant, tableId, null), ErrorType.FORBIDDEN_ACCESS);
        assertError(() -> shares.list(owner.userId(), participant.profileId(), 0, 20), ErrorType.PROFILE_NOT_FOUND);
    }

    @Test void noteValidationAndRetryNeverMutateAnExistingPublication() {
        for (String bad : List.of("x".repeat(501), "invalid\u0000note", "invalid\uD800note")) {
            assertError(() -> publish(owner, tableId, bad), ErrorType.TABLE_GROUP_PROFILE_SHARE_INVALID);
        }
        String note = "😀".repeat(500);
        var first = publish(owner, tableId, note);
        assertThat(publish(owner, tableId, " " + note + " ").shareId()).isEqualTo(first.shareId());
        assertError(() -> publish(owner, tableId, "Farklı"), ErrorType.TABLE_GROUP_PROFILE_SHARE_ALREADY_EXISTS);
        assertError(() -> shares.delete(participant.userId(), first.shareId()), ErrorType.TABLE_GROUP_PROFILE_SHARE_NOT_FOUND);
        shares.delete(owner.userId(), first.shareId());
        var replacement = publish(owner, tableId, null);
        assertThat(replacement.shareId()).isNotEqualTo(first.shareId());
        assertError(() -> shares.delete(owner.userId(), first.shareId()), ErrorType.TABLE_GROUP_PROFILE_SHARE_NOT_FOUND);
        assertThat(shares.get(owner.userId(), tableId).shareId()).isEqualTo(replacement.shareId());
    }

    @Test void migrationRerunPreservesPostsAndDeletePurgesOnlyTheirOwnEngagement() throws Exception {
        UUID first = publish(owner, tableId, null).shareId();
        UUID other = publish(participant, tableId, null).shareId();
        UUID root = comment(first, null);
        UUID reply = comment(first, root);
        like("TABLE_GROUP_POST", first);
        like("COMMENT", reply);
        like("TABLE_GROUP_POST", other);
        var card = shares.list(participant.userId(), owner.profileId(), 0, 20).content().getFirst();
        assertThat(card.likeCount()).isEqualTo(1);
        assertThat(card.commentCount()).isEqualTo(2);
        assertThat(card.likedByMe()).isTrue();
        migrate("2026-09-10-tablegroup-profile-shares.sql");
        assertThat(shares.get(owner.userId(), tableId).shareId()).isEqualTo(first);
        shares.delete(owner.userId(), first);
        assertThat(count("tbl_like", "target_id", first)).isZero();
        assertThat(count("tbl_like", "target_id", reply)).isZero();
        assertThat(count("tbl_comment", "target_id", first)).isZero();
        assertThat(count("tbl_like", "target_id", other)).isEqualTo(1);
        assertThat(shares.get(participant.userId(), tableId).shareId()).isEqualTo(other);
        jdbc.update("delete from tbl_table_group_participants where table_group_id=?", tableId);
        jdbc.update("delete from tbl_table_group where id=?", tableId);
        assertThat(count("tbl_table_group_profile_share", "id", other)).isZero();
        assertThat(count("tbl_like", "target_id", other)).isZero();
    }

    @Test void duplicateConcurrentPublishesHaveOneIdentity() throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> publish(participant, tableId, "Aynı"));
            var second = pool.submit(() -> publish(participant, tableId, "Aynı"));
            assertThat(first.get(10, TimeUnit.SECONDS).shareId()).isEqualTo(second.get(10, TimeUnit.SECONDS).shareId());
        }
    }

    @Test void migrationWidensLegacyEnumChecksAndPreservesUnrelatedChecks() throws Exception {
        UUID shareId = publish(owner,tableId,null).shareId();
        jdbc.execute("alter table tbl_like add constraint test_old_like_enum check(target_type in ('OVERTHINKING','MEDIA','EVENT','EVENT_POST','COMMENT')) not valid");
        jdbc.execute("alter table tbl_like add constraint test_like_target_not_zero check(target_id<>'00000000-0000-0000-0000-000000000000'::uuid) not valid");
        try {
            migrate("2026-09-10-tablegroup-profile-shares.sql");
            like("TABLE_GROUP_POST",shareId);
            assertThatThrownBy(() -> like("TABLE_GROUP_POST",new UUID(0,0)))
                    .hasStackTraceContaining("test_like_target_not_zero");
            assertThat(count("tbl_like","target_id",shareId)).isEqualTo(1);
        } finally {
            jdbc.execute("alter table tbl_like drop constraint test_old_like_enum");
            jdbc.execute("alter table tbl_like drop constraint test_like_target_not_zero");
        }
    }

    @Test void databaseWriteFenceRejectsStalePublicationAfterAccountErasure() {
        jdbc.update("update tbl_user set erased_at=now(),status='INACTIVE' where id=?",participant.userId());
        assertError(() -> publish(participant,tableId,null),ErrorType.UNAUTHORIZED);
        assertThatThrownBy(() -> jdbc.update("insert into tbl_table_group_profile_share(id,owner_user_id,listener_profile_id,table_group_id,published_at) values(?,?,?,?,now())",
                UUID.randomUUID(),participant.userId(),participant.profileId(),tableId))
                .hasStackTraceContaining("Account was permanently erased");
    }

    @Test void membershipRemovalWinsAgainstPublishWaitingForAggregateLock() throws Exception {
        try (var pool = Executors.newSingleThreadExecutor()) {
            var pending = new AtomicReference<Future<ErrorType>>();
            tx(() -> {
                jdbc.queryForObject("select id from tbl_table_group where id=? for update", UUID.class, tableId);
                membership(ParticipantStatus.KICKED);
                pending.set(pool.submit(() -> errorOf(() -> publish(participant, tableId, null))));
                await().atMost(Duration.ofSeconds(5)).until(() -> jdbc.queryForObject(
                        "select count(*) from pg_stat_activity where datname=current_database() and wait_event_type='Lock' and query like '%tbl_table_group%'",
                        Long.class) > 0);
                return null;
            });
            assertThat(pending.get().get(10, TimeUnit.SECONDS)).isEqualTo(ErrorType.TABLE_GROUP_PROFILE_SHARE_FORBIDDEN);
        }
        assertThat(shares.get(participant.userId(), tableId).publishedOnProfile()).isFalse();
    }

    @Test void requestValidationBoundsPageAndRejectsMissingIdentifiers() {
        for (int[] bounds : List.of(new int[]{-1,20},new int[]{1001,20},new int[]{0,0},new int[]{0,51})) {
            assertError(() -> shares.list(owner.userId(), participant.profileId(), bounds[0], bounds[1]), ErrorType.TABLE_GROUP_PROFILE_SHARE_INVALID);
        }
        assertError(() -> shares.publish(owner.userId(), new UUID(0,0), new TableGroupProfileShareUpdate(null)), ErrorType.TABLE_GROUP_PROFILE_SHARE_INVALID);
        assertError(() -> shares.publish(owner.userId(), tableId, null), ErrorType.TABLE_GROUP_PROFILE_SHARE_INVALID);
        assertError(() -> shares.get(null, tableId), ErrorType.UNAUTHORIZED);
    }

    private TableGroupProfileShareResponse.State publish(Actor actor, UUID id, String note) {
        return shares.publish(actor.userId(), id, new TableGroupProfileShareUpdate(note));
    }
    private void membership(ParticipantStatus status) {
        jdbc.update("update tbl_table_group_participants set status=? where table_group_id=? and user_id=?", status.name(), tableId, participant.userId());
    }
    private Actor actor() {
        return tx(() -> {
            User user = persist(User.builder().username("u" + UUID.randomUUID().toString().replace("-", "").substring(0,16))
                    .email(UUID.randomUUID() + "@test.invalid").password("unused").status(UserStatus.ACTIVE)
                    .emailVerified(true).roles(new HashSet<>(Set.of(role("ROLE_LISTENER")))).build());
            ListenerProfile profile = persist(ListenerProfile.builder().user(user).name("Test")
                    .visibilityMode(ListenerVisibilityMode.STANDARD).visibilityChoiceCompleted(true).build());
            return new Actor(user.getId(), profile.getId());
        });
    }
    private Role role(String name) {
        return em.createQuery("select r from Role r where r.name=:name", Role.class).setParameter("name",name)
                .getResultStream().findFirst().orElseGet(() -> persist(Role.builder().name(name).build()));
    }
    private UUID table(UUID ownerId, UUID userId, ParticipantStatus status) {
        return tx(() -> {
            City city = em.createQuery("select c from City c where c.name='Ankara'", City.class).getResultStream()
                    .findFirst().orElseGet(() -> persist(City.builder().name("Ankara").build()));
            TableGroup group = persist(TableGroup.builder().ownerId(ownerId).createRequestKey(UUID.randomUUID())
                    .city(city).description("Birlikte müzik dinleyelim").maxPersonCount(4).ageMin(18).ageMax(60)
                    .startAt(Instant.now()).meetingAt(Instant.now().plusSeconds(600)).expiresAt(Instant.now().plusSeconds(3600))
                    .status(TableGroupStatus.ACTIVE).participants(new HashSet<>(Set.of(
                            TableGroupParticipant.builder().userId(ownerId).joinedAt(Instant.now()).status(ParticipantStatus.ACCEPTED).build(),
                            TableGroupParticipant.builder().userId(userId).joinedAt(Instant.now()).status(status).joinNote("Private application note").build())))
                    .build());
            return group.getId();
        });
    }
    private UUID comment(UUID shareId, UUID parentId) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into tbl_comment(id,created_at,updated_at,user_id,target_type,target_id,text,is_deleted,parent_comment_id) values(?,now(),now(),?,'TABLE_GROUP_POST',?,'Comment',false,?)",
                id, participant.userId(), shareId, parentId);
        return id;
    }
    private void like(String type, UUID id) {
        jdbc.update("insert into tbl_like(id,created_at,updated_at,user_id,target_type,target_id) values(?,now(),now(),?,?,?)",
                UUID.randomUUID(), participant.userId(), type, id);
    }
    private void assertReadable(UUID id) { tx(() -> { engagement.requireReadable(EngagementTargetType.TABLE_GROUP_POST,id); return null; }); }
    private void assertHidden(UUID id) { assertError(() -> assertReadable(id), ErrorType.ENGAGEMENT_NOT_FOUND); }
    private long count(String table, String column, UUID id) { return jdbc.queryForObject("select count(*) from " + table + " where " + column + "=?", Long.class,id); }
    private void migrate(String filename) throws Exception {
        try (var connection=dataSource.getConnection(); var statement=connection.createStatement()) {
            statement.execute(Files.readString(Path.of("scripts/db",filename)));
        }
    }
    private ErrorType errorOf(Runnable action) { try { action.run(); return null; } catch (SoundConnectException error) { return error.getErrorType(); } }
    private void assertError(Runnable action, ErrorType type) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(SoundConnectException.class, error -> assertThat(error.getErrorType()).isEqualTo(type));
    }
    private <T> T persist(T entity) { em.persist(entity); return entity; }
    private <T> T tx(Supplier<T> action) { return new TransactionTemplate(manager).execute(status -> action.get()); }
    private record Actor(UUID userId, UUID profileId) { }

    @Configuration(proxyBeanMethods = false)
    @EnableJpaRepositories(basePackageClasses = {TableGroupProfileShareRepository.class, CommentRepository.class, LikeRepository.class})
    @EntityScan(basePackages = "com.berkayb.soundconnect")
    @Import({JpaAuditingConfig.class, TableGroupProfileShareService.class, CommentTargetAccessGuard.class})
    static class Config {
        @Bean DataSource dataSource() {
            if (!POSTGRES.isRunning()) throw new IllegalStateException("Disposable PostgreSQL must be running");
            return new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        }
    }
}

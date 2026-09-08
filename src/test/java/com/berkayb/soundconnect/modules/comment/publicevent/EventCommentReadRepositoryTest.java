package com.berkayb.soundconnect.modules.comment.publicevent;

import com.berkayb.soundconnect.modules.comment.entity.Comment;
import com.berkayb.soundconnect.modules.comment.dto.response.CommentResponseDto;
import com.berkayb.soundconnect.modules.comment.dto.response.CommentReplyResponseDto;
import com.berkayb.soundconnect.modules.comment.mapper.CommentMapper;
import com.berkayb.soundconnect.modules.comment.repository.CommentRepository;
import com.berkayb.soundconnect.modules.comment.service.CommentServiceImpl;
import com.berkayb.soundconnect.modules.comment.support.CommentEntityFinder;
import com.berkayb.soundconnect.modules.engagement.enums.EngagementTargetType;
import com.berkayb.soundconnect.modules.engagement.service.EngagementTargetValidator;
import com.berkayb.soundconnect.modules.event.entity.Event;
import com.berkayb.soundconnect.modules.event.enums.EventOrigin;
import com.berkayb.soundconnect.modules.event.enums.EventPerformerApprovalStatus;
import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.location.entity.District;
import com.berkayb.soundconnect.modules.location.entity.Neighborhood;
import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.overthinking.repository.OverthinkingPostRepository;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.repository.ListenerProfileRepository;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.entity.MusicianProfile;
import com.berkayb.soundconnect.modules.profile.shared.avatar.PersonalProfileAvatarRepository;
import com.berkayb.soundconnect.modules.profile.shared.identity.GhostListenerIdentityBatchResolver;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.modules.user.support.UserEntityFinder;
import com.berkayb.soundconnect.modules.venue.entity.Venue;
import com.berkayb.soundconnect.modules.venue.enums.VenueStatus;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
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
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** The sole fixture database is a fresh explicit Testcontainers datasource, never application configuration. */
@DataJpaTest(properties = {
        "spring.config.location=classpath:/application-test.yml", "spring.config.import=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
        "spring.jpa.properties.hibernate.generate_statistics=true"
})
@ActiveProfiles("test")
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = EventCommentReadRepositoryTest.RepositoryConfiguration.class)
@Import({EventCommentReadService.class, CommentServiceImpl.class, CommentEntityFinder.class,
        GhostListenerIdentityBatchResolver.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class EventCommentReadRepositoryTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine")
            .withDatabaseName("event_comments_test").withUsername("event_comments_test")
            .withPassword("event_comments_test").withReuse(false);

    @Autowired DataSource dataSource;
    @Autowired EntityManager em;
    @Autowired EventCommentReadRepository repository;
    @Autowired EventCommentReadService readService;
    @MockitoSpyBean ListenerProfileRepository identityLocks;
    @MockitoBean CommentMapper mapper;
    @MockitoBean MediaAssetService media;
    @MockitoBean EngagementTargetValidator validator;
    @MockitoBean UserEntityFinder users;
    @MockitoBean OverthinkingPostRepository posts;
    private Venue venue;

    @BeforeEach
    void requireIsolatedDatasourceBeforeAnyFixtureWrite() throws SQLException {
        assertThat(POSTGRES.isRunning()).isTrue();
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getURL()).isEqualTo(POSTGRES.getJdbcUrl());
            assertThat(connection.getCatalog()).isEqualTo(POSTGRES.getDatabaseName());
        }
        var city = persist(City.builder().name("City " + UUID.randomUUID()).build());
        var district = persist(District.builder().name("Çankaya").city(city).build());
        var neighborhood = persist(Neighborhood.builder().name("Çayyolu").district(district).build());
        venue = persist(Venue.builder().name("Venue").owner(user()).status(VenueStatus.APPROVED)
                .address("Address").city(city).district(district).neighborhood(neighborhood).build());
    }

    @Test
    void publicPastAndFutureEventsRemainReadableWithoutHydratingEntityGraphs() {
        var past = event(LocalDate.of(2020, 1, 1));
        var future = event(LocalDate.of(2030, 1, 1));
        em.flush(); em.clear();
        var statistics = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        assertThat(repository.existsPublicEvent(past.getId())).isTrue();
        assertThat(repository.existsPublicEvent(future.getId())).isTrue();
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
        assertThat(statistics.getEntityLoadCount()).isZero();
        assertThat(statistics.getCollectionLoadCount()).isZero();
    }

    @ParameterizedTest
    @EnumSource(value = UserStatus.class, names = "ACTIVE", mode = EnumSource.Mode.EXCLUDE)
    void everyNonActiveOwnerStatusIsExcluded(UserStatus status) {
        var event = event(LocalDate.of(2026, 9, 8));
        venue.getOwner().setStatus(status);
        em.flush();
        assertThat(repository.existsPublicEvent(event.getId())).isFalse();
    }

    @Test
    void unverifiedOwnerIsExcluded() {
        var event = event(LocalDate.of(2026, 9, 8));
        venue.getOwner().setEmailVerified(false);
        em.flush();
        assertThat(repository.existsPublicEvent(event.getId())).isFalse();
    }

    @Test
    void unapprovedVenueIsExcluded() {
        var event = event(LocalDate.of(2026, 9, 8));
        venue.setStatus(VenueStatus.PENDING);
        em.flush();
        assertThat(repository.existsPublicEvent(event.getId())).isFalse();
    }

    @Test
    void missingDeletedAndLegacyMusicianEventsAreExcluded() {
        assertThat(repository.existsPublicEvent(UUID.randomUUID())).isFalse();
        var removed = event(LocalDate.of(2026, 9, 8));
        em.flush();
        UUID deletedId = removed.getId();
        em.remove(removed); em.flush();
        assertThat(repository.existsPublicEvent(deletedId)).isFalse();
        var musician = persist(MusicianProfile.builder().user(user()).build());
        var legacy = persist(Event.builder().title("Legacy").venue(venue).eventDate(LocalDate.of(2026, 9, 8))
                .startTime(LocalTime.NOON).eventOrigin(EventOrigin.MUSICIAN)
                .organizerUserId(musician.getUser().getId()).musicianProfile(musician)
                .performerApprovalStatus(EventPerformerApprovalStatus.APPROVED).profileCalendarApproved(true).build());
        em.flush();
        assertThat(repository.existsPublicEvent(legacy.getId())).isFalse();
    }

    @Test
    void replyParentMustBeAnExactEventRootWhileSoftDeletedRootsRemainValid() {
        UUID eventId = UUID.randomUUID(), otherEventId = UUID.randomUUID();
        var root = comment(EngagementTargetType.EVENT, eventId, null, false);
        var deletedRoot = comment(EngagementTargetType.EVENT, eventId, null, true);
        var otherEvent = comment(EngagementTargetType.EVENT, otherEventId, null, false);
        var media = comment(EngagementTargetType.MEDIA, eventId, null, false);
        var overthinking = comment(EngagementTargetType.OVERTHINKING, eventId, null, false);
        var reply = comment(EngagementTargetType.EVENT, eventId, root, false);
        em.flush(); em.clear();
        assertThat(repository.existsEventRootComment(eventId, root.getId())).isTrue();
        assertThat(repository.existsEventRootComment(eventId, deletedRoot.getId())).isTrue();
        assertThat(repository.existsEventRootComment(eventId, otherEvent.getId())).isFalse();
        assertThat(repository.existsEventRootComment(eventId, media.getId())).isFalse();
        assertThat(repository.existsEventRootComment(eventId, overthinking.getId())).isFalse();
        assertThat(repository.existsEventRootComment(eventId, reply.getId())).isFalse();
        assertThat(repository.existsEventRootComment(eventId, UUID.randomUUID())).isFalse();
    }

    @Test
    void nonEmptyReadsUseExistingPrivacyLocksAndDeletedTextMappingInsideTheirOwnTransaction() {
        var event = event(LocalDate.of(2026, 9, 8));
        var root = comment(EngagementTargetType.EVENT, event.getId(), null, true);
        var reply = comment(EngagementTargetType.EVENT, event.getId(), root, true);
        UUID authorId = venue.getOwner().getId();
        when(mapper.toCommentResponseDto(any(), anyInt(), anyBoolean(), any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
            Comment comment = invocation.getArgument(0);
            return new CommentResponseDto(comment.getId(), null, false, comment.getText(), comment.isDeleted(),
                    null, invocation.getArgument(1), comment.getCreatedAt());
        });
        when(mapper.toCommentReplyResponseDto(any(), anyBoolean(), any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
            Comment comment = invocation.getArgument(0);
            return new CommentReplyResponseDto(comment.getId(), null, false, comment.getText(), comment.isDeleted(),
                    comment.getParentComment().getId(), comment.getCreatedAt());
        });
        // Commit only into this class's disposable database, then let the actual adapter create its own transaction.
        // Remaining tests use independent UUID fixtures. Testcontainers removes this entire fixture database.
        TestTransaction.flagForCommit();
        TestTransaction.end();
        assertThat(readService.getComments(null, event.getId(), 0, 20).getContent()).singleElement().satisfies(dto -> {
            assertThat(dto.id()).isEqualTo(root.getId());
            assertThat(dto.text()).isEqualTo("[Bu yorum silinmiştir]");
            assertThat(dto.replyCount()).isEqualTo(1);
        });
        assertThat(readService.getReplies(null, event.getId(), root.getId(), 0, 20).getContent())
                .singleElement().satisfies(dto -> {
                    assertThat(dto.id()).isEqualTo(reply.getId());
                    assertThat(dto.text()).isEqualTo("[Bu yorum silinmiştir]");
                });
        verify(identityLocks, times(2)).findAllByUserIdInForVisibilityRead(List.of(authorId));
    }

    private Comment comment(EngagementTargetType type, UUID target, Comment parent, boolean deleted) {
        return persist(Comment.builder().user(venue.getOwner()).targetType(type).targetId(target)
                .parentComment(parent).text("Comment").deleted(deleted).build());
    }

    private Event event(LocalDate date) {
        return persist(Event.builder().title("Event").venue(venue).eventDate(date).startTime(LocalTime.NOON).build());
    }

    private User user() {
        return persist(User.builder().username("user" + UUID.randomUUID().toString().replace("-", "").substring(0, 10))
                .email(UUID.randomUUID() + "@test.invalid").password("unused-test-password")
                .status(UserStatus.ACTIVE).emailVerified(true).build());
    }

    private <T> T persist(T entity) { em.persist(entity); return entity; }

    @Configuration(proxyBeanMethods = false)
    @EnableJpaAuditing
    @EnableJpaRepositories(basePackageClasses = {EventCommentReadRepository.class, CommentRepository.class,
            ListenerProfileRepository.class, PersonalProfileAvatarRepository.class})
    @EntityScan(basePackages = "com.berkayb.soundconnect")
    static class RepositoryConfiguration {
        @Bean DataSource dataSource() {
            if (!POSTGRES.isRunning()) throw new IllegalStateException("Disposable PostgreSQL must be running");
            return new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        }
    }
}

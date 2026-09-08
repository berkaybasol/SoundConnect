package com.berkayb.soundconnect.modules.comment.service;

import com.berkayb.soundconnect.modules.comment.dto.request.CommentCreateRequestDto;
import com.berkayb.soundconnect.modules.comment.abuse.CommentBurstGuard;
import com.berkayb.soundconnect.modules.comment.entity.Comment;
import com.berkayb.soundconnect.modules.comment.mapper.CommentMapper;
import com.berkayb.soundconnect.modules.comment.publicevent.EventCommentReadRepository;
import com.berkayb.soundconnect.modules.comment.publicevent.EventCommentReadService;
import com.berkayb.soundconnect.modules.like.repository.LikeRepository;
import com.berkayb.soundconnect.modules.like.service.CommentLikeAccessGuard;
import com.berkayb.soundconnect.modules.like.service.LikeServiceImpl;
import com.berkayb.soundconnect.modules.like.entity.Like;
import com.berkayb.soundconnect.modules.engagement.service.EngagementTargetValidator;
import com.berkayb.soundconnect.modules.engagement.service.MediaEngagementCleanupService;
import com.berkayb.soundconnect.modules.comment.repository.*;
import com.berkayb.soundconnect.modules.comment.support.*;
import com.berkayb.soundconnect.modules.engagement.enums.EngagementTargetType;
import com.berkayb.soundconnect.modules.event.entity.Event;
import com.berkayb.soundconnect.modules.location.entity.*;
import com.berkayb.soundconnect.modules.media.entity.MediaAsset;
import com.berkayb.soundconnect.modules.media.enums.*;
import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.overthinking.entity.OverthinkingPost;
import com.berkayb.soundconnect.modules.overthinking.enums.OverthinkingVisibilityType;
import com.berkayb.soundconnect.modules.overthinking.repository.OverthinkingPostRepository;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.entity.ListenerProfile;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.enums.ListenerVisibilityMode;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.entity.MusicianProfile;
import com.berkayb.soundconnect.modules.profile.OrganizerProfile.entity.OrganizerProfile;
import com.berkayb.soundconnect.modules.profile.ProducerProfile.entity.ProducerProfile;
import com.berkayb.soundconnect.modules.profile.StudioProfile.entity.StudioProfile;
import com.berkayb.soundconnect.modules.profile.VenueProfile.entity.VenueProfile;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.modules.user.support.UserEntityFinder;
import com.berkayb.soundconnect.modules.venue.entity.Venue;
import com.berkayb.soundconnect.modules.venue.enums.VenueStatus;
import com.berkayb.soundconnect.shared.exception.*;
import com.berkayb.soundconnect.shared.config.JpaAuditingConfig;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.*;
import org.mapstruct.factory.Mappers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.jpa.repository.config.*;
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
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Fixtures exist only in the explicitly verified, non-reused disposable PostgreSQL database. */
@DataJpaTest(properties={"spring.config.location=classpath:/application-test.yml","spring.config.import=",
        "spring.jpa.hibernate.ddl-auto=create-drop","spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
        "spring.jpa.properties.hibernate.generate_statistics=true","spring.jpa.show-sql=false"})
@ActiveProfiles("test") @Testcontainers @AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes=CommentServicePostgresTest.Config.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional(propagation=Propagation.NOT_SUPPORTED)
class CommentServicePostgresTest {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:16.4-alpine")
            .withDatabaseName("comments_test").withUsername("comments_test").withPassword("comments_test").withReuse(false);
    @Autowired DataSource dataSource; @Autowired EntityManager em; @Autowired PlatformTransactionManager manager;
    @Autowired CommentServiceImpl service; @Autowired CommentTargetAccessGuard access;
    @Autowired CommentAuthorBatchResolver authors; @Autowired CommentRepository repository;
    @Autowired EventCommentReadService publicEventComments;
    @Autowired LikeServiceImpl likeService; @Autowired LikeRepository likeRepository;
    @Autowired MediaEngagementCleanupService mediaCleanup;
    @MockitoBean EngagementTargetValidator legacyLikeTargets;
    @MockitoBean MediaAssetService media; @MockitoBean UserEntityFinder users;
    @MockitoBean CommentBurstGuard burstGuard;
    JdbcTemplate jdbc; User actor; Venue venue; UUID event;

    @BeforeEach void setup() throws Exception {
        assertThat(POSTGRES.isRunning()).isTrue();
        try(var connection=dataSource.getConnection()) {
            assertThat(connection.getMetaData().getURL()).isEqualTo(POSTGRES.getJdbcUrl());
            assertThat(connection.getCatalog()).isEqualTo(POSTGRES.getDatabaseName());
        }
        jdbc=new JdbcTemplate(dataSource);
        when(users.getUser(any())).thenAnswer(i -> em.getReference(User.class, i.getArgument(0)));
        tx(() -> {
            actor=user();
            var city=persist(City.builder().name("City "+UUID.randomUUID()).build());
            var district=persist(District.builder().name("District").city(city).build());
            var neighborhood=persist(Neighborhood.builder().name("Neighborhood").district(district).build());
            venue=persist(Venue.builder().name("Venue").owner(user()).status(VenueStatus.APPROVED)
                    .address("Address").city(city).district(district).neighborhood(neighborhood).build());
            event=persist(Event.builder().title("Event").venue(venue).eventDate(LocalDate.of(2026,9,8)).startTime(LocalTime.NOON).build()).getId();
            return null;
        });
    }

    @Test void hiddenEventCannotBeReadRepliedToOrCommentedThroughGenericRoutes() {
        UUID root=create(event,null);
        jdbc.update("update tbl_venues set status='PENDING' where id=?",venue.getId());
        assertHidden(() -> service.getComments(actor.getId(),EngagementTargetType.EVENT,event,PageRequest.of(0,20)));
        assertHidden(() -> service.getReplies(actor.getId(),root,PageRequest.of(0,20)));
        assertHidden(() -> create(event,null));
        assertHidden(() -> create(event,root));
        service.deleteComment(actor.getId(),root); // own removal remains possible after unpublication
        assertThat(jdbc.queryForObject("select is_deleted from tbl_comment where id=?",Boolean.class,root)).isTrue();
    }

    @Test void eventEligibilityRejectsUnverifiedOrInactiveVenueOwner() {
        jdbc.update("update tbl_user set email_verified=false where id=?",venue.getOwner().getId());
        assertHidden(() -> create(event,null));
        jdbc.update("update tbl_user set email_verified=true,status='INACTIVE' where id=?",venue.getOwner().getId());
        assertHidden(() -> service.getComments(actor.getId(),EngagementTargetType.EVENT,event,PageRequest.of(0,20)));
    }

    @Test void mediaCommentsRespectPrivatePendingDeletedAndGhostOwnerVisibility() {
        UUID profile=tx(() -> persist(ListenerProfile.builder().user(em.getReference(User.class,actor.getId()))
                .visibilityChoiceCompleted(true).build()).getId());
        UUID asset=tx(() -> persist(MediaAsset.builder().kind(MediaKind.IMAGE).status(MediaStatus.READY).visibility(MediaVisibility.PUBLIC)
                .ownerType(MediaOwnerType.LISTENER_PROFILE).ownerId(profile).size(100L).mimeType("image/jpeg")
                .sourceUrl("https://cdn.test/image.jpg").build()).getId());
        UUID root=service.createComment(actor.getId(),EngagementTargetType.MEDIA,asset,new CommentCreateRequestDto("media",null)).id();
        for(String visibility : List.of("PRIVATE","UNLISTED")) {
            jdbc.update("update tbl_media_asset set visibility=? where id=?",visibility,asset);
            assertHidden(() -> service.getReplies(actor.getId(),root,PageRequest.of(0,20)));
        }
        jdbc.update("update tbl_media_asset set visibility='PUBLIC',status='DELETION_PENDING' where id=?",asset);
        assertHidden(() -> service.getComments(actor.getId(),EngagementTargetType.MEDIA,asset,PageRequest.of(0,20)));
        jdbc.update("update tbl_media_asset set status='READY' where id=?",asset);
        jdbc.update("update \"tbl_listener-profile\" set visibility_mode='GHOST' where id=?",profile);
        assertHidden(() -> service.getReplies(actor.getId(),root,PageRequest.of(0,20)));
        jdbc.update("update \"tbl_listener-profile\" set visibility_mode='STANDARD',visibility_choice_completed=false where id=?",profile);
        assertHidden(() -> service.createComment(actor.getId(),EngagementTargetType.MEDIA,asset,new CommentCreateRequestDto("no",null)));
    }

    @Test void deletedOverthinkingTargetDoesNotRevealFormerAnonymousAuthor() {
        UUID post=tx(() -> persist(OverthinkingPost.builder().author(em.getReference(User.class,actor.getId()))
                .title("Anonymous").content("Text").visibilityType(OverthinkingVisibilityType.ANONYMOUS).build()).getId());
        UUID root=service.createComment(actor.getId(),EngagementTargetType.OVERTHINKING,post,new CommentCreateRequestDto("author",null)).id();
        var viewer=UUID.randomUUID();
        var before=service.getComments(viewer,EngagementTargetType.OVERTHINKING,post,PageRequest.of(0,20)).getContent().getFirst();
        assertThat(before.user().id()).isNull();
        assertThat(before.anonymousAuthor()).isTrue();
        jdbc.update("delete from tbl_overthinking_post where id=?",post);
        assertHidden(() -> service.getComments(viewer,EngagementTargetType.OVERTHINKING,post,PageRequest.of(0,20)));
        assertHidden(() -> service.getReplies(viewer,root,PageRequest.of(0,20)));
    }

    @Test void equalTimestampRootAndReplyPaginationHasNoDuplicatesOrMissingLastPage() {
        List<UUID> roots=new ArrayList<>(), replies=new ArrayList<>();
        tx(() -> { for(int i=0;i<5;i++) roots.add(comment(actor,event,null).getId());
            var root=em.getReference(Comment.class,roots.getFirst());
            for(int i=0;i<5;i++) replies.add(comment(actor,event,root).getId()); return null; });
        jdbc.update("update tbl_comment set created_at='2026-09-08 12:00:00' where target_id=?",event);
        var rootExpected=jdbc.queryForList("select id from tbl_comment where target_id=? and parent_comment_id is null order by created_at desc,id desc",UUID.class,event);
        var replyExpected=jdbc.queryForList("select id from tbl_comment where parent_comment_id=? order by created_at,id",UUID.class,roots.getFirst());
        List<UUID> rootActual=new ArrayList<>(),replyActual=new ArrayList<>();
        for(int p=0;p<3;p++) {
            var page=service.getComments(actor.getId(),EngagementTargetType.EVENT,event,PageRequest.of(p,2));
            rootActual.addAll(page.map(c -> c.id()).getContent());
            replyActual.addAll(service.getReplies(actor.getId(),roots.getFirst(),PageRequest.of(p,2)).map(c -> c.id()).getContent());
            assertThat(page.isLast()).isEqualTo(p==2);
        }
        assertThat(rootActual).containsExactlyElementsOf(rootExpected);
        assertThat(replyActual).containsExactlyElementsOf(replyExpected);
    }

    @Test void fiftyDistinctListenerAuthorsUseBoundedScalarQueriesWithoutUserProfileHydration() {
        tx(() -> { for(int i=0;i<50;i++) { var author=user(); persist(ListenerProfile.builder().user(author).visibilityChoiceCompleted(true).build()); comment(author,event,null); } return null; });
        var stats=em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics(); stats.clear();
        var page=service.getComments(actor.getId(),EngagementTargetType.EVENT,event,PageRequest.of(0,50));
        assertThat(page.getContent()).hasSize(50).allSatisfy(c -> assertThat(c.user().username()).startsWith("user"));
        assertThat(stats.getPrepareStatementCount()).isLessThanOrEqualTo(8);
        assertThat(stats.getEntityLoadCount()).isEqualTo(50); // comments only
        assertThat(stats.getCollectionLoadCount()).isZero();
    }

    @Test void fourHundredRootsAndRepliesRemainCompleteAndBoundedAcrossAuthenticatedAndPublicPages() {
        List<UUID> rootIds=new ArrayList<>(), replyIds=new ArrayList<>();
        UUID otherEvent=UUID.randomUUID();
        tx(() -> {
            List<User> pageAuthors=new ArrayList<>();
            for(int i=0;i<400;i++) {
                var author=user();
                persist(ListenerProfile.builder().user(author).visibilityChoiceCompleted(true).build());
                pageAuthors.add(author);
                rootIds.add(comment(author,event,null).getId());
            }
            var root=em.getReference(Comment.class,rootIds.getFirst());
            for(var author : pageAuthors) replyIds.add(comment(author,event,root).getId());
            for(int i=0;i<400;i++) {
                // Likes span every page; a same-UUID non-COMMENT target must not inflate counts.
                persist(Like.builder().user(em.getReference(User.class,actor.getId())).targetType(EngagementTargetType.COMMENT)
                        .targetId(rootIds.get(i)).build());
                persist(Like.builder().user(pageAuthors.get(i)).targetType(EngagementTargetType.COMMENT)
                        .targetId(rootIds.get(i)).build());
                persist(Like.builder().user(em.getReference(User.class,actor.getId())).targetType(EngagementTargetType.COMMENT)
                        .targetId(replyIds.get(i)).build());
                persist(Like.builder().user(pageAuthors.get(i)).targetType(EngagementTargetType.MEDIA)
                        .targetId(rootIds.get(i)).build());
            }
            // Polymorphic target IDs must not let another target type enter the event root page.
            persist(Comment.builder().user(em.getReference(User.class,actor.getId()))
                    .targetType(EngagementTargetType.MEDIA).targetId(event).text("other type").build());
            var otherRoot=comment(actor,otherEvent,null);
            comment(actor,otherEvent,otherRoot);
            return null;
        });
        jdbc.update("update tbl_comment set created_at='2026-09-08 12:00:00' where target_id=?",event);
        // Most timestamps tie; the boundary rows also verify that date remains the primary sort key.
        jdbc.update("update tbl_comment set created_at='2026-09-08 12:01:00',is_deleted=true where id=?",rootIds.getFirst());
        jdbc.update("update tbl_comment set created_at='2026-09-08 11:59:00',is_deleted=true where id=?",replyIds.getFirst());
        jdbc.update("update tbl_comment set created_at='2026-09-08 12:01:00' where id=?",replyIds.getLast());
        var expectedRoots=jdbc.queryForList("select id from tbl_comment where target_type='EVENT' and target_id=? "
                +"and parent_comment_id is null order by created_at desc,id desc",UUID.class,event);
        var expectedReplies=jdbc.queryForList("select id from tbl_comment where parent_comment_id=? "
                +"order by created_at,id",UUID.class,rootIds.getFirst());
        assertThat(expectedRoots).hasSize(400);
        assertThat(expectedReplies).hasSize(400);

        var stats=em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        for(String path : List.of("generic authenticated","public guest","public authenticated")) {
            UUID viewer=path.equals("public guest") ? null : actor.getId();
            boolean publicPath=path.startsWith("public");
            for(int size : List.of(20,37,50)) {
                List<UUID> actualRoots=new ArrayList<>(), actualReplies=new ArrayList<>();
                long maxRootQueries=0, maxReplyQueries=0;
                int pageCount=(400+size-1)/size;
                // Also read the first page beyond the last: it must stay empty with an accurate total.
                for(int number=0;number<=pageCount;number++) {
                    stats.clear();
                    var roots=publicPath
                            ? publicEventComments.getComments(viewer,event,number,size)
                            : service.getComments(viewer,EngagementTargetType.EVENT,event,
                                    PageRequest.of(number,size,Sort.by("text"))); // caller cannot override stable order
                    maxRootQueries=Math.max(maxRootQueries,stats.getPrepareStatementCount());
                    assertThat(stats.getPrepareStatementCount()).as("%s roots page %s/%s",path,number,size)
                            .isLessThanOrEqualTo(publicPath ? 8 : 7);
                    assertThat(stats.getEntityLoadCount()).as("only the requested root page is hydrated")
                            .isEqualTo(roots.getNumberOfElements());
                    assertThat(stats.getCollectionLoadCount()).isZero();
                    assertLargePage(roots,number,size);
                    roots.forEach(root -> {
                        assertThat(root.parentCommentId()).isNull();
                        assertThat(root.replyCount()).isEqualTo(root.id().equals(rootIds.getFirst()) ? 400 : 0);
                        assertThat(root.user().username()).startsWith("user");
                        assertThat(root.likeCount()).isEqualTo(root.deleted() ? 0 : 2);
                        assertThat(root.likedByMe()).isEqualTo(!root.deleted() && viewer!=null);
                        if(root.id().equals(rootIds.getFirst())) {
                            assertThat(root.deleted()).isTrue();
                            assertThat(root.text()).isEqualTo("[Bu yorum silinmiştir]");
                        }
                    });
                    actualRoots.addAll(roots.map(root -> root.id()).getContent());

                    stats.clear();
                    var replies=publicPath
                            ? publicEventComments.getReplies(viewer,event,rootIds.getFirst(),number,size)
                            : service.getReplies(viewer,rootIds.getFirst(),
                                    PageRequest.of(number,size,Sort.by(Sort.Direction.DESC,"text")));
                    maxReplyQueries=Math.max(maxReplyQueries,stats.getPrepareStatementCount());
                    assertThat(stats.getPrepareStatementCount()).as("%s replies page %s/%s",path,number,size)
                            .isLessThanOrEqualTo(publicPath ? 9 : 7);
                    assertThat(stats.getEntityLoadCount()).as("one parent plus the requested reply page is hydrated")
                            .isEqualTo(replies.getNumberOfElements()+1);
                    assertThat(stats.getCollectionLoadCount()).isZero();
                    assertLargePage(replies,number,size);
                    replies.forEach(reply -> {
                        assertThat(reply.parentCommentId()).isEqualTo(rootIds.getFirst());
                        assertThat(reply.user().username()).startsWith("user");
                        assertThat(reply.likeCount()).isEqualTo(reply.deleted() ? 0 : 1);
                        assertThat(reply.likedByMe()).isEqualTo(!reply.deleted() && viewer!=null);
                        if(reply.id().equals(replyIds.getFirst())) {
                            assertThat(reply.deleted()).isTrue();
                            assertThat(reply.text()).isEqualTo("[Bu yorum silinmiştir]");
                        }
                    });
                    actualReplies.addAll(replies.map(reply -> reply.id()).getContent());
                }
                assertThat(actualRoots).doesNotHaveDuplicates().containsExactlyElementsOf(expectedRoots);
                assertThat(actualReplies).doesNotHaveDuplicates().containsExactlyElementsOf(expectedReplies);
                System.out.printf("400-comment pagination: %s, size=%d, max root SQL=%d, max reply SQL=%d%n",
                        path,size,maxRootQueries,maxReplyQueries);
            }
        }
    }

    @Test void pageLimitsAndPublicRootScopeAreEnforcedAgainstRealPostgres() {
        UUID root=create(event,null), reply=create(event,root);
        UUID otherRoot=tx(() -> comment(actor,UUID.randomUUID(),null).getId());
        var stats=em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        stats.clear();
        for(int[] invalid : List.of(new int[]{-1,20},new int[]{1001,20},new int[]{0,0},new int[]{0,51})) {
            assertThatThrownBy(() -> publicEventComments.getComments(null,event,invalid[0],invalid[1]))
                    .isInstanceOfSatisfying(SoundConnectException.class,e -> assertThat(e.getErrorType()).isEqualTo(ErrorType.INVALID_PARAMETER));
            assertThatThrownBy(() -> publicEventComments.getReplies(null,event,root,invalid[0],invalid[1]))
                    .isInstanceOfSatisfying(SoundConnectException.class,e -> assertThat(e.getErrorType()).isEqualTo(ErrorType.INVALID_PARAMETER));
        }
        assertThat(stats.getPrepareStatementCount()).as("invalid public bounds fail before database work").isZero();
        for(UUID invalidRoot : List.of(otherRoot,reply,UUID.randomUUID())) {
            assertThatThrownBy(() -> publicEventComments.getReplies(null,event,invalidRoot,0,20))
                    .isInstanceOfSatisfying(SoundConnectException.class,e -> assertThat(e.getErrorType()).isEqualTo(ErrorType.COMMENT_NOT_FOUND));
        }
        assertThat(service.getComments(actor.getId(),EngagementTargetType.EVENT,event,PageRequest.of(0,500)).getSize()).isEqualTo(50);
        assertThat(service.getReplies(actor.getId(),root,PageRequest.of(0,500)).getSize()).isEqualTo(50);
        assertThat(publicEventComments.getComments(null,event,1000,50).getContent()).isEmpty();
        assertThat(publicEventComments.getReplies(null,event,root,1000,50).getContent()).isEmpty();
        assertThatThrownBy(() -> service.getComments(actor.getId(),EngagementTargetType.EVENT,event,PageRequest.of(1001,20)))
                .isInstanceOfSatisfying(SoundConnectException.class,e -> assertThat(e.getErrorType()).isEqualTo(ErrorType.INVALID_PARAMETER));
        assertThatThrownBy(() -> service.getReplies(actor.getId(),root,PageRequest.of(1001,20)))
                .isInstanceOfSatisfying(SoundConnectException.class,e -> assertThat(e.getErrorType()).isEqualTo(ErrorType.INVALID_PARAMETER));
        jdbc.update("update tbl_venues set status='PENDING' where id=?",venue.getId());
        assertThatThrownBy(() -> publicEventComments.getComments(null,event,0,20))
                .isInstanceOfSatisfying(SoundConnectException.class,e -> assertThat(e.getErrorType()).isEqualTo(ErrorType.EVENT_NOT_FOUND));
        assertThatThrownBy(() -> publicEventComments.getReplies(null,event,root,0,20))
                .isInstanceOfSatisfying(SoundConnectException.class,e -> assertThat(e.getErrorType()).isEqualTo(ErrorType.EVENT_NOT_FOUND));
    }

    @Test void commentLikesHaveIdempotentDesiredStateAndGuestSafePageSnapshots() {
        UUID root=create(event,null), reply=create(event,root);
        assertThat(likeService.readCommentLike(actor.getId(),root).likeCount()).isZero();
        var first=likeService.setCommentLike(actor.getId(),root,true);
        assertThat(first.likeCount()).isEqualTo(1); assertThat(first.likedByMe()).isTrue();
        assertThat(likeService.setCommentLike(actor.getId(),root,true)).isEqualTo(first);
        likeService.like(actor.getId(),EngagementTargetType.COMMENT,root); // legacy service entry point also desired=true
        var secondUser=tx(this::user);
        assertThat(likeService.setCommentLike(secondUser.getId(),root,true).likeCount()).isEqualTo(2);
        likeService.setCommentLike(actor.getId(),reply,true);
        var own=publicEventComments.getComments(actor.getId(),event,0,20).getContent().getFirst();
        var guest=publicEventComments.getComments(null,event,0,20).getContent().getFirst();
        assertThat(own.likeCount()).isEqualTo(2); assertThat(own.likedByMe()).isTrue();
        assertThat(guest.likeCount()).isEqualTo(2); assertThat(guest.likedByMe()).isFalse();
        var ownReply=service.getReplies(actor.getId(),root,PageRequest.of(0,20)).getContent().getFirst();
        var guestReply=publicEventComments.getReplies(null,event,root,0,20).getContent().getFirst();
        assertThat(ownReply.likeCount()).isEqualTo(1); assertThat(ownReply.likedByMe()).isTrue();
        assertThat(guestReply.likeCount()).isEqualTo(1); assertThat(guestReply.likedByMe()).isFalse();
        var removed=likeService.setCommentLike(actor.getId(),root,false);
        assertThat(removed.likeCount()).isEqualTo(1); assertThat(removed.likedByMe()).isFalse();
        assertThat(likeService.setCommentLike(actor.getId(),root,false)).isEqualTo(removed);
        likeService.unlike(actor.getId(),EngagementTargetType.COMMENT,root);
        assertThat(likeService.countLikes(EngagementTargetType.COMMENT,root)).isEqualTo(1);
        assertThat(likeService.isLiked(actor.getId(),EngagementTargetType.COMMENT,root)).isFalse();
    }

    @Test void commentLikeReadsAndWritesRejectHiddenDeletedMissingAndInactiveTargets() {
        UUID root=create(event,null), reply=create(event,root);
        likeService.setCommentLike(actor.getId(),root,true);
        service.deleteComment(actor.getId(),root);
        var deleted=service.getComments(actor.getId(),EngagementTargetType.EVENT,event,PageRequest.of(0,20)).getContent().getFirst();
        assertThat(deleted.likeCount()).isZero(); assertThat(deleted.likedByMe()).isFalse();
        assertLikeError(() -> likeService.setCommentLike(actor.getId(),root,true),ErrorType.COMMENT_NOT_FOUND);
        assertLikeError(() -> likeService.setCommentLike(actor.getId(),root,false),ErrorType.COMMENT_NOT_FOUND);
        assertLikeError(() -> likeService.readCommentLike(actor.getId(),root),ErrorType.COMMENT_NOT_FOUND);
        assertLikeError(() -> likeService.countLikes(EngagementTargetType.COMMENT,root),ErrorType.COMMENT_NOT_FOUND);
        assertLikeError(() -> likeService.isLiked(actor.getId(),EngagementTargetType.COMMENT,root),ErrorType.COMMENT_NOT_FOUND);
        assertLikeError(() -> likeService.setCommentLike(actor.getId(),UUID.randomUUID(),true),ErrorType.COMMENT_NOT_FOUND);
        assertThat(likeService.setCommentLike(actor.getId(),reply,true).likeCount()).isEqualTo(1); // retained reply still readable
        jdbc.update("update tbl_venues set status='PENDING' where id=?",venue.getId());
        assertHidden(() -> likeService.readCommentLike(actor.getId(),reply));
        assertHidden(() -> likeService.setCommentLike(actor.getId(),reply,false));
        assertHidden(() -> likeService.countLikes(EngagementTargetType.COMMENT,reply));
        jdbc.update("update tbl_venues set status='APPROVED' where id=?",venue.getId());
        jdbc.update("update tbl_user set email_verified=false where id=?",actor.getId());
        assertLikeError(() -> likeService.setCommentLike(actor.getId(),reply,false),ErrorType.UNAUTHORIZED);
        jdbc.update("update tbl_user set email_verified=true,status='INACTIVE' where id=?",actor.getId());
        assertLikeError(() -> likeService.setCommentLike(actor.getId(),reply,true),ErrorType.UNAUTHORIZED);
        assertLikeError(() -> likeService.readCommentLike(actor.getId(),reply),ErrorType.UNAUTHORIZED);
        assertLikeError(() -> likeService.setCommentLike(null,reply,true),ErrorType.UNAUTHORIZED);
    }

    @Test void commentLikesFollowMediaAndOverthinkingAccessAndPurgeWithHardDeletedMediaComments() {
        UUID profile=tx(() -> persist(ListenerProfile.builder().user(em.getReference(User.class,actor.getId()))
                .visibilityChoiceCompleted(true).build()).getId());
        UUID asset=tx(() -> persist(MediaAsset.builder().kind(MediaKind.IMAGE).status(MediaStatus.READY).visibility(MediaVisibility.PUBLIC)
                .ownerType(MediaOwnerType.LISTENER_PROFILE).ownerId(profile).size(100L).mimeType("image/jpeg")
                .sourceUrl("https://cdn.test/image.jpg").build()).getId());
        UUID post=tx(() -> persist(OverthinkingPost.builder().author(em.getReference(User.class,actor.getId()))
                .title("Anonymous").content("Text").visibilityType(OverthinkingVisibilityType.ANONYMOUS).build()).getId());
        List<UUID> mediaComments=new ArrayList<>(),postComments=new ArrayList<>();
        for(var type : List.of(EngagementTargetType.MEDIA,EngagementTargetType.OVERTHINKING)) {
            UUID target=type==EngagementTargetType.MEDIA ? asset : post;
            var ids=type==EngagementTargetType.MEDIA ? mediaComments : postComments;
            var root=service.createComment(actor.getId(),type,target,new CommentCreateRequestDto("root",null));
            var reply=service.createComment(actor.getId(),type,target,new CommentCreateRequestDto("reply",root.id()));
            ids.add(root.id()); ids.add(reply.id());
            for(UUID id : ids) assertThat(likeService.setCommentLike(actor.getId(),id,true).likeCount()).isEqualTo(1);
            assertThat(service.getComments(actor.getId(),type,target,PageRequest.of(0,20)).getContent().getFirst().likedByMe()).isTrue();
            assertThat(service.getReplies(actor.getId(),root.id(),PageRequest.of(0,20)).getContent().getFirst().likedByMe()).isTrue();
        }
        jdbc.update("update tbl_media_asset set visibility='PRIVATE' where id=?",asset);
        assertHidden(() -> likeService.setCommentLike(actor.getId(),mediaComments.getFirst(),true));
        jdbc.update("update tbl_media_asset set visibility='PUBLIC' where id=?",asset);
        jdbc.update("update \"tbl_listener-profile\" set visibility_mode='GHOST' where id=?",profile);
        assertHidden(() -> likeService.readCommentLike(actor.getId(),mediaComments.getLast()));
        jdbc.update("delete from tbl_overthinking_post where id=?",post);
        assertHidden(() -> likeService.readCommentLike(actor.getId(),postComments.getFirst()));
        tx(() -> { mediaCleanup.purgeForMedia(asset); return null; });
        assertThat(jdbc.queryForObject("select count(*) from tbl_comment where target_type='MEDIA' and target_id=?",Long.class,asset)).isZero();
        assertThat(likeRepository.countByTargetTypeAndTargetId(EngagementTargetType.COMMENT,mediaComments.getFirst())).isZero();
        assertThat(likeRepository.countByTargetTypeAndTargetId(EngagementTargetType.COMMENT,mediaComments.getLast())).isZero();
        assertThat(likeRepository.countByTargetTypeAndTargetId(EngagementTargetType.COMMENT,postComments.getFirst())).isEqualTo(1);
    }

    @Test void commentTargetNeverCreatesAnotherCommentLevelAndMalformedReplyParentsCannotBeLiked() {
        UUID root=create(event,null);
        assertHidden(() -> service.createComment(actor.getId(),EngagementTargetType.COMMENT,root,new CommentCreateRequestDto("nested",null)));
        assertHidden(() -> service.getComments(actor.getId(),EngagementTargetType.COMMENT,root,PageRequest.of(0,20)));
        UUID otherEvent=UUID.randomUUID();
        UUID malformed=tx(() -> comment(actor,event,comment(actor,otherEvent,null)).getId());
        assertLikeError(() -> likeService.setCommentLike(actor.getId(),malformed,true),ErrorType.COMMENT_NOT_FOUND);
        UUID reply=create(event,root);
        UUID nested=tx(() -> comment(actor,event,em.getReference(Comment.class,reply)).getId());
        assertLikeError(() -> likeService.setCommentLike(actor.getId(),nested,true),ErrorType.COMMENT_NOT_FOUND);
    }

    @Test void concurrentCommentLikesAndUnlikesRemainUniqueAndReturnCommittedDesiredState() throws Exception {
        UUID root=create(event,null);
        for(boolean desired : List.of(true,false,true)) {
            try(var workers=Executors.newFixedThreadPool(8)) {
                var start=new CountDownLatch(1);
                var requests=new ArrayList<Future<com.berkayb.soundconnect.modules.like.dto.CommentLikeState>>();
                for(int i=0;i<8;i++) requests.add(workers.submit(() -> { start.await(); return likeService.setCommentLike(actor.getId(),root,desired); }));
                start.countDown();
                for(var request : requests) {
                    var state=request.get(20,TimeUnit.SECONDS);
                    assertThat(state.likedByMe()).isEqualTo(desired);
                    assertThat(state.likeCount()).isEqualTo(desired ? 1 : 0);
                }
            }
            assertThat(likeRepository.countByTargetTypeAndTargetId(EngagementTargetType.COMMENT,root)).isEqualTo(desired ? 1 : 0);
        }
        List<User> participants=tx(() -> { List<User> result=new ArrayList<>(); for(int i=0;i<8;i++) result.add(user()); return result; });
        try(var workers=Executors.newFixedThreadPool(8)) {
            var start=new CountDownLatch(1); List<Future<?>> requests=new ArrayList<>();
            for(var participant : participants) requests.add(workers.submit(() -> { start.await(); return likeService.setCommentLike(participant.getId(),root,true); }));
            start.countDown(); for(var request : requests) request.get(20,TimeUnit.SECONDS);
        }
        assertThat(likeService.readCommentLike(actor.getId(),root).likeCount()).isEqualTo(9);
    }

    @Test void commentDeletionWinningTheRowLockPreventsRacingLikeInsert() throws Exception {
        UUID root=create(event,null);
        try(var connection=dataSource.getConnection();var worker=Executors.newSingleThreadExecutor()) {
            connection.setAutoCommit(false);
            try(var lock=connection.prepareStatement("select id from tbl_comment where id=? for update")) {
                lock.setObject(1,root); lock.executeQuery().close();
            }
            var entered=new CountDownLatch(1);
            var pending=worker.submit(() -> { entered.countDown(); return likeService.setCommentLike(actor.getId(),root,true); });
            assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> pending.get(200,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            try(var deletion=connection.prepareStatement("update tbl_comment set is_deleted=true where id=?")) {
                deletion.setObject(1,root); deletion.executeUpdate();
            }
            connection.commit();
            assertThatThrownBy(() -> pending.get(10,TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(SoundConnectException.class);
        }
        assertThat(likeRepository.countByTargetTypeAndTargetId(EngagementTargetType.COMMENT,root)).isZero();
    }

    private void assertLikeError(Runnable action,ErrorType expected) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(SoundConnectException.class,e -> assertThat(e.getErrorType()).isEqualTo(expected));
    }

    private void assertLargePage(Page<?> page,int number,int size) {
        int totalPages=(400+size-1)/size;
        assertThat(page.getTotalElements()).isEqualTo(400);
        assertThat(page.getTotalPages()).isEqualTo(totalPages);
        assertThat(page.getNumber()).isEqualTo(number);
        assertThat(page.getSize()).isEqualTo(size);
        assertThat(page.getNumberOfElements()).isEqualTo(Math.max(0,Math.min(size,400-number*size)));
        assertThat(page.hasNext()).isEqualTo(number+1<totalPages);
        assertThat(page.isLast()).isEqualTo(number+1>=totalPages);
    }

    @Test void authoritativeAvatarCandidatesCoverAllSixProfileTypesAndGhostOverridesAlternates() {
        var expected=new LinkedHashMap<UUID,UUID>();
        tx(() -> {
            var musician=user(); var mi=UUID.randomUUID(); persist(MusicianProfile.builder().user(musician).profilePictureMediaId(mi).build()); expected.put(musician.getId(),mi);
            var listener=user(); var li=UUID.randomUUID(); persist(ListenerProfile.builder().user(listener).profilePictureMediaId(li).visibilityChoiceCompleted(true).build()); expected.put(listener.getId(),li);
            var organizer=user(); var oi=UUID.randomUUID(); persist(OrganizerProfile.builder().user(organizer).profilePictureMediaId(oi).build()); expected.put(organizer.getId(),oi);
            var producer=user(); var pi=UUID.randomUUID(); persist(ProducerProfile.builder().user(producer).profilePictureMediaId(pi).build()); expected.put(producer.getId(),pi);
            var studio=user(); var si=UUID.randomUUID(); persist(StudioProfile.builder().user(studio).profilePictureMediaId(si).build()); expected.put(studio.getId(),si);
            var vi=UUID.randomUUID(); persist(VenueProfile.builder().venue(em.getReference(Venue.class,venue.getId())).profilePictureMediaId(vi).build()); expected.put(venue.getOwner().getId(),vi);
            return null;
        });
        Map<UUID,String> urls=new HashMap<>(); expected.values().forEach(id -> urls.put(id,"https://cdn.test/"+id));
        when(media.getDisplayUrlMap(anyList())).thenReturn(urls);
        var result=tx(() -> authors.resolve(expected.keySet()));
        expected.forEach((user,avatar) -> assertThat(result.get(user).avatarUrl()).isEqualTo(urls.get(avatar)));
        verify(media).getDisplayUrlMap(argThat(ids -> ids.size()==6));
        UUID listener=expected.keySet().stream().skip(1).findFirst().orElseThrow();
        jdbc.update("update \"tbl_listener-profile\" set visibility_mode='GHOST' where user_id=?",listener);
        assertThat(tx(() -> authors.resolve(Set.of(listener))).get(listener).visibilityMode()).isEqualTo(ListenerVisibilityMode.GHOST);
        jdbc.update("update \"tbl_listener-profile\" set visibility_choice_completed=false where user_id=?",listener);
        var pending=tx(() -> authors.resolve(Set.of(listener))).get(listener);
        assertThat(pending.username()).isEqualTo("Kullanici"); assertThat(pending.avatarUrl()).isNull();
    }

    @Test void racingParentDeletionPreventsReplyAfterDeletionAndKeepsExistingRepliesReadable() throws Exception {
        UUID root=create(event,null), existing=create(event,root);
        try(var connection=dataSource.getConnection(); var worker=Executors.newSingleThreadExecutor()) {
            connection.setAutoCommit(false);
            try(var lock=connection.prepareStatement("select id from tbl_comment where id=? for update")) {
                lock.setObject(1,root); lock.executeQuery().close();
            }
            var entered=new CountDownLatch(1);
            var pending=worker.submit(() -> { entered.countDown(); return create(event,root); });
            assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> pending.get(200,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            try(var deletion=connection.prepareStatement("update tbl_comment set is_deleted=true where id=?")) {
                deletion.setObject(1,root); deletion.executeUpdate();
            }
            connection.commit();
            assertThatThrownBy(() -> pending.get(10,TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(SoundConnectException.class);
        }
        var replies=service.getReplies(actor.getId(),root,PageRequest.of(0,20));
        assertThat(replies.getContent()).singleElement().satisfies(c -> assertThat(c.id()).isEqualTo(existing));
    }

    @Test void actualPostgresRollbackReleasesReservationButCommittedResponseFailureDoesNot() {
        StringRedisTemplate reservationRedis=mock(StringRedisTemplate.class);
        ZSetOperations<String,String> sortedSets=mock(ZSetOperations.class);
        Set<String> reservations=new HashSet<>();
        when(reservationRedis.execute(any(RedisScript.class),anyList(),any(Object[].class))).thenAnswer(i -> {
            reservations.add(i.getArgument(4)); return 0L;
        });
        when(reservationRedis.opsForZSet()).thenReturn(sortedSets);
        when(sortedSets.remove(anyString(),any(Object[].class))).thenAnswer(i -> reservations.remove(i.getArgument(1)) ? 1L : 0L);
        var actualGuard=new CommentBurstGuard(reservationRedis);
        doAnswer(i -> { actualGuard.reserve(i.getArgument(0),i.getArgument(1),i.getArgument(2)); return null; })
                .when(burstGuard).reserve(any(),any(),any());

        assertThatThrownBy(() -> tx(() -> {
            create(event,null); em.flush();
            assertThat(reservations).hasSize(1);
            assertThat(em.createNativeQuery("select count(*) from tbl_comment where target_id=:id",Long.class)
                    .setParameter("id",event).getSingleResult()).isEqualTo(1L);
            throw new IllegalStateException("failure after flushed insert");
        })).isInstanceOf(IllegalStateException.class).hasMessage("failure after flushed insert");
        assertThat(jdbc.queryForObject("select count(*) from tbl_comment where target_id=?",Long.class,event)).isZero();
        assertThat(reservations).isEmpty();

        UUID committed=create(event,null);
        assertThat(reservations).hasSize(1);
        assertThatThrownBy(() -> { throw new IllegalStateException("response serialization after service commit"); })
                .isInstanceOf(IllegalStateException.class);
        assertThat(repository.existsById(committed)).isTrue();
        assertThat(reservations).hasSize(1);
        verify(sortedSets,times(1)).remove(anyString(),any(Object[].class));
    }

    @Test void freshUtcAuditedRootAndReplyExposeActualInstantsWithoutChangingStoredWallClock() {
        Instant before=Instant.now();
        var root=service.createComment(actor.getId(),EngagementTargetType.EVENT,event,new CommentCreateRequestDto("fresh",null));
        var reply=service.createComment(actor.getId(),EngagementTargetType.EVENT,event,new CommentCreateRequestDto("fresh reply",root.id()));
        Instant after=Instant.now();
        assertThat(root.createdAt()).isBetween(before,after);
        assertThat(reply.createdAt()).isBetween(before,after);

        // 22:59 UTC is already the next calendar date in Istanbul; storage is still UTC wall clock.
        LocalDateTime storedUtc=LocalDateTime.of(2026,9,8,22,59,59,123456000);
        jdbc.update("update tbl_comment set created_at=? where target_id=?",storedUtc,event);
        Instant expected=Instant.parse("2026-09-08T22:59:59.123456Z");
        var rootRead=service.getComments(actor.getId(),EngagementTargetType.EVENT,event,PageRequest.of(0,20)).getContent().getFirst();
        var replyRead=service.getReplies(actor.getId(),root.id(),PageRequest.of(0,20)).getContent().getFirst();
        assertThat(rootRead.createdAt()).isEqualTo(expected);
        assertThat(replyRead.createdAt()).isEqualTo(expected);
        service.deleteComment(actor.getId(),root.id());
        var deleted=service.getComments(actor.getId(),EngagementTargetType.EVENT,event,PageRequest.of(0,20)).getContent().getFirst();
        assertThat(deleted.deleted()).isTrue();
        assertThat(deleted.createdAt()).isEqualTo(expected);
        LocalDateTime storedAfterRead=jdbc.queryForObject("select created_at from tbl_comment where id=?",
                (rs,index) -> rs.getObject("created_at",LocalDateTime.class),root.id());
        assertThat(storedAfterRead).isEqualTo(storedUtc);
    }

    private UUID create(UUID event,UUID parent) { return service.createComment(actor.getId(),EngagementTargetType.EVENT,event,new CommentCreateRequestDto("comment",parent)).id(); }
    private Comment comment(User user,UUID event,Comment parent) { return persist(Comment.builder().user(em.getReference(User.class,user.getId())).targetType(EngagementTargetType.EVENT).targetId(event).parentComment(parent).text("text").build()); }
    private User user() { return persist(User.builder().username("user"+UUID.randomUUID().toString().replace("-","").substring(0,10)).email(UUID.randomUUID()+"@test.invalid").password("unused").status(UserStatus.ACTIVE).emailVerified(true).build()); }
    private <T> T persist(T value) { em.persist(value); return value; }
    private <T> T tx(Supplier<T> action) { return new TransactionTemplate(manager).execute(s -> action.get()); }
    private void assertHidden(Runnable action) { assertThatThrownBy(action::run).isInstanceOfSatisfying(SoundConnectException.class,e -> assertThat(e.getErrorType()).isEqualTo(ErrorType.ENGAGEMENT_NOT_FOUND)); }

    @Configuration(proxyBeanMethods=false)
    @EnableJpaRepositories(basePackageClasses={CommentRepository.class,OverthinkingPostRepository.class,EventCommentReadRepository.class,LikeRepository.class})
    @EntityScan(basePackages="com.berkayb.soundconnect")
    @Import({CommentServiceImpl.class,CommentEntityFinder.class,CommentTargetAccessGuard.class,CommentAuthorBatchResolver.class,JpaAuditingConfig.class,EventCommentReadService.class,
            LikeServiceImpl.class,CommentLikeAccessGuard.class,MediaEngagementCleanupService.class})
    static class Config {
        @Bean DataSource dataSource() {
            if(!POSTGRES.isRunning()) throw new IllegalStateException("Disposable PostgreSQL must be running");
            return new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());
        }
        @Bean CommentMapper mapper() { return Mappers.getMapper(CommentMapper.class); }
    }
}

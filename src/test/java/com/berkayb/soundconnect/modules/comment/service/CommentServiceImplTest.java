package com.berkayb.soundconnect.modules.comment.service;

import com.berkayb.soundconnect.modules.comment.dto.request.CommentCreateRequestDto;
import com.berkayb.soundconnect.modules.comment.abuse.CommentBurstGuard;
import com.berkayb.soundconnect.modules.comment.dto.support.UserSummaryDto;
import com.berkayb.soundconnect.modules.comment.entity.Comment;
import com.berkayb.soundconnect.modules.comment.mapper.CommentMapper;
import com.berkayb.soundconnect.modules.comment.repository.CommentRepository;
import com.berkayb.soundconnect.modules.comment.support.*;
import com.berkayb.soundconnect.modules.engagement.enums.EngagementTargetType;
import com.berkayb.soundconnect.modules.overthinking.entity.OverthinkingPost;
import com.berkayb.soundconnect.modules.overthinking.enums.OverthinkingVisibilityType;
import com.berkayb.soundconnect.modules.overthinking.repository.OverthinkingPostRepository;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.support.UserEntityFinder;
import com.berkayb.soundconnect.shared.exception.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mapstruct.factory.Mappers;
import org.springframework.data.domain.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CommentServiceImplTest {
    @Mock CommentTargetAccessGuard targets;
    @Mock CommentRepository repository;
    @Mock CommentEntityFinder finder;
    @Mock UserEntityFinder users;
    @Mock OverthinkingPostRepository posts;
    @Mock CommentAuthorBatchResolver authors;
    @Mock CommentBurstGuard burstGuard;
    @Mock com.berkayb.soundconnect.modules.like.repository.LikeRepository likes;
    @Mock com.berkayb.soundconnect.modules.engagement.service.MediaEngagementNotificationService notifications;
    CommentServiceImpl service;
    final UUID actor = UUID.randomUUID(), target = UUID.randomUUID(), rootId = UUID.randomUUID();

    @BeforeEach void setup() {
        service = new CommentServiceImpl(targets, repository, Mappers.getMapper(CommentMapper.class), finder, users, posts, authors, burstGuard,likes,notifications);
        lenient().when(likes.lockActiveActor(any())).thenAnswer(i -> Optional.of(i.getArgument(0)));
    }

    @Test void accountDisabledAfterAuthenticationCannotCreateOrDeleteComments() {
        when(likes.lockActiveActor(actor)).thenReturn(Optional.empty());
        assertError(() -> service.createComment(actor, EngagementTargetType.MEDIA, target,
                new CommentCreateRequestDto("text", null)), ErrorType.UNAUTHORIZED);
        assertError(() -> service.deleteComment(actor, rootId), ErrorType.UNAUTHORIZED);
        assertError(() -> service.createComment(null, EngagementTargetType.MEDIA, target,
                new CommentCreateRequestDto("text", null)), ErrorType.UNAUTHORIZED);
        verifyNoInteractions(targets, repository, users, burstGuard);
    }

    @Test void rootReadRejectsHiddenTargetBeforeQueryingCommentsOrIdentity() {
        doThrow(new SoundConnectException(ErrorType.ENGAGEMENT_NOT_FOUND)).when(targets).requireReadable(EngagementTargetType.MEDIA, target);
        assertError(() -> service.getComments(actor, EngagementTargetType.MEDIA, target, PageRequest.of(0, 20)), ErrorType.ENGAGEMENT_NOT_FOUND);
        verifyNoInteractions(repository, authors, burstGuard);
    }

    @Test void repliesRecheckTargetsAndRejectReplyAsParent() {
        var root = comment(null);
        when(finder.getById(rootId)).thenReturn(root);
        doThrow(new SoundConnectException(ErrorType.ENGAGEMENT_NOT_FOUND)).when(targets).requireReadable(EngagementTargetType.MEDIA, target);
        assertError(() -> service.getReplies(actor, rootId, PageRequest.of(0, 20)), ErrorType.ENGAGEMENT_NOT_FOUND);
        verifyNoInteractions(repository, authors, burstGuard);
        root.setParentComment(comment(null));
        assertError(() -> service.getReplies(actor, rootId, PageRequest.of(0, 20)), ErrorType.COMMENT_REPLY_DEPTH_NOT_ALLOWED);
    }

    @Test void rootAndReplyPaginationOverrideUntrustedSortWithStableBoundedOrder() {
        when(repository.findByTargetTypeAndTargetIdAndParentCommentIsNull(any(), any(), any())).thenAnswer(i -> Page.empty(i.getArgument(2)));
        when(finder.getById(rootId)).thenReturn(comment(null));
        when(repository.findByParentComment(any(), any())).thenAnswer(i -> Page.empty(i.getArgument(1)));
        var roots = service.getComments(actor, EngagementTargetType.MEDIA, target, PageRequest.of(2, 100, Sort.by("text")));
        var replies = service.getReplies(actor, rootId, PageRequest.of(3, 100, Sort.by("text")));
        assertThat(roots.getSize()).isEqualTo(50);
        assertThat(roots.getSort()).isEqualTo(Sort.by(Sort.Direction.DESC, "createdAt", "id"));
        assertThat(replies.getSort()).isEqualTo(Sort.by(Sort.Direction.ASC, "createdAt", "id"));
        assertError(() -> service.getComments(actor, EngagementTargetType.MEDIA, target, PageRequest.of(1001, 1)), ErrorType.INVALID_PARAMETER);
    }

    @Test void createRejectsNullBlankAndOver500TextBeforeTargetWork() {
        assertError(() -> service.createComment(actor, EngagementTargetType.MEDIA, target, null), ErrorType.COMMENT_TEXT_INVALID);
        for (String text : new String[]{null, "  ", "a".repeat(501)}) {
            assertError(() -> service.createComment(actor, EngagementTargetType.MEDIA, target, new CommentCreateRequestDto(text, null)), ErrorType.COMMENT_TEXT_INVALID);
        }
        verifyNoInteractions(targets, repository, users, burstGuard);
    }

    @Test void replyChecksFreshLockedParentAndNeverInsertsOnDeletedParent() {
        when(users.getUser(actor)).thenReturn(user());
        var locked = mock(CommentRepository.LockedComment.class);
        when(repository.lockComment(rootId)).thenReturn(Optional.of(locked));
        when(locked.getDeleted()).thenReturn(true);
        assertError(() -> service.createComment(actor, EngagementTargetType.MEDIA, target, new CommentCreateRequestDto("reply", rootId)), ErrorType.COMMENT_PARENT_DELETED);
        verify(repository, never()).save(any());
        verifyNoInteractions(finder);
        verifyNoInteractions(burstGuard);
    }

    @Test void replyRejectsDepthAndCrossTarget() {
        when(users.getUser(actor)).thenReturn(user());
        var locked = mock(CommentRepository.LockedComment.class);
        when(repository.lockComment(rootId)).thenReturn(Optional.of(locked));
        when(locked.getParentId()).thenReturn(UUID.randomUUID());
        assertError(() -> service.createComment(actor, EngagementTargetType.MEDIA, target, new CommentCreateRequestDto("reply", rootId)), ErrorType.COMMENT_REPLY_DEPTH_NOT_ALLOWED);
        when(locked.getParentId()).thenReturn(null);
        when(locked.getTargetType()).thenReturn("EVENT");
        assertError(() -> service.createComment(actor, EngagementTargetType.MEDIA, target, new CommentCreateRequestDto("reply", rootId)), ErrorType.COMMENT_PARENT_TARGET_MISMATCH);
        verifyNoInteractions(burstGuard);
    }

    @Test void ownerDeleteIsIdempotentAndUnauthorizedDeleteCannotMutate() {
        var locked = mock(CommentRepository.LockedComment.class);
        when(repository.lockComment(rootId)).thenReturn(Optional.of(locked));
        when(locked.getUserId()).thenReturn(actor);
        service.deleteComment(actor, rootId);
        verify(repository).softDelete(rootId);
        when(locked.getDeleted()).thenReturn(true);
        service.deleteComment(actor, rootId);
        verify(repository, times(1)).softDelete(rootId);
        assertError(() -> service.deleteComment(UUID.randomUUID(), rootId), ErrorType.COMMENT_FORBIDDEN);
        verifyNoInteractions(targets); // owners can remove their text after the target becomes unavailable
    }

    @Test void deletedTextIsMaskedAndAuthorsResolvedOncePerPage() {
        var one = comment(null); one.setDeleted(true);
        var two = comment(null); two.setId(UUID.randomUUID());
        when(repository.findByTargetTypeAndTargetIdAndParentCommentIsNull(any(), any(), any())).thenReturn(new PageImpl<>(List.of(one,two)));
        when(authors.resolve(Set.of(actor))).thenReturn(Map.of(actor, new UserSummaryDto(actor, "handle", "https://cdn.test/current.jpg")));
        var result = service.getComments(actor, EngagementTargetType.MEDIA, target, PageRequest.of(0,20));
        assertThat(result.getContent().getFirst().text()).isEqualTo("[Bu yorum silinmiştir]");
        assertThat(result.getContent().get(1).user().avatarUrl()).isEqualTo("https://cdn.test/current.jpg");
        verify(authors).resolve(Set.of(actor));
    }

    @Test void anonymousPostOwnerIsHiddenFromOthersAndMissingPostFailsClosed() {
        var comment = comment(null); comment.setTargetType(EngagementTargetType.OVERTHINKING);
        when(repository.findByTargetTypeAndTargetIdAndParentCommentIsNull(any(), any(), any())).thenReturn(new PageImpl<>(List.of(comment)));
        var post = OverthinkingPost.builder().id(target).author(user()).visibilityType(OverthinkingVisibilityType.ANONYMOUS).build();
        when(posts.findAllById(Set.of(target))).thenReturn(List.of(post), List.of());
        for (int i=0; i<2; i++) {
            var dto = service.getComments(UUID.randomUUID(), EngagementTargetType.OVERTHINKING, target, PageRequest.of(0,20)).getContent().getFirst();
            assertThat(dto.anonymousAuthor()).isTrue();
            assertThat(dto.user().id()).isNull();
            assertThat(dto.user().avatarUrl()).isNull();
        }
        verifyNoInteractions(authors);
    }

    @Test void invalidTargetOrMissingUserCannotConsumeBurstQuota() {
        doThrow(new SoundConnectException(ErrorType.ENGAGEMENT_NOT_FOUND)).when(targets).requireReadable(EngagementTargetType.EVENT,target);
        assertError(() -> service.createComment(actor,EngagementTargetType.EVENT,target,new CommentCreateRequestDto("valid",null)),ErrorType.ENGAGEMENT_NOT_FOUND);
        when(users.getUser(actor)).thenThrow(new SoundConnectException(ErrorType.USER_NOT_FOUND));
        assertError(() -> service.createComment(actor,EngagementTargetType.MEDIA,target,new CommentCreateRequestDto("valid",null)),ErrorType.USER_NOT_FOUND);
        verifyNoInteractions(burstGuard);
        verify(repository,never()).save(any());
    }

    @Test void rateLimitedAndUnavailableReservationsNeverReachSave() {
        when(users.getUser(actor)).thenReturn(user());
        doThrow(new RateLimitedException(ErrorType.COMMENT_BURST_RATE_LIMITED,10))
                .doThrow(new ServiceUnavailableRetryException(ErrorType.COMMENT_BURST_UNAVAILABLE,5))
                .when(burstGuard).reserve(actor,EngagementTargetType.MEDIA,target);
        assertError(() -> service.createComment(actor,EngagementTargetType.MEDIA,target,new CommentCreateRequestDto("valid",null)),ErrorType.COMMENT_BURST_RATE_LIMITED);
        assertError(() -> service.createComment(actor,EngagementTargetType.MEDIA,target,new CommentCreateRequestDto("valid",null)),ErrorType.COMMENT_BURST_UNAVAILABLE);
        verify(repository,never()).save(any());
        verifyNoInteractions(authors);
    }

    @Test void rootAndReplyReserveSameContentKeyOnlyAfterValidationAndBeforeSave() {
        when(users.getUser(actor)).thenReturn(user());
        when(repository.save(any())).thenAnswer(i -> { Comment c=i.getArgument(0); c.setId(UUID.randomUUID()); return c; });
        when(authors.resolve(Set.of(actor))).thenReturn(Map.of(actor,new UserSummaryDto(actor,"handle",null)));
        service.createComment(actor,EngagementTargetType.MEDIA,target,new CommentCreateRequestDto("root",null));
        var locked=mock(CommentRepository.LockedComment.class);
        when(repository.lockComment(rootId)).thenReturn(Optional.of(locked));
        when(locked.getTargetType()).thenReturn("MEDIA"); when(locked.getTargetId()).thenReturn(target); when(locked.getId()).thenReturn(rootId);
        when(repository.getReferenceById(rootId)).thenReturn(comment(null));
        service.createComment(actor,EngagementTargetType.MEDIA,target,new CommentCreateRequestDto("reply",rootId));
        var order=inOrder(targets,users,repository,burstGuard);
        order.verify(targets).requireReadable(EngagementTargetType.MEDIA,target);
        order.verify(users).getUser(actor);
        order.verify(burstGuard).reserve(actor,EngagementTargetType.MEDIA,target);
        order.verify(repository).save(any());
        order.verify(targets).requireReadable(EngagementTargetType.MEDIA,target);
        order.verify(users).getUser(actor);
        order.verify(repository).lockComment(rootId);
        order.verify(repository).getReferenceById(rootId);
        order.verify(burstGuard).reserve(actor,EngagementTargetType.MEDIA,target);
        order.verify(repository).save(any());
    }

    private User user() { return User.builder().id(actor).username("handle").build(); }
    private Comment comment(Comment parent) { return Comment.builder().id(rootId).user(user()).targetType(EngagementTargetType.MEDIA).targetId(target).parentComment(parent).text("original").build(); }
    private void assertError(Runnable action, ErrorType expected) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(SoundConnectException.class, e -> assertThat(e.getErrorType()).isEqualTo(expected));
    }
}

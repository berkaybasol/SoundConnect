package com.berkayb.soundconnect.modules.contentreport;

import com.berkayb.soundconnect.modules.comment.entity.Comment;
import com.berkayb.soundconnect.modules.comment.repository.CommentRepository;
import com.berkayb.soundconnect.modules.comment.support.CommentEntityFinder;
import com.berkayb.soundconnect.modules.comment.support.CommentTargetAccessGuard;
import com.berkayb.soundconnect.modules.engagement.enums.EngagementTargetType;
import com.berkayb.soundconnect.modules.overthinking.entity.OverthinkingPost;
import com.berkayb.soundconnect.modules.overthinking.enums.OverthinkingVisibilityType;
import com.berkayb.soundconnect.modules.overthinking.repository.OverthinkingPostRepository;
import com.berkayb.soundconnect.modules.overthinking.service.OverthinkingPostService;
import com.berkayb.soundconnect.modules.profile.shared.resolver.dto.UserProfileTargetDto;
import com.berkayb.soundconnect.modules.profile.shared.resolver.dto.UserProfilesResolveResponseDto;
import com.berkayb.soundconnect.modules.profile.shared.resolver.service.PublicProfileResolverService;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.support.AccountDeliveryFence;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.berkayb.soundconnect.modules.contentreport.ContentReportKind.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContentReportTargetsTest {
    final UUID actor = UUID.randomUUID(), id = UUID.randomUUID(), resource = UUID.randomUUID(), parent = UUID.randomUUID();
    final User author = User.builder().id(UUID.randomUUID()).username("private_identity").build();
    final CommentRepository comments = mock(CommentRepository.class);
    final CommentEntityFinder finder = mock(CommentEntityFinder.class);
    final CommentTargetAccessGuard access = mock(CommentTargetAccessGuard.class);
    final OverthinkingPostRepository posts = mock(OverthinkingPostRepository.class);
    final PublicProfileResolverService profiles = mock(PublicProfileResolverService.class);
    final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    final ContentReportTargets targets = new ContentReportTargets(jdbc, mock(AccountDeliveryFence.class), profiles,
            access, comments, finder, posts, mock(OverthinkingPostService.class), mock(ReportedProfileShares.class));

    @Test void anonymousOriginalOnlyExposesPublicTitleAndTextAndRequiresVisibility() {
        var post = OverthinkingPost.builder().author(author).title("Title").content("Body")
                .visibilityType(OverthinkingVisibilityType.ANONYMOUS).build();
        when(posts.findById(id)).thenReturn(Optional.of(post));
        assertThat(targets.capture(actor, OVERTHINKING_POST, id,null))
                .isEqualTo(new ContentReportEvidence("Title", "Body", null));
        doThrow(new SoundConnectException(ErrorType.ENGAGEMENT_NOT_FOUND)).when(access)
                .requireReadable(actor, EngagementTargetType.OVERTHINKING, id);
        assertThatThrownBy(() -> targets.capture(actor, OVERTHINKING_POST,id,null))
                .hasFieldOrPropertyWithValue("errorType", ErrorType.ENGAGEMENT_NOT_FOUND);
        verify(posts).findById(id);
    }

    @Test void replyOnlyExposesSelectedTextAndVisibleParentText() {
        var replyRow = commentRow(id,parent);
        when(comments.findCommentTarget(id)).thenReturn(Optional.of(replyRow));
        Comment root = Comment.builder().id(parent).user(author).text("parent text").build();
        when(finder.getVisibleById(actor,parent)).thenReturn(root);
        var rootRow = commentRow(parent,null);
        when(comments.lockCommentForRead(parent)).thenReturn(Optional.of(rootRow));
        when(comments.lockCommentForRead(id)).thenReturn(Optional.of(replyRow));
        var comment = Comment.builder().id(id).user(author).text("reply").build();
        when(finder.getVisibleById(actor,id)).thenReturn(comment);
        assertThat(targets.capture(actor,COMMENT,id,null)).isEqualTo(new ContentReportEvidence("Yanıt","reply","parent text"));
        root.setDeleted(true);
        assertThat(targets.capture(actor,COMMENT,id,null).parentText()).isNull();
        comment.setDeleted(true);
        assertThatThrownBy(() -> targets.capture(actor,COMMENT,id,null)).hasFieldOrPropertyWithValue("errorType",ErrorType.CONTENT_REPORT_TARGET_UNAVAILABLE);
        verify(access,times(3)).requireReadable(actor,EngagementTargetType.OVERTHINKING,resource);
    }

    @Test void removalRejectsEditedOrDeletedCommentsAndChangesOnlyTheSelectedId() {
        var row = commentRow(id,parent);
        when(comments.lockComment(id)).thenReturn(Optional.of(row));
        var comment = Comment.builder().id(id).user(author).text("current").build();
        when(comments.findById(id)).thenReturn(Optional.of(comment));
        var evidence = new ContentReportEvidence("Yanıt","original","parent");
        assertThat(targets.removable(COMMENT,id,evidence)).isFalse();
        assertThatThrownBy(() -> targets.remove(COMMENT,id,evidence)).hasFieldOrPropertyWithValue("errorType",ErrorType.CONTENT_REPORT_STATE_CHANGED);
        verify(comments,never()).softDelete(any());
        comment.setText("original");
        when(comments.softDelete(id)).thenReturn(1);
        assertThat(targets.removable(COMMENT,id,evidence)).isTrue();
        targets.remove(COMMENT,id,evidence);
        verify(comments).softDelete(id);
        when(row.getDeleted()).thenReturn(true);
        assertThatThrownBy(() -> targets.remove(COMMENT,id,evidence)).hasFieldOrPropertyWithValue("errorType",ErrorType.CONTENT_REPORT_STATE_CHANGED);
        verify(comments,never()).softDelete(parent);
    }

    @Test void venueSelectionRequiresExactMatchWhenOneAccountOwnsSeveralVenues() {
        var first = new UserProfileTargetDto("VENUE",id,"first venue",null);
        var second = new UserProfileTargetDto("VENUE",resource,"selected venue",null);
        when(profiles.resolveByUserId(author.getId())).thenReturn(new UserProfilesResolveResponseDto(author.getId(),List.of(first,second)));
        assertThatThrownBy(() -> targets.capture(actor,VENUE_PROFILE,author.getId(),null))
                .hasFieldOrPropertyWithValue("errorType",ErrorType.CONTENT_REPORT_TARGET_UNAVAILABLE);
        when(jdbc.queryForList(contains("tbl_venue_profile"),eq(Map.of("id",resource)),eq(String.class)))
                .thenReturn(List.of("public biography"));
        assertThat(targets.capture(actor,VENUE_PROFILE,author.getId(),resource))
                .isEqualTo(new ContentReportEvidence("selected venue","public biography",null));
        assertThatThrownBy(() -> targets.capture(actor,STUDIO_PROFILE,author.getId(),resource))
                .hasFieldOrPropertyWithValue("errorType",ErrorType.CONTENT_REPORT_TARGET_UNAVAILABLE);
    }

    private CommentRepository.LockedComment commentRow(UUID commentId, UUID parentId) {
        var row = mock(CommentRepository.LockedComment.class);
        when(row.getId()).thenReturn(commentId);
        when(row.getParentId()).thenReturn(parentId);
        when(row.getTargetId()).thenReturn(resource);
        when(row.getTargetType()).thenReturn("OVERTHINKING");
        return row;
    }
}

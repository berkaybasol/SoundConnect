package com.berkayb.soundconnect.modules.comment.publicevent;

import com.berkayb.soundconnect.modules.comment.dto.response.CommentReplyResponseDto;
import com.berkayb.soundconnect.modules.comment.dto.response.CommentResponseDto;
import com.berkayb.soundconnect.modules.comment.service.CommentService;
import com.berkayb.soundconnect.modules.engagement.enums.EngagementTargetType;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class EventCommentReadServiceTest {
    private final EventCommentReadRepository repository = mock(EventCommentReadRepository.class);
    private final CommentService comments = mock(CommentService.class);
    private final EventCommentReadService service = new EventCommentReadService(repository, comments);
    private final UUID eventId = UUID.randomUUID(), commentId = UUID.randomUUID();

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = "bd5d3620-4b17-4c29-ae86-ce9a2a805be1")
    void preservesGuestAndAuthenticatedViewerWhileReusingExistingCommentMapping(String rawViewer) {
        UUID viewer = rawViewer == null ? null : UUID.fromString(rawViewer);
        var pageable = PageRequest.of(0, 20);
        Page<CommentResponseDto> page = Page.empty(pageable);
        when(repository.existsPublicEvent(eventId)).thenReturn(true);
        when(comments.getComments(viewer, EngagementTargetType.EVENT, eventId, pageable)).thenReturn(page);
        assertThat(service.getComments(viewer, eventId, 0, 20)).isSameAs(page);
        verify(repository).existsPublicEvent(eventId);
        verify(comments).getComments(viewer, EngagementTargetType.EVENT, eventId, pageable);
        verifyNoMoreInteractions(repository, comments);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = "bd5d3620-4b17-4c29-ae86-ce9a2a805be1")
    void validatesEventAndExactRootBeforeReusingReplyMapping(String rawViewer) {
        UUID viewer = rawViewer == null ? null : UUID.fromString(rawViewer);
        var pageable = PageRequest.of(1000, 50);
        Page<CommentReplyResponseDto> page = Page.empty(pageable);
        when(repository.existsPublicEvent(eventId)).thenReturn(true);
        when(repository.existsEventRootComment(eventId, commentId)).thenReturn(true);
        when(comments.getReplies(viewer, commentId, pageable)).thenReturn(page);
        assertThat(service.getReplies(viewer, eventId, commentId, 1000, 50)).isSameAs(page);
        var order = inOrder(repository, comments);
        order.verify(repository).existsPublicEvent(eventId);
        order.verify(repository).existsEventRootComment(eventId, commentId);
        order.verify(comments).getReplies(viewer, commentId, pageable);
        verifyNoMoreInteractions(repository, comments);
    }

    @ParameterizedTest
    @CsvSource({"-1,20", "1001,20", "0,0", "0,51", "2147483647,2147483647"})
    void rejectsUnboundedPagingBeforeAnyRead(int page, int size) {
        assertError(() -> service.getComments(null, eventId, page, size), ErrorType.INVALID_PARAMETER);
        assertError(() -> service.getReplies(null, eventId, commentId, page, size), ErrorType.INVALID_PARAMETER);
        verifyNoInteractions(repository, comments);
    }

    @Test
    void rejectsMissingIdentifiersBeforeAnyRead() {
        assertError(() -> service.getComments(null, null, 0, 20), ErrorType.INVALID_PARAMETER);
        assertError(() -> service.getReplies(null, null, commentId, 0, 20), ErrorType.INVALID_PARAMETER);
        assertError(() -> service.getReplies(null, eventId, null, 0, 20), ErrorType.INVALID_PARAMETER);
        verifyNoInteractions(repository, comments);
    }

    @Test
    void missingDeletedOrIneligibleEventCannotExposeCommentsOrParentExistence() {
        assertError(() -> service.getComments(null, eventId, 0, 20), ErrorType.EVENT_NOT_FOUND);
        assertError(() -> service.getReplies(null, eventId, commentId, 0, 20), ErrorType.EVENT_NOT_FOUND);
        verify(repository, times(2)).existsPublicEvent(eventId);
        verifyNoMoreInteractions(repository);
        verifyNoInteractions(comments);
    }

    @Test
    void otherEventOtherTargetOrNestedParentCannotExposeReplies() {
        when(repository.existsPublicEvent(eventId)).thenReturn(true);
        assertError(() -> service.getReplies(null, eventId, commentId, 0, 20), ErrorType.COMMENT_NOT_FOUND);
        verify(repository).existsPublicEvent(eventId);
        verify(repository).existsEventRootComment(eventId, commentId);
        verifyNoMoreInteractions(repository);
        verifyNoInteractions(comments);
    }

    private static void assertError(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, ErrorType error) {
        assertThatThrownBy(action).isInstanceOfSatisfying(SoundConnectException.class,
                exception -> assertThat(exception.getErrorType()).isEqualTo(error));
    }
}

package com.berkayb.soundconnect.modules.like;

import com.berkayb.soundconnect.modules.engagement.enums.EngagementTargetType;
import com.berkayb.soundconnect.modules.engagement.service.EngagementTargetValidator;
import com.berkayb.soundconnect.modules.like.repository.LikeRepository;
import com.berkayb.soundconnect.modules.like.service.CommentLikeAccessGuard;
import com.berkayb.soundconnect.modules.like.service.LikeServiceImpl;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import java.util.UUID;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LikeServiceImplTest {
    @Mock LikeRepository repository;
    @Mock EngagementTargetValidator targets;
    @Mock CommentLikeAccessGuard comments;
    @Mock com.berkayb.soundconnect.modules.engagement.service.MediaEngagementNotificationService notifications;
    @InjectMocks LikeServiceImpl service;

    @Test void onlyNewDesiredLikeRequestsANotification() {
        UUID actor=UUID.randomUUID(), target=UUID.randomUUID();
        when(repository.lockActiveActor(actor)).thenReturn(Optional.of(actor));
        when(repository.insertIfAbsent(any(),eq(actor),eq("MEDIA"),eq(target))).thenReturn(1,0);
        service.like(actor,EngagementTargetType.MEDIA,target);
        service.like(actor,EngagementTargetType.MEDIA,target);
        service.unlike(actor,EngagementTargetType.MEDIA,target);
        verify(notifications,times(1)).liked(actor,EngagementTargetType.MEDIA,target);
        verifyNoMoreInteractions(notifications);
    }

    @ParameterizedTest @EnumSource(value=EngagementTargetType.class,names="COMMENT",mode=EnumSource.Mode.EXCLUDE)
    void existingTargetLikesKeepValidationAndUseAtomicDesiredWrites(EngagementTargetType type) {
        UUID actor=UUID.randomUUID(),target=UUID.randomUUID();
        when(repository.lockActiveActor(actor)).thenReturn(Optional.of(actor));
        service.like(actor,type,target); service.like(actor,type,target);
        service.unlike(actor,type,target); service.unlike(actor,type,target);
        verify(targets,times(4)).validateExists(type,target);
        verify(repository,times(2)).insertIfAbsent(any(),eq(actor),eq(type.name()),eq(target));
        verify(repository,times(2)).deleteDesiredLike(actor,type.name(),target);
        verify(repository,never()).existsByUserIdAndTargetTypeAndTargetId(any(),any(),any());
        verify(repository,never()).save(any());
        verifyNoInteractions(comments);
    }

    @Test void nonDuplicateIntegrityFailureIsNotSwallowedAsSuccessfulLike() {
        UUID actor=UUID.randomUUID(),target=UUID.randomUUID();
        when(repository.lockActiveActor(actor)).thenReturn(Optional.of(actor));
        var failure=new DataIntegrityViolationException("foreign key or schema failure");
        when(repository.insertIfAbsent(any(),eq(actor),eq("MEDIA"),eq(target))).thenThrow(failure);
        assertThatThrownBy(() -> service.like(actor,EngagementTargetType.MEDIA,target)).isSameAs(failure);
    }

    @ParameterizedTest @EnumSource(EngagementTargetType.class)
    void inactiveOrUnverifiedActorCannotLikeUnlikeOrReadPersonalState(EngagementTargetType type) {
        UUID actor=UUID.randomUUID(), target=UUID.randomUUID();
        for (Runnable operation : java.util.List.<Runnable>of(
                () -> service.like(actor,type,target), () -> service.unlike(actor,type,target),
                () -> service.isLiked(actor,type,target))) {
            assertThatThrownBy(operation::run).isInstanceOfSatisfying(SoundConnectException.class,
                    exception -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.UNAUTHORIZED));
        }
        verifyNoInteractions(targets, comments);
        verify(repository,never()).insertIfAbsent(any(),any(),any(),any());
        verify(repository,never()).deleteDesiredLike(any(),any(),any());
    }

    @ParameterizedTest @EnumSource(value=EngagementTargetType.class,names="COMMENT",mode=EnumSource.Mode.EXCLUDE)
    void countAndPersonalStateCannotReadHiddenTargetAggregates(EngagementTargetType type) {
        UUID actor=UUID.randomUUID(), target=UUID.randomUUID();
        when(repository.lockActiveActor(actor)).thenReturn(Optional.of(actor));
        var hidden = new SoundConnectException(ErrorType.ENGAGEMENT_NOT_FOUND);
        doThrow(hidden).when(targets).validateExists(type,target);
        assertThatThrownBy(() -> service.countLikes(type,target)).isSameAs(hidden);
        assertThatThrownBy(() -> service.isLiked(actor,type,target)).isSameAs(hidden);
        verify(repository,never()).countByTargetTypeAndTargetId(any(),any());
        verify(repository,never()).existsByUserIdAndTargetTypeAndTargetId(any(),any(),any());
    }
}

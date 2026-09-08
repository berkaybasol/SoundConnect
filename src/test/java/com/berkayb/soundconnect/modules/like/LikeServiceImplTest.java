package com.berkayb.soundconnect.modules.like;

import com.berkayb.soundconnect.modules.engagement.enums.EngagementTargetType;
import com.berkayb.soundconnect.modules.engagement.service.EngagementTargetValidator;
import com.berkayb.soundconnect.modules.like.repository.LikeRepository;
import com.berkayb.soundconnect.modules.like.service.CommentLikeAccessGuard;
import com.berkayb.soundconnect.modules.like.service.LikeServiceImpl;
import com.berkayb.soundconnect.modules.user.support.UserEntityFinder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LikeServiceImplTest {
    @Mock LikeRepository repository;
    @Mock UserEntityFinder users;
    @Mock EngagementTargetValidator targets;
    @Mock CommentLikeAccessGuard comments;
    @InjectMocks LikeServiceImpl service;

    @ParameterizedTest @EnumSource(value=EngagementTargetType.class,names="COMMENT",mode=EnumSource.Mode.EXCLUDE)
    void existingTargetLikesKeepValidationAndUseAtomicDesiredWrites(EngagementTargetType type) {
        UUID actor=UUID.randomUUID(),target=UUID.randomUUID();
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
        var failure=new DataIntegrityViolationException("foreign key or schema failure");
        when(repository.insertIfAbsent(any(),eq(actor),eq("MEDIA"),eq(target))).thenThrow(failure);
        assertThatThrownBy(() -> service.like(actor,EngagementTargetType.MEDIA,target)).isSameAs(failure);
    }
}

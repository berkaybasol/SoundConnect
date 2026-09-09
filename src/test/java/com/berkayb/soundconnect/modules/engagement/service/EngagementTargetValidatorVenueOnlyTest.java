package com.berkayb.soundconnect.modules.engagement.service;

import com.berkayb.soundconnect.modules.comment.repository.CommentTargetAccessRepository;
import com.berkayb.soundconnect.modules.comment.support.CommentTargetAccessGuard;
import com.berkayb.soundconnect.modules.engagement.enums.EngagementTargetType;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EngagementTargetValidatorVenueOnlyTest {
    @Mock CommentTargetAccessRepository repository;

    @Test void approvedPublicVenueEventRemainsEligible() {
        UUID event = UUID.randomUUID();
        when(repository.lockPublicEvent(event)).thenReturn(Optional.of(event));
        assertThatCode(() -> validator().validateExists(EngagementTargetType.EVENT, event)).doesNotThrowAnyException();
        verify(repository).lockPublicEvent(event);
    }

    @Test void existingButNonPublicEventCannotBeUsedForEngagement() {
        UUID event = UUID.randomUUID();
        // Existence/origin alone cannot admit pending venues, inactive owners,
        // calendar-hidden events or inconsistent location hierarchies.
        assertThatThrownBy(() -> validator().validateExists(EngagementTargetType.EVENT, event))
                .isInstanceOfSatisfying(SoundConnectException.class, exception ->
                        assertThat(exception.getErrorType()).isEqualTo(ErrorType.ENGAGEMENT_NOT_FOUND));
        verify(repository).lockPublicEvent(event);
    }

    @Test void invalidTargetFailsBeforeQueryingAndCommentUsesItsOwnAccessGuard() {
        assertThatThrownBy(() -> validator().validateExists(null, UUID.randomUUID()))
                .isInstanceOfSatisfying(SoundConnectException.class, exception ->
                        assertThat(exception.getErrorType()).isEqualTo(ErrorType.INVALID_PARAMETER));
        assertThatThrownBy(() -> validator().validateExists(EngagementTargetType.COMMENT, UUID.randomUUID()))
                .isInstanceOfSatisfying(SoundConnectException.class, exception ->
                        assertThat(exception.getErrorType()).isEqualTo(ErrorType.ENGAGEMENT_NOT_FOUND));
        verifyNoInteractions(repository);
    }

    private EngagementTargetValidatorImpl validator() {
        return new EngagementTargetValidatorImpl(new CommentTargetAccessGuard(repository));
    }
}

package com.berkayb.soundconnect.modules.engagement.service;

import com.berkayb.soundconnect.modules.comment.repository.CommentTargetAccessRepository;
import com.berkayb.soundconnect.modules.comment.support.CommentTargetAccessGuard;
import com.berkayb.soundconnect.modules.engagement.enums.EngagementTargetType;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EngagementTargetValidatorMediaLockTest {
    @Mock CommentTargetAccessRepository repository;
    UUID asset = UUID.randomUUID(), owner = UUID.randomUUID();

    @Test void visibilityIsLockedBeforeDeletionCompatibleMediaFence() {
        owner("LISTENER_PROFILE");
        when(repository.lockListenerVisibility(owner, true)).thenReturn(Optional.of(true));
        when(repository.lockPublicMedia(asset, "LISTENER_PROFILE", owner)).thenReturn(Optional.of(asset));
        assertThatCode(() -> validator().validateExists(EngagementTargetType.MEDIA, asset)).doesNotThrowAnyException();
        var order = inOrder(repository);
        order.verify(repository).mediaOwner(asset);
        order.verify(repository).lockListenerVisibility(owner, true);
        order.verify(repository).lockPublicMedia(asset, "LISTENER_PROFILE", owner);
    }

    @ParameterizedTest @ValueSource(strings={"LISTENER_PROFILE", "USER"})
    void ghostOrPendingListenerMediaIsRejectedBeforeMediaLock(String type) {
        owner(type);
        when(repository.lockListenerVisibility(owner, type.equals("LISTENER_PROFILE"))).thenReturn(Optional.of(false));
        assertHidden();
        verify(repository, never()).lockPublicMedia(any(), any(), any());
    }

    @Test void missingListenerProfileFailsClosed() {
        owner("LISTENER_PROFILE");
        assertHidden();
        verify(repository, never()).lockPublicMedia(any(), any(), any());
    }

    @Test void privateUnrenderableOrDeletionPendingAssetIsRejectedByLockedMediaPredicate() {
        owner("USER");
        assertHidden();
        verify(repository).lockPublicMedia(asset, "USER", owner);
    }

    @Test void validatorRequiresCallerTransactionSoLockCannotEscapeEarly() throws Exception {
        var transaction = EngagementTargetValidatorImpl.class.getMethod("validateExists", EngagementTargetType.class, UUID.class)
                .getAnnotation(Transactional.class);
        assertThat(transaction).isNotNull();
        assertThat(transaction.propagation()).isEqualTo(Propagation.MANDATORY);
    }

    @Test void listenerMediaAndSourceEngagementUseCurrentMainstageFences() {
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated("viewer", "n/a",
                        java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_LISTENER"))));
        try {
            owner("MUSICIAN_PROFILE");
            assertHidden();
            verify(repository).lockMainstagePublicMedia(asset, "MUSICIAN_PROFILE", owner);
            verify(repository, never()).lockPublicMedia(any(), any(), any());
            assertThatThrownBy(() -> validator().validateExists(EngagementTargetType.OVERTHINKING, asset))
                    .isInstanceOf(SoundConnectException.class);
            verify(repository).lockMainstagePost(asset);
            verify(repository, never()).lockPost(any());
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    private void owner(String type) {
        var metadata = mock(CommentTargetAccessRepository.MediaOwner.class);
        when(metadata.getOwnerType()).thenReturn(type);
        when(metadata.getOwnerId()).thenReturn(owner);
        when(repository.mediaOwner(asset)).thenReturn(Optional.of(metadata));
    }
    private void assertHidden() {
        assertThatThrownBy(() -> validator().validateExists(EngagementTargetType.MEDIA, asset))
                .isInstanceOfSatisfying(SoundConnectException.class, exception ->
                        assertThat(exception.getErrorType()).isEqualTo(ErrorType.ENGAGEMENT_NOT_FOUND));
    }
    private EngagementTargetValidatorImpl validator() {
        return new EngagementTargetValidatorImpl(new CommentTargetAccessGuard(repository, org.mockito.Mockito.mock(com.berkayb.soundconnect.modules.promotion.announcement.AnnouncementAccess.class)));
    }
}

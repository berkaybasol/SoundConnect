package com.berkayb.soundconnect.modules.comment.service;

import com.berkayb.soundconnect.modules.comment.repository.CommentAuthorRepository;
import com.berkayb.soundconnect.modules.comment.support.CommentAuthorBatchResolver;
import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.enums.ListenerVisibilityMode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CommentAuthorBatchResolverTest {
    @Mock CommentAuthorRepository repository;
    @Mock MediaAssetService media;
    @InjectMocks CommentAuthorBatchResolver resolver;
    final UUID author=UUID.randomUUID(), avatar=UUID.randomUUID();

    @Test void emptyAndOversizedBatchesDoNotQuery() {
        assertThat(resolver.resolve(Set.of())).isEmpty();
        var ids=new HashSet<UUID>(); for(int i=0;i<51;i++) ids.add(UUID.randomUUID());
        assertThatThrownBy(() -> resolver.resolve(ids)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository,media);
    }

    @Test void configuredPrivateOrFailedAvatarNeverFallsBackToStaleLegacyIdentity() {
        var row=candidate();
        when(row.getStudio()).thenReturn(avatar);
        when(media.getDisplayUrlMap(List.of(avatar))).thenReturn(Map.of());
        assertThat(resolver.resolve(Set.of(author)).get(author).avatarUrl()).isNull();
        verify(row,never()).getLegacyAvatar();
    }

    @Test void configuredPersonalAvatarWinsOverVenueAndLegacyAvatar() {
        var row=candidate(); var venue=UUID.randomUUID();
        when(row.getListener()).thenReturn(avatar); when(row.getVenue()).thenReturn(venue);
        when(media.getDisplayUrlMap(List.of(avatar,venue))).thenReturn(Map.of(avatar,"https://cdn.test/listener.jpg",venue,"https://cdn.test/venue.jpg"));
        assertThat(resolver.resolve(Set.of(author)).get(author).avatarUrl()).isEqualTo("https://cdn.test/listener.jpg");
        verify(media,never()).getDisplayUrl(any());
    }

    @Test void legacyUuidIsResolvedThroughPublicReadyMediaAndRawPathsAreNotExposed() {
        var row=candidate(); when(row.getLegacyAvatar()).thenReturn(avatar.toString());
        when(media.getDisplayUrlMap(List.of(avatar))).thenReturn(Map.of(avatar,"https://cdn.test/legacy.jpg"));
        assertThat(resolver.resolve(Set.of(author)).get(author).avatarUrl()).isEqualTo("https://cdn.test/legacy.jpg");
        when(row.getLegacyAvatar()).thenReturn("private/storage/key.jpg");
        assertThat(resolver.resolve(Set.of(author)).get(author).avatarUrl()).isNull();
    }

    @Test void ghostUsesOnlyCanonicalListenerAvatarAndNeverQueriesAlternateProfiles() {
        var ghost=mock(CommentAuthorRepository.ListenerIdentity.class);
        when(ghost.getUserId()).thenReturn(author); when(ghost.getMode()).thenReturn("GHOST");
        when(ghost.getChoiceCompleted()).thenReturn(true); when(ghost.getUsername()).thenReturn("  canonical  ");
        when(ghost.getAvatar()).thenReturn(avatar);
        when(repository.lockListenerIdentities(Set.of(author))).thenReturn(List.of(ghost));
        when(media.getDisplayUrlMap(List.of(avatar))).thenReturn(Map.of(avatar,"https://cdn.test/ghost.jpg"));
        var result=resolver.resolve(Set.of(author)).get(author);
        assertThat(result.username()).isEqualTo("canonical");
        assertThat(result.avatarUrl()).isEqualTo("https://cdn.test/ghost.jpg");
        assertThat(result.visibilityMode()).isEqualTo(ListenerVisibilityMode.GHOST);
        verify(repository,never()).candidates(any());
    }

    @Test void pendingListenerIsAnonymousCompatibilityIdentityWithoutMediaOrAlternateProfileQueries() {
        var pending=mock(CommentAuthorRepository.ListenerIdentity.class);
        when(pending.getUserId()).thenReturn(author);
        when(repository.lockListenerIdentities(Set.of(author))).thenReturn(List.of(pending));
        var result=resolver.resolve(Set.of(author)).get(author);
        assertThat(result.username()).isEqualTo("Kullanici");
        assertThat(result.avatarUrl()).isNull();
        assertThat(result.visibilityMode()).isEqualTo(ListenerVisibilityMode.GHOST);
        verify(repository,never()).candidates(any()); verifyNoInteractions(media);
    }

    @Test void privacyQueryFailureFailsClosedRatherThanUsingLegacyIdentity() {
        when(repository.lockListenerIdentities(Set.of(author))).thenThrow(new IllegalStateException("database unavailable"));
        assertThatThrownBy(() -> resolver.resolve(Set.of(author))).isInstanceOf(IllegalStateException.class);
        verify(repository,never()).candidates(any()); verifyNoInteractions(media);
    }

    private CommentAuthorRepository.Candidate candidate() {
        var row=mock(CommentAuthorRepository.Candidate.class);
        when(row.getUserId()).thenReturn(author); when(row.getUsername()).thenReturn("canonical");
        when(repository.candidates(List.of(author))).thenReturn(List.of(row));
        return row;
    }
}

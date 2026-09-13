package com.berkayb.soundconnect.modules.feed.musician.feedback;

import com.berkayb.soundconnect.modules.feed.musician.core.MusicianFeedViewerGuard;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MusicianFeedMutedAuthorsServiceTest {
    private final MusicianFeedViewerGuard viewers = mock(MusicianFeedViewerGuard.class);
    private final MusicianFeedMutedAuthorsRepository repository = mock(MusicianFeedMutedAuthorsRepository.class);
    private final MusicianFeedMutedAuthorsCursorCodec cursors = mock(MusicianFeedMutedAuthorsCursorCodec.class);
    private final MusicianFeedMutedAuthorsService service = new MusicianFeedMutedAuthorsService(viewers, repository, cursors);
    private final UUID viewer = UUID.randomUUID();

    @Test
    void verifiesTheViewerBeforeAnyPreferenceRead() {
        doThrow(new SoundConnectException(ErrorType.FORBIDDEN_ACCESS)).when(viewers).requireMusicianProfile(viewer);
        assertThatThrownBy(() -> service.get(viewer, null, null)).isInstanceOf(SoundConnectException.class);
        verifyNoInteractions(repository, cursors);
    }

    @Test
    void validatesLimitsBeforeQuerying() {
        for (int limit : new int[]{-1, 0, 51, Integer.MAX_VALUE}) {
            assertThatThrownBy(() -> service.get(viewer, limit, null))
                    .isInstanceOfSatisfying(SoundConnectException.class,
                            failure -> assertThat(failure.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST));
        }
        verifyNoInteractions(repository, cursors);
    }

    @Test
    void defaultAndMaximumPagesFetchOnlyOneLookaheadRow() {
        when(repository.findPage(eq(viewer), any(), isNull(), anyInt())).thenReturn(List.of());
        assertThat(service.get(viewer, null, null).items()).isEmpty();
        assertThat(service.get(viewer, 50, null).hasMore()).isFalse();
        verify(repository).findPage(eq(viewer), any(), isNull(), eq(31));
        verify(repository).findPage(eq(viewer), any(), isNull(), eq(51));
    }
}

package com.berkayb.soundconnect.modules.overthinking.service;

import com.berkayb.soundconnect.modules.overthinking.dto.request.OverthinkingPostSaveRequestDto;
import com.berkayb.soundconnect.modules.overthinking.enums.OverthinkingVisibilityType;
import com.berkayb.soundconnect.modules.spotify.client.SpotifyApiClient;
import com.berkayb.soundconnect.modules.spotify.dto.response.SpotifyTrackItemDto;
import com.berkayb.soundconnect.shared.exception.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class OverthinkingPostCommandServiceTest {
    private static final String TRACK = "4uLU6hMCjMI75M1A2tKUQC";
    private final SpotifyApiClient spotify = mock(SpotifyApiClient.class);
    private final OverthinkingPostService posts = mock(OverthinkingPostService.class);
    private final OverthinkingPostCommandService commands = new OverthinkingPostCommandService(spotify, posts);

    @Test void oneAuthoritativeSnapshotIsPassedToTheTransactionalWriterWithoutSerialPageEnrichment() {
        UUID author = UUID.randomUUID(); var request = command("https://i.scdn.co/image/art");
        var snapshot = new SpotifyTrackItemDto(TRACK, "Song", 1, false, null, request.spotifyTrackUrl(), null,
                request.spotifyAlbumImageUrl(), List.of("Artist"));
        when(spotify.getTracksByIds(List.of(TRACK))).thenReturn(List.of(snapshot));
        commands.create(author, request);
        var order = inOrder(spotify, posts);
        order.verify(spotify).getTracksByIds(List.of(TRACK));
        order.verify(posts).createWithSnapshot(author, request, snapshot);
        verifyNoMoreInteractions(spotify);
    }

    @Test void providerFailureStillPublishesTheValidatedSnapshotHints() {
        UUID author = UUID.randomUUID(); var request = command("https://i.scdn.co/image/art");
        when(spotify.getTracksByIds(List.of(TRACK))).thenThrow(new SoundConnectException(ErrorType.SPOTIFY_RATE_LIMITED));
        commands.create(author, request);
        verify(posts).createWithSnapshot(author, request, null);
    }

    @Test void unsafeArtworkIsRejectedBeforeAnyExternalOrDatabaseWork() {
        assertThatThrownBy(() -> commands.create(UUID.randomUUID(), command("https://tracker.example/pixel")))
                .isInstanceOfSatisfying(SoundConnectException.class,
                        error -> assertThat(error.getErrorType()).isEqualTo(ErrorType.OVERTHINKING_SPOTIFY_SOURCE_INVALID));
        verifyNoInteractions(spotify, posts);
    }

    private OverthinkingPostSaveRequestDto command(String image) {
        return new OverthinkingPostSaveRequestDto("Title", "Body", OverthinkingVisibilityType.VISIBLE,
                "https://open.spotify.com/track/"+TRACK, null, "Hint song", "Hint artist", image, null, null, UUID.randomUUID());
    }
}

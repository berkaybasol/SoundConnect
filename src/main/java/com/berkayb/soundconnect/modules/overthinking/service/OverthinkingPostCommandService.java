package com.berkayb.soundconnect.modules.overthinking.service;

import com.berkayb.soundconnect.modules.overthinking.dto.request.OverthinkingPostSaveRequestDto;
import com.berkayb.soundconnect.modules.overthinking.dto.response.OverthinkingPostResponseDto;
import com.berkayb.soundconnect.modules.overthinking.support.OverthinkingSpotifyReference;
import com.berkayb.soundconnect.modules.spotify.client.SpotifyApiClient;
import com.berkayb.soundconnect.modules.spotify.dto.response.SpotifyTrackItemDto;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.UUID;

/** Provider enrichment completes before the short, atomic post/receipt transaction acquires database locks. */
@Service @RequiredArgsConstructor @Slf4j
public class OverthinkingPostCommandService {
    private final SpotifyApiClient spotify;
    private final OverthinkingPostService posts;

    @Transactional(propagation = Propagation.NEVER)
    public OverthinkingPostResponseDto create(UUID authorId, OverthinkingPostSaveRequestDto request) {
        SpotifyTrackItemDto snapshot = null;
        // Validate untrusted URLs before attempting any provider call.
        OverthinkingSpotifyReference.imageUrl(request.spotifyAlbumImageUrl());
        OverthinkingSpotifyReference.artistId(request.spotifyArtistId());
        if (request.spotifyTrackUrl() != null && !request.spotifyTrackUrl().isBlank()) {
            if (request.musicianTrackId() != null || request.bandTrackId() != null)
                throw new SoundConnectException(ErrorType.OVERTHINKING_MULTIPLE_MUSIC_SOURCE);
            String id = OverthinkingSpotifyReference.parse(request.spotifyTrackUrl()).trackId();
            try {
                snapshot = spotify.getTracksByIds(List.of(id)).stream()
                        .filter(track -> id.equals(track.spotifyTrackId())).findFirst().orElse(null);
            } catch (RuntimeException unavailable) {
                log.warn("Overthinking creation retained validated music hints; Spotify unavailable: {}",
                        unavailable.getClass().getSimpleName());
            }
        }
        return posts.createWithSnapshot(authorId, request, snapshot);
    }
}

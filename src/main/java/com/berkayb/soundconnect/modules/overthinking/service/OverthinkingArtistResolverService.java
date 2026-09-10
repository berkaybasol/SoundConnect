package com.berkayb.soundconnect.modules.overthinking.service;

import com.berkayb.soundconnect.modules.overthinking.dto.request.OverthinkingPostSaveRequestDto;
import com.berkayb.soundconnect.modules.overthinking.entity.OverthinkingPost;

public interface OverthinkingArtistResolverService {
	
	void resolveAndSetArtist(OverthinkingPost post, OverthinkingPostSaveRequestDto dto);

    void resolveAndSetArtist(OverthinkingPost post, OverthinkingPostSaveRequestDto dto,
            com.berkayb.soundconnect.modules.spotify.dto.response.SpotifyTrackItemDto snapshot);
}

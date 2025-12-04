package com.berkayb.soundconnect.modules.overthinking.service;

import com.berkayb.soundconnect.modules.overthinking.dto.request.OverthinkingPostSaveRequestDto;
import com.berkayb.soundconnect.modules.overthinking.entity.OverthinkingPost;

public interface OverthinkingArtistResolverService {
	
	void resolveAndSetArtist(OverthinkingPost post, OverthinkingPostSaveRequestDto dto);
}
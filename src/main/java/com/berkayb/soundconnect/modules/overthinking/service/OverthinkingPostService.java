package com.berkayb.soundconnect.modules.overthinking.service;

import com.berkayb.soundconnect.modules.overthinking.dto.request.OverthinkingPostSaveRequestDto;
import com.berkayb.soundconnect.modules.overthinking.dto.response.OverthinkingPostResponseDto;
import com.berkayb.soundconnect.modules.overthinking.enums.OverthinkingFeedOrder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;
import java.util.List;
import java.util.Map;

public interface OverthinkingPostService {
	
	OverthinkingPostResponseDto create(UUID authorId, OverthinkingPostSaveRequestDto dto);

    OverthinkingPostResponseDto createWithSnapshot(UUID authorId, OverthinkingPostSaveRequestDto dto,
            com.berkayb.soundconnect.modules.spotify.dto.response.SpotifyTrackItemDto snapshot);
	
	OverthinkingPostResponseDto update(UUID postId, UUID authorId, OverthinkingPostSaveRequestDto dto);
	
	void delete(UUID postId, UUID authorId);
	
	OverthinkingPostResponseDto getById(UUID postId, UUID viewerId);

	Map<UUID, OverthinkingPostResponseDto> getByIdsForViewer(UUID viewerId, List<UUID> postIds);
	
	Page<OverthinkingPostResponseDto> getAll(UUID viewerId, Pageable pageable);

	Page<OverthinkingPostResponseDto> getAll(UUID viewerId, Pageable pageable, OverthinkingFeedOrder order);
	
	Page<OverthinkingPostResponseDto> getMyPosts(UUID userId, Pageable pageable);
	
	Page<OverthinkingPostResponseDto> getPostsByArtist(UUID artistId, UUID viewerId, Pageable pageable);
}

package com.berkayb.soundconnect.modules.overthinking.service;

import com.berkayb.soundconnect.modules.overthinking.dto.request.OverthinkingPostSaveRequestDto;
import com.berkayb.soundconnect.modules.overthinking.dto.response.OverthinkingPostResponseDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface OverthinkingPostService {
	
	OverthinkingPostResponseDto create(UUID authorId, OverthinkingPostSaveRequestDto dto);
	
	OverthinkingPostResponseDto update(UUID postId, UUID authorId, OverthinkingPostSaveRequestDto dto);
	
	void delete(UUID postId, UUID authorId);
	
	OverthinkingPostResponseDto getById(UUID postId, UUID viewerId);
	
	Page<OverthinkingPostResponseDto> getAll(UUID viewerId, Pageable pageable);
	
	Page<OverthinkingPostResponseDto> getMyPosts(UUID userId, Pageable pageable);
	
	Page<OverthinkingPostResponseDto> getPostsByArtist(UUID artistId, UUID viewerId, Pageable pageable);
}
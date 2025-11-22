package com.berkayb.soundconnect.modules.collab.service;

import com.berkayb.soundconnect.modules.collab.dto.request.CollabCreateRequestDto;
import com.berkayb.soundconnect.modules.collab.dto.request.CollabFilterRequestDto;
import com.berkayb.soundconnect.modules.collab.dto.request.CollabUpdateRequestDto;
import com.berkayb.soundconnect.modules.collab.dto.response.CollabResponseDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface CollabService {
	
	CollabResponseDto create(UUID authenticatedUserId, CollabCreateRequestDto dto);
	
	CollabResponseDto update(UUID collabId, UUID authenticatedUserId, CollabUpdateRequestDto dto);
	
	void delete(UUID collabId, UUID authenticatedUserId);
	
	CollabResponseDto getById(UUID collabId, UUID authenticatedUserId);
	
	Page<CollabResponseDto> search(UUID authenticatedUserId,
	                               CollabFilterRequestDto filter,
	                               Pageable pageable);
}
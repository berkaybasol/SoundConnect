package com.berkayb.soundconnect.modules.collab.service;

import com.berkayb.soundconnect.modules.collab.dto.request.CollabFillSlotRequestDto;
import com.berkayb.soundconnect.modules.collab.dto.request.CollabUnfillSlotRequestDto;
import com.berkayb.soundconnect.modules.collab.dto.response.CollabResponseDto;

import java.util.UUID;

public interface CollabSlotManagementService {
	
	CollabResponseDto fill (UUID authenticatedUserId, UUID collabId, CollabFillSlotRequestDto dto);
	
	CollabResponseDto unfill (UUID authenticatedUserId, UUID collabId, CollabUnfillSlotRequestDto dto);
}
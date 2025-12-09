package com.berkayb.soundconnect.modules.setlistcreator.service;

import com.berkayb.soundconnect.modules.setlistcreator.dto.request.SetlistCreateRequestDto;
import com.berkayb.soundconnect.modules.setlistcreator.dto.request.SetlistItemRequestDto;
import com.berkayb.soundconnect.modules.setlistcreator.dto.request.SetlistSetRequestDto;
import com.berkayb.soundconnect.modules.setlistcreator.dto.response.SetlistResponseDto;

import java.util.UUID;

public interface SetlistService {
	
	SetlistResponseDto createSetlist(SetlistCreateRequestDto request);
	
	SetlistResponseDto addSetToSetlist(UUID setlistId, SetlistSetRequestDto request);
	
	SetlistResponseDto addItemToSet(UUID setId, SetlistItemRequestDto request);
	
	SetlistResponseDto getSetlistDetail(UUID setlistId);
	
	void deleteSetlist(UUID setlistId);
}
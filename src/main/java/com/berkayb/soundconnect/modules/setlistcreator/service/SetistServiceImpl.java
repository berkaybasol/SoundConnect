package com.berkayb.soundconnect.modules.setlistcreator.service;

import com.berkayb.soundconnect.modules.setlistcreator.dto.request.SetlistCreateRequestDto;
import com.berkayb.soundconnect.modules.setlistcreator.dto.request.SetlistItemRequestDto;
import com.berkayb.soundconnect.modules.setlistcreator.dto.request.SetlistSetRequestDto;
import com.berkayb.soundconnect.modules.setlistcreator.dto.response.SetlistResponseDto;
import com.berkayb.soundconnect.modules.setlistcreator.mapper.SetlistItemMapper;
import com.berkayb.soundconnect.modules.setlistcreator.mapper.SetlistMapper;
import com.berkayb.soundconnect.modules.setlistcreator.mapper.SetlistSetMapper;
import com.berkayb.soundconnect.modules.setlistcreator.repository.SetlistItemRepository;
import com.berkayb.soundconnect.modules.setlistcreator.repository.SetlistRepository;
import com.berkayb.soundconnect.modules.setlistcreator.repository.SetlistSetRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;


@Service
@RequiredArgsConstructor
@Slf4j
public class SetistServiceImpl implements SetlistService{
	
	private final SetlistRepository setlistRepository;
	private final SetlistSetRepository setlistSetRepository;
	private final SetlistItemRepository setlistItemRepository;
	private final SetlistMapper setlistMapper;
	private final SetlistSetMapper setlistSetMapper;
	private final SetlistItemMapper setlistItemMapper;
	private final
	
	
	@Override
	public SetlistResponseDto createSetlist(SetlistCreateRequestDto request) {
		return null;
	}
	
	@Override
	public SetlistResponseDto addSetToSetlist(UUID setlistId, SetlistSetRequestDto request) {
		return null;
	}
	
	@Override
	public SetlistResponseDto addItemToSet(UUID setId, SetlistItemRequestDto request) {
		return null;
	}
	
	@Override
	public SetlistResponseDto getSetlistDetail(UUID setlistId) {
		return null;
	}
	
	@Override
	public void deleteSetlist(UUID setlistId) {
	
	}
}
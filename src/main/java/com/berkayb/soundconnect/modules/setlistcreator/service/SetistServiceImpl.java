package com.berkayb.soundconnect.modules.setlistcreator.service;

import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity.Band;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.support.BandEntityFinder;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.entity.MusicianProfile;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.support.MusicianProfileEntityFinder;
import com.berkayb.soundconnect.modules.setlistcreator.dto.request.SetlistCreateRequestDto;
import com.berkayb.soundconnect.modules.setlistcreator.dto.request.SetlistItemRequestDto;
import com.berkayb.soundconnect.modules.setlistcreator.dto.request.SetlistSetRequestDto;
import com.berkayb.soundconnect.modules.setlistcreator.dto.response.SetlistResponseDto;
import com.berkayb.soundconnect.modules.setlistcreator.entity.Setlist;
import com.berkayb.soundconnect.modules.setlistcreator.entity.SetlistItem;
import com.berkayb.soundconnect.modules.setlistcreator.entity.SetlistSet;
import com.berkayb.soundconnect.modules.setlistcreator.mapper.SetlistItemMapper;
import com.berkayb.soundconnect.modules.setlistcreator.mapper.SetlistMapper;
import com.berkayb.soundconnect.modules.setlistcreator.mapper.SetlistSetMapper;
import com.berkayb.soundconnect.modules.setlistcreator.repository.SetlistItemRepository;
import com.berkayb.soundconnect.modules.setlistcreator.repository.SetlistRepository;
import com.berkayb.soundconnect.modules.setlistcreator.repository.SetlistSetRepository;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.springframework.transaction.annotation.Transactional;
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
	private final MusicianProfileEntityFinder musicianProfileFinder;
	private final BandEntityFinder bandEntityFinder;
	
	
	@Override
	@Transactional
	public SetlistResponseDto createSetlist(SetlistCreateRequestDto request) {
		log.info("Creating setlist with name: {}", request.name());
		
		Setlist setlist = setlistMapper.toEntity(request);
		
		if (request.musicianProfileId() != null) {
			MusicianProfile profile =
					musicianProfileFinder.getMusician(request.musicianProfileId());
			setlist.setMusicianProfile(profile);
		} else if (request.bandId() != null) {
			Band band = bandEntityFinder.getBand(request.bandId());
			setlist.setBand(band);
		} else {
			throw new SoundConnectException(ErrorType.BAD_REQUEST, "Setlist must belong to musician profile or band");
		}
		return setlistMapper.toResponse(setlistRepository.save(setlist));
	}
	
	@Override
	@Transactional
	public SetlistResponseDto addSetToSetlist(UUID setlistId, SetlistSetRequestDto request) {
		Setlist setlist = setlistRepository.findById(setlistId)
				.orElseThrow(()-> new SoundConnectException(ErrorType.SETLIST_NOT_FOUND));
		SetlistSet set = setlistSetMapper.toEntity(request);
		set.setSetlist(setlist);
		setlist.addSet(set);
		return setlistMapper.toResponse(setlistRepository.save(setlist));
	}
	
	@Override
	public SetlistResponseDto addItemToSet(UUID setId, SetlistItemRequestDto request) {
		SetlistSet set = setlistSetRepository.findById(setId)
		                                     .orElseThrow(() -> new SoundConnectException(ErrorType.SETLIST_SET_NOT_FOUND));
		
		SetlistItem item = setlistItemMapper.toEntity(request);
		item.setSet(set);
		
		set.addItem(item);
		
		setlistSetRepository.save(set);
		return setlistMapper.toResponse(set.getSetlist());
	}
	
	@Override
	@Transactional(readOnly = true)
	public SetlistResponseDto getSetlistDetail(UUID setlistId) {
		Setlist setlist = setlistRepository.findById(setlistId)
				.orElseThrow(() -> new SoundConnectException(ErrorType.SETLIST_NOT_FOUND));
		return setlistMapper.toResponse(setlist);
	}
	
	@Override
	@Transactional
	public void deleteSetlist(UUID setlistId) {
		if (!setlistRepository.existsById(setlistId)) {
			throw new SoundConnectException(ErrorType.SETLIST_NOT_FOUND);
		}
		setlistRepository.deleteById(setlistId);
	}
}
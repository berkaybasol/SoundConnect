package com.berkayb.soundconnect.modules.collab.service;

import com.berkayb.soundconnect.modules.collab.dto.request.CollabFillSlotRequestDto;
import com.berkayb.soundconnect.modules.collab.dto.request.CollabUnfillSlotRequestDto;
import com.berkayb.soundconnect.modules.collab.dto.response.CollabResponseDto;
import com.berkayb.soundconnect.modules.collab.entity.Collab;
import com.berkayb.soundconnect.modules.collab.mapper.CollabMapper;
import com.berkayb.soundconnect.modules.collab.repository.CollabRepository;
import com.berkayb.soundconnect.modules.collab.support.finder.CollabEntityFinder;
import com.berkayb.soundconnect.modules.collab.support.validations.CollabValidations;
import com.berkayb.soundconnect.modules.instrument.entity.Instrument;
import com.berkayb.soundconnect.modules.instrument.support.InstrumentEntityFinder;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.support.UserEntityFinder;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class CollabSlotManagementServiceImpl implements CollabSlotManagementService{
	
	private final CollabRepository collabRepository;
	private final CollabMapper collabMapper;
	private final UserEntityFinder userEntityFinder;
	private final InstrumentEntityFinder instrumentEntityFinder;
	private final CollabValidations collabValidations;
	private final CollabEntityFinder collabEntityFinder;
	
	
	@Override
	public CollabResponseDto fill(UUID authenticatedUserId, UUID collabId, CollabFillSlotRequestDto dto) {
		log.info("[CollabSlot] Filling slot for collab {} by user {}", collabId, authenticatedUserId);
		
		User owner = userEntityFinder.getUser(authenticatedUserId);
		
		Collab collab = collabEntityFinder.getCollab(collabId);
		
		collabValidations.validateOwner(collab, owner);
		collabValidations.validateNotExpired(collab);
		
		Instrument instrument = instrumentEntityFinder.getInstrument(dto.instrumentId());
		
		collabValidations.validateRequired(collab, instrument);
		collabValidations.validateNotAlreadyFilled(collab, instrument);
		
		collab.getFilledInstruments().add(instrument);
		collabRepository.save(collab);
		
		return collabMapper.toResponseDto(collab);
		
	}
	
	@Override
	public CollabResponseDto unfill(UUID authenticatedUserId, UUID collabId, CollabUnfillSlotRequestDto dto) {
		log.info("[CollabSlot] Unfilling slot for collab {} by user {}", collabId, authenticatedUserId);
		
		User owner = userEntityFinder.getUser(authenticatedUserId);
		
		Collab collab = collabEntityFinder.getCollab(collabId);
		
		collabValidations.validateOwner(collab, owner);
		
		Instrument instrument = instrumentEntityFinder.getInstrument(dto.instrumentId());
		
		collabValidations.validateRequired(collab, instrument);
		collabValidations.validateFilled(collab, instrument);
		
		collab.getFilledInstruments().remove(instrument);
		collabRepository.save(collab);
		
		return collabMapper.toResponseDto(collab);
	}
}
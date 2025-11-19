package com.berkayb.soundconnect.modules.collab.service;

import com.berkayb.soundconnect.modules.collab.dto.request.CollabCreateRequestDto;
import com.berkayb.soundconnect.modules.collab.dto.request.CollabFilterRequestDto;
import com.berkayb.soundconnect.modules.collab.dto.request.CollabUpdateRequestDto;
import com.berkayb.soundconnect.modules.collab.dto.response.CollabResponseDto;
import com.berkayb.soundconnect.modules.collab.entity.Collab;
import com.berkayb.soundconnect.modules.collab.enums.CollabRole;
import com.berkayb.soundconnect.modules.collab.mapper.CollabMapper;
import com.berkayb.soundconnect.modules.collab.repository.CollabRepository;
import com.berkayb.soundconnect.modules.collab.ttl.service.CollabTTLService;
import com.berkayb.soundconnect.modules.instrument.entity.Instrument;
import com.berkayb.soundconnect.modules.collab.spec.CollabSpecifications;
import com.berkayb.soundconnect.modules.instrument.support.InstrumentEntityFinder;
import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.location.support.LocationEntityFinder;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.support.UserEntityFinder;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class CollabServiceImpl implements CollabService{
	
	private final CollabRepository collabRepository;
	private final CollabMapper collabMapper;
	private final UserEntityFinder userFinder;
	private final LocationEntityFinder locationEntityFinder;
	private final InstrumentEntityFinder instrumentEntityFinder;
	private final CollabTTLService collabTTLService;
	
	@Override
	public CollabResponseDto create(UUID authenticatedUserId, CollabCreateRequestDto dto) {
		log.info("[CollabService] Creating new collab for user {}", authenticatedUserId);
		
		// 1) owner validate
		User owner = userFinder.getUser(authenticatedUserId);
		
		// 2) owner role resolve
		CollabRole ownerRole = resolveOwnerRole(owner);
		
		// 3) entity olustur
		Collab collab = collabMapper.toEntity(dto);
		collab.setOwner(owner);
		collab.setOwnerRole(ownerRole);
		
		// 4) city resolve
		City city = locationEntityFinder.getCity(dto.cityId());
		collab.setCity(city);
		
		// 5) required instruments resolve
		Set<Instrument> required = instrumentEntityFinder.getInstrumentsByIds(dto.requiredInstrumentIds());
		collab.setRequiredInstruments(required);
		
		// 6) filled = boşs
		collab.setFilledInstruments(Set.of());
		
		// 7) daily validation
		validateDailyFields(dto.daily(), dto.expirationTime());
		
		// 8) save
		collabRepository.save(collab);
		
		// 9) redis TTL
		 if (collab.isDaily()) {
			 collabTTLService.setTTL(collab.getId(), collab.getExpirationTime());
		 }
		
		return collabMapper.toResponseDto(collab, authenticatedUserId);
	}
	
	@Override
	public CollabResponseDto update(UUID collabId, UUID authenticatedUserId, CollabUpdateRequestDto dto) {
		log.info("[CollabService] Updating collab {} by user {}", collabId, authenticatedUserId);
		
		Collab collab = collabRepository.findByIdAndOwner_Id(collabId, authenticatedUserId)
		                                .orElseThrow(() -> new SoundConnectException(ErrorType.COLLAB_NOT_FOUND_OR_NOT_OWNER));
		
		boolean oldDaily = collab.isDaily();
		LocalDateTime oldExpiration = collab.getExpirationTime();
		
		collabMapper.updateEntity(collab, dto);
		
		City city = locationEntityFinder.getCity(dto.cityId());
		collab.setCity(city);
		
		Set<Instrument> required = instrumentEntityFinder.getInstrumentsByIds(dto.requiredInstrumentIds());
		collab.setRequiredInstruments(required);
		
		validateDailyFields(dto.daily(), dto.expirationTime());
		
		collabRepository.save(collab);
		
		// -------------------------
		// TTL RESET LOGIC
		// -------------------------
		if (collab.isDaily()) {
			
			boolean expirationChanged =
					oldExpiration == null ||
							!oldExpiration.equals(collab.getExpirationTime());
			
			boolean dailyFlagChanged = oldDaily != collab.isDaily();
			
			if (expirationChanged || dailyFlagChanged) {
				collabTTLService.resetTTL(collab.getId(), collab.getExpirationTime());
			}
			
		} else {
			// Eğer daily devre dışı bırakıldıysa TTL key de silinsin
			collabTTLService.deleteTTL(collab.getId());
		}
		
		return collabMapper.toResponseDto(collab, authenticatedUserId);
	}
	
	@Override
	public void delete(UUID collabId, UUID authenticatedUserId) {
		log.info("[CollabService] Deleting collab {} by user {}", collabId, authenticatedUserId);
		
		Collab collab = collabRepository.findByIdAndOwner_Id(collabId, authenticatedUserId)
		                                .orElseThrow(() -> new SoundConnectException(ErrorType.COLLAB_NOT_FOUND_OR_NOT_OWNER));
		
		// TTL key'i temizle
		collabTTLService.deleteTTL(collab.getId());
		
		collabRepository.delete(collab);
	}
	
	@Override
	public CollabResponseDto getById(UUID collabId) {
		log.info("[CollabService] Fetching collab {}", collabId);
		
		Collab collab = collabRepository.findById(collabId)
		                                .orElseThrow(() -> new SoundConnectException(ErrorType.COLLAB_NOT_FOUND));
		
		return collabMapper.toResponseDto(collab);
	}
	
	@Override
	public Page<CollabResponseDto> search(CollabFilterRequestDto filter, Pageable pageable) {
		log.info("[CollabService] Searching collabs with filter {}", filter);
		
		// Specifation bu modulde sonraki adimla birlikte yazilacak
		 var spec = CollabSpecifications.filter(filter);
		
		return collabRepository.findAll(spec, pageable)
		                       .map(collabMapper::toResponseDto);
	}
	
	
	// ---------------- HELPER METHODS ------------------ //
	
	private CollabRole resolveOwnerRole(User user) {
		// Şimdilik user profile üzerinden manuel bir map yapılabilir.
		// İleride: user.getProfile().getPrimaryRole() gibi bir yapı olacak.
		return CollabRole.MUSICIAN; // Geçici
	}
	
	private void validateDailyFields(boolean daily, LocalDateTime expirationTime) {
		if (daily && expirationTime == null) {
			throw new SoundConnectException(ErrorType.COLLAB_EXPIRATION_REQUIRED);
		}
	}
}
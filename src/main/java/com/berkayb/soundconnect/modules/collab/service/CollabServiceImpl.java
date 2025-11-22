package com.berkayb.soundconnect.modules.collab.service;

import com.berkayb.soundconnect.modules.collab.dto.request.CollabCreateRequestDto;
import com.berkayb.soundconnect.modules.collab.dto.request.CollabFilterRequestDto;
import com.berkayb.soundconnect.modules.collab.dto.request.CollabUpdateRequestDto;
import com.berkayb.soundconnect.modules.collab.dto.request.RequiredSlotRequestDto;
import com.berkayb.soundconnect.modules.collab.dto.response.CollabResponseDto;
import com.berkayb.soundconnect.modules.collab.entity.Collab;
import com.berkayb.soundconnect.modules.collab.entity.CollabRequiredSlot;
import com.berkayb.soundconnect.modules.collab.enums.CollabRole;
import com.berkayb.soundconnect.modules.collab.mapper.CollabMapper;
import com.berkayb.soundconnect.modules.collab.repository.CollabRepository;
import com.berkayb.soundconnect.modules.collab.spec.CollabSpecifications;
import com.berkayb.soundconnect.modules.collab.ttl.service.CollabTTLService;
import com.berkayb.soundconnect.modules.instrument.entity.Instrument;
import com.berkayb.soundconnect.modules.instrument.support.InstrumentEntityFinder;
import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.location.support.LocationEntityFinder;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.enums.BandMemberShipStatus;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.repository.BandMemberRepository;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.support.UserEntityFinder;
import com.berkayb.soundconnect.modules.venue.repository.VenueRepository;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CollabServiceImpl implements CollabService {
	
	private final CollabRepository collabRepository;
	private final CollabMapper collabMapper;
	private final UserEntityFinder userFinder;
	private final LocationEntityFinder locationEntityFinder;
	private final InstrumentEntityFinder instrumentEntityFinder;
	private final CollabTTLService collabTTLService;
	private final VenueRepository venueRepository;
	private final BandMemberRepository bandMemberRepository;
	
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
		
		// 5) daily validation
		validateDailyFields(dto.daily(), dto.expirationTime());
		
		// 6) required slots üret
		mergeRequiredSlots(collab, dto.requiredSlots());
		
		// 7) save
		collabRepository.save(collab);
		
		// 8) redis TTL
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
		
		// basic alanlar
		collabMapper.updateEntity(collab, dto);
		
		// city güncelle
		City city = locationEntityFinder.getCity(dto.cityId());
		collab.setCity(city);
		
		// slot merge
		mergeRequiredSlots(collab, dto.requiredSlots());
		
		// daily alanı validasyonu
		validateDailyFields(dto.daily(), dto.expirationTime());
		
		collabRepository.save(collab);
		
		// TTL RESET LOGIC
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
	public CollabResponseDto getById(UUID collabId, UUID authenticatedUserId) {
		log.info("[CollabService] Fetching collab {} by user {}", collabId, authenticatedUserId);
		
		Collab collab = collabRepository.findById(collabId)
		                                .orElseThrow(() -> new SoundConnectException(ErrorType.COLLAB_NOT_FOUND));
		
		return collabMapper.toResponseDto(collab, authenticatedUserId);
	}
	
	@Override
	public Page<CollabResponseDto> search(UUID authenticatedUserId,
	                                      CollabFilterRequestDto filter,
	                                      Pageable pageable) {
		log.info("[CollabService] Searching collabs with filter {} (user: {})",
		         filter, authenticatedUserId);
		
		var spec = CollabSpecifications.filter(filter);
		
		return collabRepository.findAll(spec, pageable)
		                       .map(collab -> collabMapper.toResponseDto(collab, authenticatedUserId));
	}
	
	// ---------------- HELPER METHODS ------------------ //
	
	private CollabRole resolveOwnerRole(User user) {
		if (user.getMusicianProfile() != null) {
			return CollabRole.MUSICIAN;
		}
		
		if (bandMemberRepository.existsByUser_IdAndStatus(user.getId(), BandMemberShipStatus.ACTIVE)) {
			return CollabRole.BAND;
		}
		
		if (venueRepository.existsByOwner_Id(user.getId())) {
			return CollabRole.VENUE;
		}
		
		if (user.getProducerProfile() != null) {
			return CollabRole.PRODUCER;
		}
		
		if (user.getOrganizerProfile() != null) {
			return CollabRole.ORGANIZER;
		}
		
		if (user.getStudioProfile() != null) {
			return CollabRole.STUDIO;
		}
		
		throw new SoundConnectException(ErrorType.ROLE_NOT_FOUND);
	}
	
	private void validateDailyFields(boolean daily, LocalDateTime expirationTime) {
		if (daily && expirationTime == null) {
			throw new SoundConnectException(ErrorType.COLLAB_EXPIRATION_REQUIRED);
		}
		// İstersen burada "geçmiş tarih" kontrolü de ekleyebiliriz
	}
	
	/**
	 * Create + Update için ortak slot merge mantığı.
	 * - Aynı enstrüman varsa requiredCount güncellenir, filledCount clamp edilir
	 * - Yeni enstrüman için yeni slot oluşturulur
	 * - DTO'da artık olmayan enstrümanlardaki slotlar silinir (orphanRemoval)
	 */
	private void mergeRequiredSlots(Collab collab, Set<RequiredSlotRequestDto> slotDtos) {
		if (slotDtos == null || slotDtos.isEmpty()) {
			throw new SoundConnectException(ErrorType.COLLAB_SLOT_REQUIRED);
		}
		
		// mevcut slotları map'e çevir
		Map<UUID, CollabRequiredSlot> currentSlotsByInstrumentId =
				collab.getRequiredSlots().stream()
				      .collect(Collectors.toMap(
						      slot -> slot.getInstrument().getId(),
						      Function.identity()
				      ));
		
		Set<UUID> incomingInstrumentIds = new HashSet<>();
		
		// güncel slot listesi (mevcut + yeni)
		Set<CollabRequiredSlot> updatedSlots = new HashSet<>();
		
		for (RequiredSlotRequestDto dto : slotDtos) {
			UUID instrumentId = dto.instrumentId();
			incomingInstrumentIds.add(instrumentId);
			
			Instrument instrument = instrumentEntityFinder.getInstrument(instrumentId);
			CollabRequiredSlot existing = currentSlotsByInstrumentId.get(instrumentId);
			
			if (existing != null) {
				// mevcut slot → sadece requiredCount güncelle
				existing.setRequiredCount(dto.requiredCount());
				// filledCount > requiredCount ise clamp
				if (existing.getFilledCount() > existing.getRequiredCount()) {
					existing.setFilledCount(existing.getRequiredCount());
				}
				updatedSlots.add(existing);
			} else {
				// yeni slot
				CollabRequiredSlot newSlot = CollabRequiredSlot.builder()
				                                               .collab(collab)
				                                               .instrument(instrument)
				                                               .requiredCount(dto.requiredCount())
				                                               .filledCount(0)
				                                               .build();
				updatedSlots.add(newSlot);
			}
		}
		
		// DTO'da olmayan enstrümanların slotlarını kaldır
		collab.getRequiredSlots().removeIf(
				slot -> !incomingInstrumentIds.contains(slot.getInstrument().getId())
		);
		
		// yeni slotları ekle (mevcut olanlar zaten collection içinde)
		collab.getRequiredSlots().addAll(updatedSlots);
	}
}
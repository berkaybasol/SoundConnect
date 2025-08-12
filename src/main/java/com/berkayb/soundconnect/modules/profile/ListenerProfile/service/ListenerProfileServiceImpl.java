package com.berkayb.soundconnect.modules.profile.ListenerProfile.service;


import com.berkayb.soundconnect.modules.profile.ListenerProfile.dto.request.ListenerSaveRequestDto;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.dto.response.ListenerProfileResponseDto;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.entity.ListenerProfile;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.mapper.ListenerProfileMapper;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.repository.ListenerProfileRepository;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.support.UserEntityFinder;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ListenerProfileServiceImpl implements ListenerProfileService {
	private final ListenerProfileRepository listenerProfileRepository;
	private final UserEntityFinder userEntityFinder;
	private final ListenerProfileMapper listenerProfileMapper;
	
	
	@Override
	public ListenerProfileResponseDto createProfile(UUID userId, ListenerSaveRequestDto dto) {
		// kullaniciyi getir.
		User user = userEntityFinder.getUser(userId);
		
		// daha once profil acilmis mi kontrol et
		if (listenerProfileRepository.findByUserId(user.getId()).isPresent()) {
			log.warn("bu profil zaten mevcut userId: {}", userId);
			throw new SoundConnectException(ErrorType.PROFILE_ALREADY_EXISTS);
		}
		// profil olustur
		ListenerProfile profile = ListenerProfile.builder()
				.user(user)
				.description(dto.description())
				.profilePicture(dto.profilePicture())
				.build();
		
		// kaydet ve response dto'ya cevir ve don
		ListenerProfile saved = listenerProfileRepository.save(profile);
		
		log.info("Yeni Dinleyici profili olusturuldu. UserId: {}", userId);
		return listenerProfileMapper.toDto(saved);
	}
	
	
	@Override
	public ListenerProfileResponseDto getProfileByUserId(UUID userId) {
		User user = userEntityFinder.getUser(userId);
		
		ListenerProfile profile = listenerProfileRepository.findByUserId(userId).orElseThrow(() -> {
			log.warn("Profil bulunamadi. UserId: {}", userId);
			return new SoundConnectException(ErrorType.PROFILE_NOT_FOUND);
		});
		log.info("listener profile getirildi UserId: {}", userId);
		return listenerProfileMapper.toDto(profile);
	}
	
	@Override
	public ListenerProfileResponseDto updateProfile(UUID userId, ListenerSaveRequestDto dto)
	{
		User user = userEntityFinder.getUser(userId);
		
		ListenerProfile profile = listenerProfileRepository.findByUserId(userId).orElseThrow(() -> {
			log.warn("Profil bulunamadi. UserId: {}", userId);
			return new SoundConnectException(ErrorType.PROFILE_NOT_FOUND);
		});
		
		// null degilse alanlari guncelle
		if (dto.description() != null) profile.setDescription(dto.description());
		if (dto.profilePicture() != null) profile.setProfilePicture(dto.profilePicture());
		
		// kaydet
		ListenerProfile updated = listenerProfileRepository.save(profile);
		log.info("Yeni Listener profile olusturdu. UserId: {}", userId);
		
		// response dto'ya sarip don
		return listenerProfileMapper.toDto(updated);
		
		
	}
}
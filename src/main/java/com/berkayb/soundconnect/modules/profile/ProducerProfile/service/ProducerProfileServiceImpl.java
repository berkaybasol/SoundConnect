package com.berkayb.soundconnect.modules.profile.ProducerProfile.service;

import com.berkayb.soundconnect.modules.profile.ProducerProfile.dto.request.ProducerProfileSaveRequestDto;
import com.berkayb.soundconnect.modules.profile.ProducerProfile.dto.response.ProducerProfileResponseDto;
import com.berkayb.soundconnect.modules.profile.ProducerProfile.entity.ProducerProfile;
import com.berkayb.soundconnect.modules.profile.ProducerProfile.mapper.ProducerProfileMapper;
import com.berkayb.soundconnect.modules.profile.ProducerProfile.repository.ProducerProfileRepository;
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
public class ProducerProfileServiceImpl implements ProducerProfileService {
	private final ProducerProfileRepository producerProfileRepository;
	private final UserEntityFinder userEntityFinder;
	private final ProducerProfileMapper producerProfileMapper;
	
	
	@Override
	public ProducerProfileResponseDto createProfile(UUID userId, ProducerProfileSaveRequestDto dto) {
		// kullaniciyi getir
		User user = userEntityFinder.getUser(userId);
		
		// daha once porofil acilmis mi
		if (producerProfileRepository.findByUserId(user.getId()).isPresent()) {
			log.warn("kullanici zaten bu profile sahip: {}", userId);
			throw new SoundConnectException(ErrorType.PROFILE_ALREADY_EXISTS);
		}
		
		// profil olustur
		ProducerProfile profile = ProducerProfile.builder()
				.user(user)
				.name(dto.name())
				.profilePicture(dto.profilePicture())
				.address(dto.address())
				.phone(dto.phone())
				.website(dto.website())
				.instagramUrl(dto.instagramUrl())
				.youtubeUrl(dto.youtubeUrl())
				.description(dto.description())
				.build();
		
		// kaydet
		ProducerProfile savedProfile = producerProfileRepository.save(profile);
		log.info("Producer profili olusturuldu. UserId: {}", userId);
		
		return producerProfileMapper.toDto(savedProfile);
	}
	
	
	
	@Override
	public ProducerProfileResponseDto getProfileByUserId(UUID userId) {
		User user = userEntityFinder.getUser(userId);
		
		ProducerProfile producerProfile = producerProfileRepository.findByUserId(userId)
				.orElseThrow(() -> {
					log.warn("Producer profili bulunamadi. UserId: {}", userId);
					return new SoundConnectException(ErrorType.PROFILE_NOT_FOUND);
					});
		log.info("Producer profili getirildi. UserId: {}", userId);
		return producerProfileMapper.toDto(producerProfile);
	}
	
	
	@Override
	public ProducerProfileResponseDto updateProfile(UUID userId, ProducerProfileSaveRequestDto dto) {
		User user = userEntityFinder.getUser(userId);
		
		ProducerProfile profile = producerProfileRepository.findByUserId(userId)
				.orElseThrow(() -> {
					log.warn("Producer profili bulunamadi. UserId: {}", userId);
					return new SoundConnectException(ErrorType.PROFILE_NOT_FOUND);
				});
		
		// guncelle
		if (dto.name() != null) profile.setName(dto.name());
		if (dto.profilePicture() != null) profile.setProfilePicture(dto.profilePicture());
		if (dto.address() != null) profile.setAddress(dto.address());
		if (dto.phone() != null) profile.setPhone(dto.phone());
		if (dto.website() != null) profile.setWebsite(dto.website());
		if (dto.instagramUrl() != null) profile.setInstagramUrl(dto.instagramUrl());
		if (dto.youtubeUrl() != null) profile.setYoutubeUrl(dto.youtubeUrl());
		if (dto.description() != null) profile.setDescription(dto.description());
		
		ProducerProfile updated = producerProfileRepository.save(profile);
		log.info("producer profili guncellendi. UserId: {}", userId);
		return producerProfileMapper.toDto(updated);
	}
	
	// TODO: media, comment, notification...
}
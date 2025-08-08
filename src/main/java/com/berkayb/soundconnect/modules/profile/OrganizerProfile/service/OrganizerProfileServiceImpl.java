package com.berkayb.soundconnect.modules.profile.OrganizerProfile.service;


import com.berkayb.soundconnect.modules.profile.OrganizerProfile.dto.request.OrganizerProfileSaveRequestDto;
import com.berkayb.soundconnect.modules.profile.OrganizerProfile.dto.response.OrganizerProfileResponseDto;
import com.berkayb.soundconnect.modules.profile.OrganizerProfile.entity.OrganizerProfile;
import com.berkayb.soundconnect.modules.profile.OrganizerProfile.mapper.OrganizerProfileMapper;
import com.berkayb.soundconnect.modules.profile.OrganizerProfile.repository.OrganizerProfileRepository;
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
public class OrganizerProfileServiceImpl implements OrganizerProfileService {
	private final OrganizerProfileRepository organizerProfileRepository;
	private final UserEntityFinder userEntityFinder;
	private final OrganizerProfileMapper organizerProfileMapper;
	
	@Override
	public OrganizerProfileResponseDto createProfile(UUID userId, OrganizerProfileSaveRequestDto dto) {
		// kullaniciyi getir
		User user = userEntityFinder.getUser(userId);
		
		// daha once acilmis mi
		if (organizerProfileRepository.findByUserId(user.getId()).isPresent()) {
			log.warn("kullanici zaten bu profile'a sahip. {}", userId);
			throw new SoundConnectException(ErrorType.PROFILE_ALREADY_EXISTS);
		}
		
		// profile olustur
		OrganizerProfile profile = OrganizerProfile.builder()
				.user(user)
				.name(dto.name())
				.description(dto.description())
				.profilePicture(dto.profilePicture())
				.address(dto.address())
				.phone(dto.phone())
				.instagramUrl(dto.instagramUrl())
				.youtubeUrl(dto.youtubeUrl())
				.build();
		
		// kaydet
		OrganizerProfile savedProfile = organizerProfileRepository.save(profile);
		log.info("organizer profile olusturuldu. UserId: {}", userId);
		
		return organizerProfileMapper.toDto(savedProfile);
	}
	
	
	@Override
	public OrganizerProfileResponseDto getProfileByUserId(UUID userId) {
		// kullaniciyi getir
		userEntityFinder.getUser(userId);
		
		// profili bul
		OrganizerProfile organizerProfile = organizerProfileRepository.findByUserId(userId)
				.orElseThrow(() -> {
					log.warn("organizer profili bulunamadi. UserId: {}", userId);
					return new SoundConnectException(ErrorType.PROFILE_NOT_FOUND);
				});
		log.info("organizer profili getirildi, UserId: {}", userId);
		return organizerProfileMapper.toDto(organizerProfile);
	}
	
	@Override
	public OrganizerProfileResponseDto updateProfile(UUID userId, OrganizerProfileSaveRequestDto dto) {
		// kullaniciyi getir
		User user = userEntityFinder.getUser(userId);
		
		// profili bul
		OrganizerProfile organizerProfile = organizerProfileRepository.findByUserId(userId)
		        .orElseThrow(() -> {
			    log.warn("organizer profili bulunamadi. UserId: {}", userId);
			    return new SoundConnectException(ErrorType.PROFILE_NOT_FOUND);});
		
		// guncelle
		if (dto.name() != null) organizerProfile.setName(dto.name());
		if (dto.profilePicture() != null) organizerProfile.setProfilePicture(dto.profilePicture());
		if (dto.description() != null) organizerProfile.setDescription(dto.description());
		if (dto.phone() != null) organizerProfile.setPhone(dto.phone());
		if (dto.address() != null) organizerProfile.setAddress(dto.address());
		if (dto.instagramUrl() != null) organizerProfile.setInstagramUrl(dto.instagramUrl());
		if (dto.youtubeUrl() != null) organizerProfile.setYoutubeUrl(dto.youtubeUrl());
		
		// kaydet
		OrganizerProfile updated = organizerProfileRepository.save(organizerProfile);
		log.info("organizer profile guncellendi. UserId: {}", userId);
		return organizerProfileMapper.toDto(updated);
	}
	//TODO meida, comment, notification
}
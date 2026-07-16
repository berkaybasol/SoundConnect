package com.berkayb.soundconnect.modules.profile.StudioProfile.service;

import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.profile.StudioProfile.dto.response.StudioProfileResponseDto;
import com.berkayb.soundconnect.modules.profile.StudioProfile.dto.request.StudioProfileSaveRequestDto;
import com.berkayb.soundconnect.modules.profile.StudioProfile.entity.StudioProfile;
import com.berkayb.soundconnect.modules.profile.StudioProfile.mapper.StudioProfileMapper;
import com.berkayb.soundconnect.modules.profile.StudioProfile.repository.StudioProfileRepository;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.support.UserEntityFinder;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class StudioProfileServiceImpl implements StudioProfileService {
	private final StudioProfileRepository studioProfileRepository;
	private final UserEntityFinder userEntityFinder;
	private final StudioProfileMapper studioProfileMapper;
	private final MediaAssetService mediaAssetService;
	
	
	@Override
	public StudioProfileResponseDto createProfile(UUID userId, StudioProfileSaveRequestDto dto) {
		// kullaniciyi getir
		User user = userEntityFinder.getUser(userId);
		
		// daha once profil acilmis mi kontrol et
		if (studioProfileRepository.findByUserId(userId).isPresent()) {
			log.warn("kullanici zaten studio profile sahibi. {}", userId);
			throw new SoundConnectException(ErrorType.PROFILE_ALREADY_EXISTS);
		}
		// facilies null gelirse bos set olarak baslatma ve stringe cevirme islemi
		Set<String> facilities = dto.facilities() != null
				? dto.facilities().stream().map(String::valueOf).collect(Collectors.toSet())
				: new HashSet<>();
		
		
		// prfile olustur.
		StudioProfile profile = StudioProfile.builder()
				.user(user)
				.name(dto.name())
				.description(dto.descpriction())
				.profilePictureMediaId(dto.profilePicture())
				.address(dto.adress())
				.phone(dto.phone())
				.website(dto.website())
				.facilities(facilities)
				.instagramUrl(dto.instagramUrl())
				.youtubeUrl(dto.youtubeUrl())
				.build();
		
		// kaydet
		StudioProfile savedProfile = studioProfileRepository.save(profile);
		log.info("studio profili olusturuldu. UserId: {}", userId);
		
		return toResponseDto(savedProfile);
	}
	
	@Override
	public StudioProfileResponseDto getProfileByUserId(UUID userId) {
		// kullaniciyi getir
		userEntityFinder.getUser(userId);
		
		// profili bul
		StudioProfile studioProfile = studioProfileRepository.findByUserId(userId)
				.orElseThrow(() -> {
					log.warn("Studio proofili bulunamadi. UserId: {}", userId);
					return new SoundConnectException(ErrorType.PROFILE_NOT_FOUND);
				});
		log.info("Studio profili getirildi. UserId: {}", userId);
		
		return toResponseDto(studioProfile);
	}

	@Override
	public StudioProfileResponseDto getProfileByProfileId(UUID profileId) {
		StudioProfile studioProfile = studioProfileRepository.findById(profileId)
				.orElseThrow(() -> {
					log.warn("Studio profili bulunamadi. ProfileId: {}", profileId);
					return new SoundConnectException(ErrorType.PROFILE_NOT_FOUND);
				});
		return toResponseDto(studioProfile);
	}

	@Override
	public StudioProfileResponseDto updateProfile(UUID userId, StudioProfileSaveRequestDto dto) {
		// kullaniciyi getir
		userEntityFinder.getUser(userId);

		// profili bul
		StudioProfile profile = studioProfileRepository.findByUserId(userId)
		                                               .orElseThrow(() -> {
			                                               log.warn("Studio profili bulunamadı. UserId: {}", userId);
			                                               return new SoundConnectException(ErrorType.PROFILE_NOT_FOUND);
		                                               });

		// guncelle
		if (dto.name() != null) profile.setName(dto.name());
		if (dto.descpriction() != null) profile.setDescription(dto.descpriction());
		if (dto.profilePicture() != null) profile.setProfilePictureMediaId(dto.profilePicture());
		if (dto.adress() != null) profile.setAddress(dto.adress());
		if (dto.phone() != null) profile.setPhone(dto.phone());
		if (dto.website() != null) profile.setWebsite(dto.website());
		if (dto.facilities() != null) profile.setFacilities(new HashSet<>(dto.facilities()));
		if (dto.instagramUrl() != null) profile.setInstagramUrl(dto.instagramUrl());
		if (dto.youtubeUrl() != null) profile.setYoutubeUrl(dto.youtubeUrl());
		
		// kaydet
		StudioProfile updated = studioProfileRepository.save(profile);
		log.info("studio profili guncellendi. UserId: {}", userId);
		return toResponseDto(updated);
	}
	// TODO media, comment, notification

	private StudioProfileResponseDto toResponseDto(StudioProfile profile) {
		StudioProfileResponseDto dto = studioProfileMapper.toDto(profile);
		return new StudioProfileResponseDto(
				dto.id(),
				dto.userId(),
				dto.name(),
				dto.description(),
				dto.profilePictureMediaId(),
				resolveProfilePictureUrl(dto.profilePictureMediaId()),
				dto.adress(),
				dto.phone(),
				dto.website(),
				dto.facilities(),
				dto.instagramUrl(),
				dto.youtubeUrl()
		);
	}

	private String resolveProfilePictureUrl(UUID mediaAssetId) {
		if (mediaAssetId == null) {
			return null;
		}
		try {
			return mediaAssetService.getPlaybackUrl(mediaAssetId);
		} catch (Exception e) {
			log.warn("Studio profile picture resolve failed. mediaAssetId={}", mediaAssetId);
			return null;
		}
	}
}

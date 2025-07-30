package com.berkayb.soundconnect.modules.profile.service;

import com.berkayb.soundconnect.modules.instrument.entity.Instrument;
import com.berkayb.soundconnect.modules.instrument.repository.InstrumentRepository;
import com.berkayb.soundconnect.modules.profile.dto.request.MusicianProfileSaveRequestDto;
import com.berkayb.soundconnect.modules.profile.dto.response.MusicianProfileResponseDto;
import com.berkayb.soundconnect.modules.profile.entity.MusicianProfile;
import com.berkayb.soundconnect.modules.profile.mapper.MusicianProfileMapper;
import com.berkayb.soundconnect.modules.profile.repository.MusicianProfileRepository;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.support.UserEntityFinder;
import com.berkayb.soundconnect.modules.venue.entity.Venue;
import com.berkayb.soundconnect.modules.venue.repository.VenueRepository;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class MusicianProfileServiceImpl implements MusicianProfileService {
	private final MusicianProfileRepository musicianProfileRepository;
	private final UserEntityFinder userEntityFinder;
	private final InstrumentRepository instrumentRepository;
	private final MusicianProfileMapper musicianProfileMapper;
	
	
	@Override
	public MusicianProfileResponseDto createProfile(UUID userId, MusicianProfileSaveRequestDto dto) {
		// kullaniciyi getir
		User user = userEntityFinder.getUser(userId);
		
		// daha once profil acilmis mi kontrol et
		if (musicianProfileRepository.findByUserId(userId).isPresent()) {
			log.warn("Kullanici zaten bir profile sahip: {}",userId);
			throw new SoundConnectException(ErrorType.PROFILE_ALREADY_EXISTS);
		}
		
		// enstrumanlari cek
		/*
		ternary operatore alismaya calisiyorum :D
		kosul dogruysa ? koyup yapmak istedigimiz islemi yaziyoruz
		yanlis ise : koyup yapmak istedigimiz islemi yaziyoruz :D
		 */
		Set<Instrument> instruments = dto.instrumentIds() != null && !dto.instrumentIds().isEmpty()
				? new HashSet<>(instrumentRepository.findAllById(dto.instrumentIds()))
				: new HashSet<>(); // kosul yanlissa bos bir hashset olustur
		
		// profil olustur
		MusicianProfile profile = MusicianProfile.builder()
				.user(user)
				.stageName(dto.stageName())
				.bio(dto.bio())
				.profilePicture(dto.profilePicture())
				.instagramUrl(dto.instagramUrl())
				.youtubeUrl(dto.youtubeUrl())
				.soundcloudUrl(dto.soundcloudUrl())
				.spotifyEmbedUrl(dto.spotifyEmbedUrl())
				.instruments(instruments)
				.build();
		
		// kaydet ve response dto'ya cevir ve don
		MusicianProfile saved = musicianProfileRepository.save(profile);
		
		log.info("Yeni muzisyen profili olusturuldu. UserId: {}",userId);
		return musicianProfileMapper.toDto(saved);
	}
	
	@Override
	public MusicianProfileResponseDto getProfileByUserId(UUID userId) {
		User user = userEntityFinder.getUser(userId);
		
		MusicianProfile profile = musicianProfileRepository.findByUserId(userId).
				orElseThrow(() ->{
					log.warn("Profil bulunamadi. UserId: {}",userId);
					return new SoundConnectException(ErrorType.PROFILE_NOT_FOUND);
		});
		log.info("Musician profile getirildi. UserId: {}",userId);
		return musicianProfileMapper.toDto(profile);
	}
	
	@Override
	public MusicianProfileResponseDto updateProfile(UUID userId, MusicianProfileSaveRequestDto dto) {
		// kullaniciyi getir
		User user = userEntityFinder.getUser(userId);
		
		// profil var mi kontrol et
		MusicianProfile profile = musicianProfileRepository.findByUserId(userId)
				.orElseThrow(() -> {
					log.warn("Profil bulunamadi. UserId: {}",userId);
					return new SoundConnectException(ErrorType.PROFILE_NOT_FOUND);
				});
		// null degilse alanlari guncelle
		if (dto.stageName() != null) profile.setStageName(dto.stageName());
		if (dto.bio() != null) profile.setBio(dto.bio());
		if (dto.profilePicture() != null) profile.setProfilePicture(dto.profilePicture());
		if (dto.instagramUrl() != null) profile.setInstagramUrl(dto.instagramUrl());
		if (dto.soundcloudUrl() != null) profile.setSoundcloudUrl(dto.soundcloudUrl());
	    if (dto.spotifyEmbedUrl() != null) profile.setSpotifyEmbedUrl(dto.spotifyEmbedUrl());
		
		// instruments guncellemesi (null degilse)
		if (dto.instrumentIds() != null) {
			Set<Instrument> instruments = new HashSet<>(instrumentRepository.findAllById(dto.instrumentIds()));
			profile.setInstruments(instruments);
		}
		
		// kaydet
		MusicianProfile updated = musicianProfileRepository.save(profile);
		
		log.info("Musician profile güncellendi. UserId: {}", userId);
		
		// response dto'ya sarip don
		return musicianProfileMapper.toDto(updated);
		
	}
}
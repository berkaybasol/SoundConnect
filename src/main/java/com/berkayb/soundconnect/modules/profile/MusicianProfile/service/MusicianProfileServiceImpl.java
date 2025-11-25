package com.berkayb.soundconnect.modules.profile.MusicianProfile.service;

import com.berkayb.soundconnect.modules.instrument.entity.Instrument;
import com.berkayb.soundconnect.modules.instrument.repository.InstrumentRepository;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.service.BandService;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.dto.request.MusicianProfileSaveRequestDto;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.dto.response.MusicianProfileResponseDto;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.entity.MusicianProfile;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.mapper.MusicianProfileMapper;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.repository.MusicianProfileRepository;
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

@Service
@RequiredArgsConstructor
@Slf4j
public class MusicianProfileServiceImpl implements MusicianProfileService {
	
	private final MusicianProfileRepository musicianProfileRepository;
	private final UserEntityFinder userEntityFinder;
	private final InstrumentRepository instrumentRepository;
	private final MusicianProfileMapper musicianProfileMapper;
	private final BandService bandService;
	
	@Override
	public MusicianProfile getProfileEntity(UUID profileId) {
		return musicianProfileRepository.findById(profileId)
		                                .orElseThrow(() -> new SoundConnectException(ErrorType.PROFILE_NOT_FOUND));
	}
	
	
	@Override
	public MusicianProfileResponseDto createProfile(UUID userId, MusicianProfileSaveRequestDto dto) {
		
		User user = userEntityFinder.getUser(userId);
		
		if (musicianProfileRepository.findByUserId(userId).isPresent()) {
			log.warn("Kullanici zaten bir profile sahip: {}", userId);
			throw new SoundConnectException(ErrorType.PROFILE_ALREADY_EXISTS);
		}
		
		Set<Instrument> instruments =
				dto.instrumentIds() != null && !dto.instrumentIds().isEmpty()
						? new HashSet<>(instrumentRepository.findAllById(dto.instrumentIds()))
						: new HashSet<>();
		
		MusicianProfile profile = MusicianProfile.builder()
		                                         .user(user)
		                                         .stageName(dto.stageName())
		                                         .description(dto.description())
		                                         .profilePicture(dto.profilePicture())
		                                         .instagramUrl(dto.instagramUrl())
		                                         .youtubeUrl(dto.youtubeUrl())
		                                         .soundcloudUrl(dto.soundcloudUrl())
		                                         .spotifyEmbedUrl(dto.spotifyEmbedUrl())
		                                         .instruments(instruments)
												 .spotifyArtistId(dto.spotifyArtistId())
		                                         .build();
		
		MusicianProfile saved = musicianProfileRepository.save(profile);
		
		log.info("Yeni muzisyen profili olusturuldu. UserId: {}", userId);
		
		// ---- PROFILE + BANDS ----
		var base = musicianProfileMapper.toDto(saved);
		var bands = new HashSet<>(bandService.getBandsByUser(userId));
		
		return new MusicianProfileResponseDto(
				base.id(),
				base.stageName(),
				base.bio(),
				base.profilePicture(),
				base.instagramUrl(),
				base.youtubeUrl(),
				base.soundcloudUrl(),
				base.spotifyEmbedUrl(),
				base.spotifyArtistId(),
				base.instruments(),
				base.activeVenues(),
				bands
		);
	}
	
	
	@Override
	public MusicianProfileResponseDto getProfileByUserId(UUID userId) {
		
		userEntityFinder.getUser(userId);
		
		MusicianProfile profile = musicianProfileRepository.findByUserId(userId)
		                                                   .orElseThrow(() -> {
			                                                   log.warn("Profil bulunamadi. UserId: {}", userId);
			                                                   return new SoundConnectException(ErrorType.PROFILE_NOT_FOUND);
		                                                   });
		
		log.info("Musician profile getirildi. UserId: {}", userId);
		
		var base = musicianProfileMapper.toDto(profile);
		var bands = new HashSet<>(bandService.getBandsByUser(userId));
		
		return new MusicianProfileResponseDto(
				base.id(),
				base.stageName(),
				base.bio(),
				base.profilePicture(),
				base.instagramUrl(),
				base.youtubeUrl(),
				base.soundcloudUrl(),
				base.spotifyEmbedUrl(),
				base.spotifyArtistId(),
				base.instruments(),
				base.activeVenues(),
				bands
		);
	}
	
	@Override
	public MusicianProfileResponseDto updateProfile(UUID userId, MusicianProfileSaveRequestDto dto) {
		
		userEntityFinder.getUser(userId);
		
		MusicianProfile profile = musicianProfileRepository.findByUserId(userId)
		                                                   .orElseThrow(() -> {
			                                                   log.warn("Profil bulunamadi. UserId: {}", userId);
			                                                   return new SoundConnectException(ErrorType.PROFILE_NOT_FOUND);
		                                                   });
		
		if (dto.stageName() != null) profile.setStageName(dto.stageName());
		if (dto.description() != null) profile.setDescription(dto.description());
		if (dto.profilePicture() != null) profile.setProfilePicture(dto.profilePicture());
		if (dto.instagramUrl() != null) profile.setInstagramUrl(dto.instagramUrl());
		if (dto.youtubeUrl() != null) profile.setYoutubeUrl(dto.youtubeUrl());
		if (dto.soundcloudUrl() != null) profile.setSoundcloudUrl(dto.soundcloudUrl());
		if (dto.spotifyEmbedUrl() != null) profile.setSpotifyEmbedUrl(dto.spotifyEmbedUrl());
		if (dto.spotifyArtistId() != null) profile.setSpotifyArtistId(dto.spotifyArtistId());
		
		if (dto.instrumentIds() != null) {
			Set<Instrument> instruments = new HashSet<>(instrumentRepository.findAllById(dto.instrumentIds()));
			profile.setInstruments(instruments);
		}
		
		MusicianProfile updated = musicianProfileRepository.save(profile);
		
		log.info("Musician profile güncellendi. UserId: {}", userId);
		
		var base = musicianProfileMapper.toDto(updated);
		var bands = new HashSet<>(bandService.getBandsByUser(userId));
		
		return new MusicianProfileResponseDto(
				base.id(),
				base.stageName(),
				base.bio(),
				base.profilePicture(),
				base.instagramUrl(),
				base.youtubeUrl(),
				base.soundcloudUrl(),
				base.spotifyEmbedUrl(),
				base.spotifyArtistId(),
				base.instruments(),
				base.activeVenues(),
				bands
		);
	}
}
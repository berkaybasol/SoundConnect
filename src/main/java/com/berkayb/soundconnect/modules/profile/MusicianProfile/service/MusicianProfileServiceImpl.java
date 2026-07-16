package com.berkayb.soundconnect.modules.profile.MusicianProfile.service;

import com.berkayb.soundconnect.modules.instrument.entity.Instrument;
import com.berkayb.soundconnect.modules.instrument.repository.InstrumentRepository;
import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.media.enums.MediaKind;
import com.berkayb.soundconnect.modules.media.enums.MediaOwnerType;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.service.BandService;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.dto.response.MusicianProfileActiveVenueDto;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.dto.request.MusicianProfileSaveRequestDto;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.dto.response.MusicianProfileResponseDto;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.dto.response.MusicianProfileSearchItemDto;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.entity.MusicianProfile;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.mapper.MusicianProfileMapper;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.repository.MusicianProfileRepository;
import com.berkayb.soundconnect.modules.profile.VenueProfile.repository.VenueProfileRepository;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.support.UserEntityFinder;
import com.berkayb.soundconnect.modules.venue.entity.Venue;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
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
	private final MediaAssetService mediaAssetService;
	private final VenueProfileRepository venueProfileRepository;
	
	@Override
	public List<MusicianProfileSearchItemDto> searchProfiles(String query) { //eklendi
		String q = query == null ? "" : query.trim(); //eklendi
		if (q.isEmpty()) return List.of(); //eklendi
		
		return musicianProfileRepository.searchByStageNameOrUsername(q) //eklendi
		                                .stream() //eklendi
		                                .limit(10) //eklendi
		                                .map(profile -> new MusicianProfileSearchItemDto( //eklendi
		                                                                                  profile.getId(), //eklendi
		                                                                                  profile.getUser().getId(), //eklendi
		                                                                                  profile.getUser().getUsername(), //eklendi
		                                                                                  profile.getStageName(), //eklendi
		                                                                                  resolveProfilePictureUrl(profile.getProfilePictureMediaId()) //eklendi
		                                )) //eklendi
		                                .toList(); //eklendi
	}
	
	@Override
	public MusicianProfile getProfileEntity(UUID profileId) {
		return musicianProfileRepository.findById(profileId)
		                                .orElseThrow(() -> new SoundConnectException(ErrorType.PROFILE_NOT_FOUND));
	}
	
	@Override
	@Transactional
	public MusicianProfileResponseDto createProfile(UUID userId, MusicianProfileSaveRequestDto dto) {
		User user = userEntityFinder.getUser(userId);
		
		if (musicianProfileRepository.findByUserId(userId).isPresent()) {
			log.warn("Kullanici zaten bir profile sahip: {}", userId);
			throw new SoundConnectException(ErrorType.PROFILE_ALREADY_EXISTS);
		}
		if (dto.profilePicture() != null) {
			mediaAssetService.validateAssignableMedia(
					userId, dto.profilePicture(), MediaOwnerType.USER, userId, MediaKind.IMAGE
			);
		}
		
		Set<Instrument> instruments =
				dto.instrumentIds() != null && !dto.instrumentIds().isEmpty()
						? new HashSet<>(instrumentRepository.findAllById(dto.instrumentIds()))
						: new HashSet<>();
		
		MusicianProfile profile = MusicianProfile.builder()
		                                         .user(user)
		                                         .stageName(dto.stageName())
		                                         .description(dto.description())
		                                         .profilePictureMediaId(dto.profilePicture())
		                                         .instagramUrl(dto.instagramUrl())
		                                         .youtubeUrl(dto.youtubeUrl())
		                                         .soundcloudUrl(dto.soundcloudUrl())
		                                         .spotifyEmbedUrl(dto.spotifyEmbedUrl())
		                                         .instruments(instruments)
		                                         .spotifyArtistId(dto.spotifyArtistId())
		                                         .spotifyTracks(dto.spotifyTracks() != null ? dto.spotifyTracks() : List.of())
		                                         .build();
		
		MusicianProfile saved = musicianProfileRepository.save(profile);
		
		log.info("Yeni muzisyen profili olusturuldu. UserId: {}", userId);
		
		var base = musicianProfileMapper.toDto(saved);
		String profilePictureUrl = resolveProfilePictureUrl(saved.getProfilePictureMediaId());
		var bands = new HashSet<>(bandService.getBandsByUser(userId));
		
		return new MusicianProfileResponseDto(
				base.id(),
				saved.getUser().getId(),
				saved.getUser().getUsername(),
				base.stageName(),
				base.bio(),
				base.profilePictureMediaId(),
				profilePictureUrl,
				base.instagramUrl(),
				base.youtubeUrl(),
				base.soundcloudUrl(),
				base.spotifyEmbedUrl(),
				base.spotifyArtistId(),
				base.instruments(),
				base.activeVenues(),
				activeVenueConnections(saved),
				bands,
				base.spotifyTrackIds(),
				base.spotifyTracks()
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
		String profilePictureUrl = resolveProfilePictureUrl(profile.getProfilePictureMediaId());
		var bands = new HashSet<>(bandService.getBandsByUser(userId));
		
		return new MusicianProfileResponseDto(
				base.id(),
				profile.getUser().getId(),
				profile.getUser().getUsername(),
				base.stageName(),
				base.bio(),
				base.profilePictureMediaId(),
				profilePictureUrl,
				base.instagramUrl(),
				base.youtubeUrl(),
				base.soundcloudUrl(),
				base.spotifyEmbedUrl(),
				base.spotifyArtistId(),
				base.instruments(),
				base.activeVenues(),
				activeVenueConnections(profile),
				bands,
				base.spotifyTrackIds(),
				base.spotifyTracks()
		);
	}
	
	@Override
	@Transactional
	public MusicianProfileResponseDto updateProfile(UUID userId, MusicianProfileSaveRequestDto dto) {
		userEntityFinder.getUser(userId);
		
		MusicianProfile profile = musicianProfileRepository.findByUserId(userId)
		                                                   .orElseThrow(() -> {
			                                                   log.warn("Profil bulunamadi. UserId: {}", userId);
			                                                   return new SoundConnectException(ErrorType.PROFILE_NOT_FOUND);
		                                                   });
		
		if (dto.stageName() != null) profile.setStageName(dto.stageName());
		if (dto.description() != null) profile.setDescription(dto.description());
		if (dto.profilePicture() != null) {
			mediaAssetService.validateAssignableMedia(
					userId, dto.profilePicture(), MediaOwnerType.MUSICIAN_PROFILE, profile.getId(), MediaKind.IMAGE
			);
			profile.setProfilePictureMediaId(dto.profilePicture());
		}
		if (dto.instagramUrl() != null) profile.setInstagramUrl(dto.instagramUrl());
		if (dto.youtubeUrl() != null) profile.setYoutubeUrl(dto.youtubeUrl());
		if (dto.soundcloudUrl() != null) profile.setSoundcloudUrl(dto.soundcloudUrl());
		if (dto.spotifyEmbedUrl() != null) profile.setSpotifyEmbedUrl(dto.spotifyEmbedUrl());
		if (dto.spotifyArtistId() != null) profile.setSpotifyArtistId(dto.spotifyArtistId());
		if (dto.spotifyTrackIds() != null) profile.setSpotifyTrackIds(dto.spotifyTrackIds());
		if (dto.spotifyTracks() != null) profile.setSpotifyTracks(dto.spotifyTracks());
		
		if (dto.instrumentIds() != null) {
			Set<Instrument> instruments = new HashSet<>(instrumentRepository.findAllById(dto.instrumentIds()));
			profile.setInstruments(instruments);
		}
		
		MusicianProfile updated = musicianProfileRepository.save(profile);
		
		log.info("Musician profile güncellendi. UserId: {}", userId);
		
		var base = musicianProfileMapper.toDto(updated);
		String profilePictureUrl = resolveProfilePictureUrl(updated.getProfilePictureMediaId());
		var bands = new HashSet<>(bandService.getBandsByUser(userId));
		
		return new MusicianProfileResponseDto(
				base.id(),
				updated.getUser().getId(),
				updated.getUser().getUsername(),
				base.stageName(),
				base.bio(),
				base.profilePictureMediaId(),
				profilePictureUrl,
				base.instagramUrl(),
				base.youtubeUrl(),
				base.soundcloudUrl(),
				base.spotifyEmbedUrl(),
				base.spotifyArtistId(),
				base.instruments(),
				base.activeVenues(),
				activeVenueConnections(updated),
				bands,
				base.spotifyTrackIds(),
				base.spotifyTracks()
		);
	}
	
	@Override
	public MusicianProfileResponseDto getProfileByProfileId(UUID profileId) { //degisti
		MusicianProfile profile = musicianProfileRepository.findById(profileId) //degisti
		                                                   .orElseThrow(() -> { //degisti
			                                                   log.warn("Profil bulunamadi. profileId: {}", profileId); //degisti
			                                                   return new SoundConnectException(ErrorType.PROFILE_NOT_FOUND); //degisti
		                                                   }); //degisti
		
		var base = musicianProfileMapper.toDto(profile); //degisti
		String profilePictureUrl = resolveProfilePictureUrl(profile.getProfilePictureMediaId()); //degisti
		var bands = new HashSet<>(bandService.getBandsByUser(profile.getUser().getId())); //degisti
		
		return new MusicianProfileResponseDto( //degisti
		                                       base.id(), //degisti
		                                       profile.getUser().getId(), //degisti
		                                       profile.getUser().getUsername(), //degisti
		                                       base.stageName(), //degisti
		                                       base.bio(), //degisti
		                                       base.profilePictureMediaId(), //degisti
		                                       profilePictureUrl, //degisti
		                                       base.instagramUrl(), //degisti
		                                       base.youtubeUrl(), //degisti
		                                       base.soundcloudUrl(), //degisti
		                                       base.spotifyEmbedUrl(), //degisti
		                                       base.spotifyArtistId(), //degisti
		                                       base.instruments(), //degisti
		                                       base.activeVenues(), //degisti
		                                       activeVenueConnections(profile), //degisti
		                                       bands, //degisti
		                                       base.spotifyTrackIds(), //degisti
		                                       base.spotifyTracks() //degisti
		); //degisti
	}
	
	
	private List<MusicianProfileActiveVenueDto> activeVenueConnections(MusicianProfile profile) {
		if (profile == null || profile.getActiveVenues() == null || profile.getActiveVenues().isEmpty()) {
			return List.of();
		}
		return profile.getActiveVenues().stream()
		              .map(venue -> new MusicianProfileActiveVenueDto(
				              venue.getId(),
				              venue.getName(),
				              resolveVenueProfilePictureUrl(venue)
		              ))
		              .toList();
	}

	private String resolveVenueProfilePictureUrl(Venue venue) {
		if (venue == null || venue.getId() == null) return null;
		return venueProfileRepository.findByVenueId(venue.getId())
		                             .map(profile -> resolveProfilePictureUrl(profile.getProfilePictureMediaId()))
		                             .orElse(null);
	}

	private String resolveProfilePictureUrl(UUID mediaAssetId) {
		if (mediaAssetId == null) return null;
		try {
			return mediaAssetService.getDisplayUrl(mediaAssetId);
		} catch (Exception e) {
			log.warn("Profile picture resolve failed. mediaAssetId={}", mediaAssetId);
			return null;
		}
	}
}

package com.berkayb.soundconnect.modules.track.service;

import com.berkayb.soundconnect.modules.media.enums.MediaKind;
import com.berkayb.soundconnect.modules.media.enums.MediaOwnerType;
import com.berkayb.soundconnect.modules.media.enums.MediaStatus;
import com.berkayb.soundconnect.modules.media.enums.MediaVisibility;
import com.berkayb.soundconnect.modules.media.repository.MediaAssetRepository;
import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.service.BandService;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.enums.BandMemberShipStatus;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.enums.BandRole;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.service.MusicianProfileService;
import com.berkayb.soundconnect.modules.track.dto.request.TrackCreateRequestDto;
import com.berkayb.soundconnect.modules.track.dto.response.TrackResponseDto;
import com.berkayb.soundconnect.modules.track.entity.Track;
import com.berkayb.soundconnect.modules.track.enums.TrackOwnerType;
import com.berkayb.soundconnect.modules.track.mapper.TrackMapper;
import com.berkayb.soundconnect.modules.track.repository.TrackRepository;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class TrackServiceImpl implements TrackService {
	
	@Override
	public List<TrackResponseDto> getTracksByOwner(UUID ownerId, TrackOwnerType ownerType) {
		List<Track> tracks = trackRepository.findAllByOwnerIdAndOwnerType(ownerId, ownerType);
		List<UUID> mediaIds = tracks.stream().map(Track::getMediaAssetId).toList();
		Map<UUID, String> playbackMap = mediaAssetService.getPlaybackUrlMap(mediaIds);
		
		
		return tracks.stream()
		             .map(track -> toDto(track, playbackMap))
		             .toList();
	}
	
	@Override
	@Transactional(readOnly = true)
	public Page<TrackResponseDto> listTracks(UUID ownerId, TrackOwnerType ownerType, Pageable pageable) {
		
		log.info("[Track] Listing tracks for ownerId={} ownerType={}", ownerId, ownerType);
		
		Page<Track> page = trackRepository.findByOwnerIdAndOwnerType(ownerId, ownerType, pageable);
		List<UUID> mediaIds = page.getContent().stream().map(Track::getMediaAssetId).toList();
		Map<UUID, String> playbackMap = mediaAssetService.getPlaybackUrlMap(mediaIds);
		
		return page.map(track -> trackMapper.toDto(track, mediaAssetService));
	}
	
	
	@Override
	@Transactional
	public void deleteTrack(UUID trackId, UUID ownerId, UUID userId, TrackOwnerType ownerType) {
		
		Track track = getTrackEntity(trackId);
		
		if (!track.getOwnerId().equals(ownerId) || track.getOwnerType() != ownerType) {
			log.warn("[Track] Delete forbidden. trackId={} incorrect ownerId={}", trackId, ownerId);
			throw new SoundConnectException(ErrorType.TRACK_OWNER_INVALID);
		}
		
		// user yetkili mi?
		validateOwner(ownerId, userId);
		
		trackRepository.delete(track);
		
		log.info("[Track] Deleted trackId={} by userId={}", trackId, userId);
	}
	
	
	private final TrackRepository trackRepository;
	private final TrackMapper trackMapper;
	private final MediaAssetService mediaAssetService;
	private final MusicianProfileService musicianProfileService;
	private final BandService bandService;
	private final MediaAssetRepository mediaAssetRepository;
	
	@Override
	@Transactional
	public TrackResponseDto createTrack(UUID ownerId, UUID userId, TrackCreateRequestDto dto) {
		
		if (userId == null) {
			throw new SoundConnectException(ErrorType.USER_NOT_FOUND);
		}
		TrackOwnerType ownerType = resolveOwnerType(ownerId, userId);
		validateOwner(ownerId, userId);
		
		var asset = mediaAssetRepository.findByIdForUpdate(dto.mediaAssetId())
				.orElseThrow(() -> new SoundConnectException(ErrorType.MEDIA_ASSET_NOT_FOUND));
		MediaOwnerType expectedMediaOwnerType = ownerType == TrackOwnerType.BAND
				? MediaOwnerType.BAND
				: MediaOwnerType.MUSICIAN_PROFILE;
		if (asset.getOwnerType() != expectedMediaOwnerType || !ownerId.equals(asset.getOwnerId())) {
			throw new SoundConnectException(ErrorType.MEDIA_ASSET_OWNER_MISMATCH);
		}

		Track existing = trackRepository
				.findByOwnerTypeAndOwnerIdAndMediaAssetId(ownerType, ownerId, dto.mediaAssetId())
				.orElse(null);
		if (existing != null) {
			log.info("[Track] Idempotent replay. ownerId={}, ownerType={}, trackId={}",
					ownerId, ownerType, existing.getId());
			return trackMapper.toDto(existing, mediaAssetService);
		}

		if (asset.getKind() != MediaKind.AUDIO) {
			throw new SoundConnectException(ErrorType.MEDIA_KIND_INVALID);
		}
		
		if (asset.getStatus() != MediaStatus.READY) {
			throw new SoundConnectException(ErrorType.MEDIA_ASSET_NOT_READY);
		}
		if (asset.getVisibility() != MediaVisibility.PUBLIC) {
			throw new SoundConnectException(ErrorType.MEDIA_ASSET_NOT_PUBLIC);
		}
		
		// 3) Track oluştur
		Track track = Track.builder()
		                   .title(dto.title())
		                   .mediaAssetId(dto.mediaAssetId())
		                   .ownerId(ownerId)
		                   .ownerType(ownerType)
		                   .durationSeconds(dto.durationSeconds())
		                   .bpm(dto.bpm())
		                   .build();
		
		trackRepository.save(track);
		
		log.info("[Track] Yeni track oluşturuldu. ownerId={}, ownerType={}, trackId={}",
		         ownerId, ownerType, track.getId());
		
		return trackMapper.toDto(track, mediaAssetService);
	}
	
	@Override
	@Transactional(readOnly = true)
	public TrackResponseDto getTrackById(UUID trackId) {
		Track track = getTrackEntity(trackId);
		return trackMapper.toDto(track, mediaAssetService);
	}
	
	@Override
	@Transactional(readOnly = true)
	public Track getTrackEntity(UUID trackId) {
		return trackRepository.findById(trackId)
		                      .orElseThrow(() -> {
			                      log.warn("[Track] Track bulunamadı: {}", trackId);
			                      return new SoundConnectException(ErrorType.TRACK_NOT_FOUND);
		                      });
	}
	
	
	
	/**
	 * Owner'ın tipi nedir?
	 * - MusicianProfile mı?
	 * - Band mı?
	 *
	 * Bu metod SADECE ownerType belirler.
	 * Kullanıcının bu owner üzerinde yetkili olup olmadığını kontrol etmez.
	 */
	private TrackOwnerType resolveOwnerType(UUID ownerId, UUID userId) {
		
		boolean isMusicianProfile = false;
		boolean isBand = false;
		
		// MusicianProfile var mı?
		try {
			musicianProfileService.getProfileEntity(ownerId);
			isMusicianProfile = true;
		} catch (SoundConnectException exception) {
			if (exception.getErrorType() != ErrorType.PROFILE_NOT_FOUND) throw exception;
		}
		
		// Band var mı?
		try {
			bandService.getBandEntity(ownerId);
			isBand = true;
		} catch (SoundConnectException exception) {
			if (exception.getErrorType() != ErrorType.BAND_NOT_FOUND) throw exception;
		}
		
		// Aynı ID hem band hem musician profili olamaz → veri bozukluğu
		if (isMusicianProfile && isBand) {
			log.error("[Track] Owner hem musician hem band olamaz ownerId={}", ownerId);
			throw new SoundConnectException(ErrorType.TRACK_OWNER_INVALID);
		}
		
		if (isMusicianProfile) {
			return TrackOwnerType.MUSICIAN_PROFILE;
		}
		
		if (isBand) {
			return TrackOwnerType.BAND;
		}
		
		// Hiçbir kategoriye uymuyorsa → geçersiz owner
		log.warn("[Track] Owner bulunamadı ownerId={}", ownerId);
		throw new SoundConnectException(ErrorType.TRACK_OWNER_INVALID);
	}
	
	private void validateOwner(UUID ownerId, UUID userId) {
		
		// 1) Musician Profile check
		try {
			var profile = musicianProfileService.getProfileEntity(ownerId);
			if (profile.getUser().getId().equals(userId)) {
				return; // işlem yapmaya yetkili
			} else {
				log.warn("[Track] Kullanıcı musician profil sahibi değil. userId={} ownerId={}", userId, ownerId);
				throw new SoundConnectException(ErrorType.TRACK_OWNER_INVALID);
			}
		} catch (SoundConnectException exception) {
			if (exception.getErrorType() != ErrorType.PROFILE_NOT_FOUND) throw exception;
			// musician değil → band olup olmadığına bakacağız
		}
		
		// 2) Band check
		try {
			var band = bandService.getBandEntity(ownerId);
			var isActiveMember = band.getMembers().stream()
			                         .anyMatch(m ->
					                                   m.getUser() != null
							                                   && m.getUser().getId().equals(userId)
							                                   && m.getStatus() == BandMemberShipStatus.ACTIVE
							                                   && (m.getBandRole() == BandRole.FOUNDER
							                                   || m.getBandRole() == BandRole.MANAGER)
			                         );
			
			if (!isActiveMember) {
				log.warn("[Track] Band üyesi değil. userId={} bandId={}", userId, ownerId);
				throw new SoundConnectException(ErrorType.BAND_MEMBER_NOT_FOUND);
			}
			
			return; // işlem yapmaya yetkili
			
		} catch (SoundConnectException exception) {
			if (exception.getErrorType() != ErrorType.BAND_NOT_FOUND) throw exception;
			// ne musician ne de band → invalid
		}
		
		log.warn("[Track] Owner doğrulaması başarısız. userId={} ownerId={}", userId, ownerId);
		throw new SoundConnectException(ErrorType.TRACK_OWNER_INVALID);
	}
	
	
	//helper
	private TrackResponseDto toDto(Track track, Map<UUID, String> playbackUrls) {
		return new TrackResponseDto(
				track.getId(),
				track.getMediaAssetId(),
				track.getTitle(),
				playbackUrls.get(track.getMediaAssetId()),
				track.getDurationSeconds(),
				track.getBpm()
		);
	}
}

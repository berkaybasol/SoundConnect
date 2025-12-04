package com.berkayb.soundconnect.modules.track.service;

import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.service.BandService;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class TrackServiceImpl implements TrackService {
	
	private final TrackRepository trackRepository;
	private final TrackMapper trackMapper;
	private final MediaAssetService mediaAssetService;
	private final MusicianProfileService musicianProfileService;
	private final BandService bandService;
	
	@Override
	@Transactional
	public TrackResponseDto createTrack(UUID ownerId, UUID userId, TrackCreateRequestDto dto) {
		
		// 1) MediaAsset var mı?
		if (!mediaAssetService.exists(dto.mediaAssetId())) {
			log.warn("[Track] MediaAsset bulunamadı: {}", dto.mediaAssetId());
			throw new SoundConnectException(ErrorType.MEDIA_ASSET_NOT_FOUND);
		}
		
		// 2) Owner kim? (Musician mı, Band mi?) + user gerçekten o owner'a bağlı mı?
		TrackOwnerType ownerType = resolveOwnerType(ownerId, userId);
		
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
	
	@Override
	@Transactional(readOnly = true)
	public List<TrackResponseDto> getTracksByOwner(UUID ownerId, TrackOwnerType ownerType) {
		List<Track> tracks = trackRepository.findByOwnerIdAndOwnerType(ownerId, ownerType);
		
		log.info("[Track] Owner için track listelendi. ownerId={}, ownerType={}, count={}",
		         ownerId, ownerType, tracks.size());
		
		return tracks.stream()
		             .map(track -> trackMapper.toDto(track, mediaAssetService))
		             .toList();
	}
	
	/**
	 * Owner gerçekten geçerli mi?
	 * - MUSICIAN_PROFILE ise: ilgili MusicianProfile var mı?
	 * - BAND ise: Band var mı ve user bu band'in üyesi mi?
	 *
	 * Geçerli değilse exception fırlatır.
	 */
	private TrackOwnerType resolveOwnerType(UUID ownerId, UUID userId) {
		boolean musicianOwner = false;
		boolean bandOwner = false;
		
		// MusicianProfile var mı?
		try {
			musicianProfileService.getProfileEntity(ownerId);
			musicianOwner = true;
		} catch (Exception ignored) {
			// log yazmıyoruz; band tarafını da deneyeceğiz
		}
		
		// Band var mı ve user bu band'in üyesi mi?
		try {
			var band = bandService.getBandEntity(ownerId);
			var isMember = band.getMembers().stream()
			                   .anyMatch(m -> m.getUser().getId().equals(userId));
			
			if (!isMember) {
				throw new SoundConnectException(ErrorType.BAND_MEMBER_NOT_FOUND);
			}
			bandOwner = true;
		} catch (SoundConnectException e) {
			// Bizim attığımız anlamlı hata ise olduğu gibi bırak
			if (e.getErrorType() == ErrorType.BAND_MEMBER_NOT_FOUND) {
				log.warn("[Track] Band member bulunamadı. ownerId={}, userId={}", ownerId, userId);
			}
		} catch (Exception ignored) {
			// band yoksa sorun değil, musicianOwner'a bakacağız
		}
		
		if (musicianOwner && bandOwner) {
			// Teorik olarak olmaması lazım ama veri bozulursa buraya düşebilir
			log.error("[Track] Owner hem MusicianProfile hem Band olarak bulundu. ownerId={}", ownerId);
			throw new SoundConnectException(ErrorType.TRACK_OWNER_INVALID);
		}
		
		if (musicianOwner) {
			return TrackOwnerType.MUSICIAN_PROFILE;
		}
		
		if (bandOwner) {
			return TrackOwnerType.BAND;
		}
		
		log.warn("[Track] Owner doğrulaması başarısız. ownerId={}, userId={}", ownerId, userId);
		throw new SoundConnectException(ErrorType.TRACK_OWNER_INVALID);
	}
}
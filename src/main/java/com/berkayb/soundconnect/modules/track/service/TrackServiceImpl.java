package com.berkayb.soundconnect.modules.track.service;

import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.service.BandService;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.service.MusicianProfileService;
import com.berkayb.soundconnect.modules.track.dto.request.TrackCreateRequestDto;
import com.berkayb.soundconnect.modules.track.dto.response.TrackResponseDto;
import com.berkayb.soundconnect.modules.track.entity.Track;
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
public class TrackServiceImpl implements TrackService{
	
	
	private final TrackRepository trackRepository;
	private final TrackMapper trackMapper;
	private final MediaAssetService mediaAssetService;
	private final MusicianProfileService musicianProfileService;
	private final BandService bandService;
	
	@Override
	@Transactional
	public TrackResponseDto createTrack(UUID ownerId, UUID userId, TrackCreateRequestDto dto) {
		// media asset dogrula
		if (!mediaAssetService.exists(dto.mediaAssetId())) {
			log.warn("MediaAsset bulunamadı: {}", dto.mediaAssetId());
			throw new SoundConnectException(ErrorType.MEDIA_ASSET_NOT_FOUND);
		}
		// owner validasyonu profile veya band olabilir
		validateOwner(ownerId, userId);
		
		// track olustur
		Track track = Track.builder()
				.title(dto.title())
				.ownerId(ownerId)
				.mediaAssetId(dto.mediaAssetId())
				.durationSeconds(dto.durationSeconds())
				.build();
		
		trackRepository.save(track);
		
		log.info("Yeni track olusturuldu. ownerId={}, trackId={}", ownerId, track.getId());
		
		// dto don
		return trackMapper.toDto(track, mediaAssetService);
		
	}
	
	@Override
	@Transactional(readOnly = true)
	public TrackResponseDto getTrackById(UUID trackId) {
		Track track = getTrackEntity(trackId);
		return trackMapper.toDto(track, mediaAssetService);
	}
	
	@Override
	public Track getTrackEntity(UUID trackId) {
		return trackRepository.findById(trackId)
				.orElseThrow(() -> new SoundConnectException(ErrorType.TRACK_NOT_FOUND));
	}
	
	
	private void validateOwner(UUID ownerId, UUID userId) {
		boolean musicianOwner = false;
		boolean bandOwner = false;
		
		try {
			musicianProfileService.getProfileEntity(ownerId);
			musicianOwner = true;
		} catch (Exception ignored) {}
		
		try {
			var band = bandService.getBandEntity(ownerId);
			var bm = band.getMembers().stream().anyMatch(m -> m.getUser().getId().equals(userId));
			if (!bm) throw new SoundConnectException(ErrorType.BAND_MEMBER_NOT_FOUND);
			bandOwner = true;
		} catch (Exception ignored) {}
		
		if (!musicianOwner && !bandOwner) {
			log.warn("Owner doğrulaması başarısız. ownerId={}, userId={}", ownerId, userId);
			throw new SoundConnectException(ErrorType.TRACK_OWNER_INVALID);
		}
	}
}
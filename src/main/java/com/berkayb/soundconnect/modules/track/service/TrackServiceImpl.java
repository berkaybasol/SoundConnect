package com.berkayb.soundconnect.modules.track.service;

import com.berkayb.soundconnect.modules.media.enums.MediaKind;
import com.berkayb.soundconnect.modules.media.enums.MediaOwnerType;
import com.berkayb.soundconnect.modules.media.enums.MediaStatus;
import com.berkayb.soundconnect.modules.media.enums.MediaVisibility;
import com.berkayb.soundconnect.modules.media.repository.MediaAssetRepository;
import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.enums.BandMemberShipStatus;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.enums.BandRole;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.service.BandService;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.service.MusicianProfileService;
import com.berkayb.soundconnect.modules.profile.StudioProfile.repository.StudioProfileRepository;
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
	private final TrackRepository trackRepository;
	private final TrackMapper trackMapper;
	private final MediaAssetService mediaAssetService;
	private final MusicianProfileService musicianProfileService;
	private final BandService bandService;
	private final StudioProfileRepository studioProfileRepository;
	private final MediaAssetRepository mediaAssetRepository;

	@Override
	@Transactional(readOnly = true)
	public List<TrackResponseDto> getTracksByOwner(UUID ownerId, TrackOwnerType ownerType) {
		List<Track> tracks = trackRepository.findAllByOwnerIdAndOwnerType(ownerId, ownerType);
		Map<UUID, String> playbackUrls = mediaAssetService.getPlaybackUrlMap(
				tracks.stream().map(Track::getMediaAssetId).toList()
		);
		return tracks.stream().map(track -> toDto(track, playbackUrls)).toList();
	}

	@Override
	@Transactional(readOnly = true)
	public Page<TrackResponseDto> listTracks(UUID ownerId, TrackOwnerType ownerType, Pageable pageable) {
		Page<Track> tracks = trackRepository.findByOwnerIdAndOwnerType(ownerId, ownerType, pageable);
		Map<UUID, String> playbackUrls = mediaAssetService.getPlaybackUrlMap(
				tracks.getContent().stream().map(Track::getMediaAssetId).toList()
		);
		return tracks.map(track -> toDto(track, playbackUrls));
	}

	@Override
	@Transactional
	public void deleteTrack(UUID trackId, UUID ownerId, UUID userId, TrackOwnerType ownerType) {
		if (userId == null) throw new SoundConnectException(ErrorType.USER_NOT_FOUND);
		Track track = getTrackEntity(trackId);
		if (!track.getOwnerId().equals(ownerId) || track.getOwnerType() != ownerType) {
			throw new SoundConnectException(ErrorType.TRACK_OWNER_INVALID);
		}
		validateOwner(ownerId, userId, ownerType);
		// Serialize with attachment and engagement operations before detaching.
		mediaAssetRepository.findByIdAndOwnerForUpdate(track.getMediaAssetId(), mediaOwnerType(ownerType), ownerId)
				.orElseThrow(() -> new SoundConnectException(ErrorType.MEDIA_ASSET_NOT_FOUND));
		trackRepository.delete(track);
		trackRepository.flush();
		// The reference guard now sees the detached track. Failure rolls back both
		// operations; storage cleanup only starts after the transaction commits.
		mediaAssetService.delete(track.getMediaAssetId(), userId, mediaOwnerType(ownerType), ownerId);
		log.info("Track deleted trackId={} ownerId={} ownerType={} actor={}",
				trackId, ownerId, ownerType, userId);
	}

	@Override
	@Transactional
	public TrackResponseDto createTrack(UUID ownerId, UUID userId, TrackCreateRequestDto dto) {
		if (userId == null) throw new SoundConnectException(ErrorType.USER_NOT_FOUND);
		if (ownerId == null || dto == null || dto.mediaAssetId() == null
				|| dto.title() == null || dto.title().isBlank()) {
			throw new SoundConnectException(ErrorType.BAD_REQUEST);
		}

		TrackOwnerType ownerType = resolveOwnerType(ownerId);
		validateOwner(ownerId, userId, ownerType);
		var asset = mediaAssetRepository.findByIdForUpdate(dto.mediaAssetId())
				.orElseThrow(() -> new SoundConnectException(ErrorType.MEDIA_ASSET_NOT_FOUND));

		MediaOwnerType expectedMediaOwnerType = mediaOwnerType(ownerType);
		if (asset.getOwnerType() != expectedMediaOwnerType || !ownerId.equals(asset.getOwnerId())) {
			throw new SoundConnectException(ErrorType.MEDIA_ASSET_OWNER_MISMATCH);
		}

		Track existing = trackRepository
				.findByOwnerTypeAndOwnerIdAndMediaAssetId(ownerType, ownerId, dto.mediaAssetId())
				.orElse(null);
		if (existing != null) {
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

		Track track = Track.builder()
				.title(dto.title().trim())
				.mediaAssetId(dto.mediaAssetId())
				.ownerId(ownerId)
				.ownerType(ownerType)
				.durationSeconds(dto.durationSeconds())
				.bpm(dto.bpm())
				.build();
		trackRepository.save(track);
		log.info("Track created trackId={} ownerId={} ownerType={}",
				track.getId(), ownerId, ownerType);
		return trackMapper.toDto(track, mediaAssetService);
	}

	@Override
	@Transactional(readOnly = true)
	public TrackResponseDto getTrackById(UUID trackId) {
		return trackMapper.toDto(getTrackEntity(trackId), mediaAssetService);
	}

	@Override
	@Transactional(readOnly = true)
	public Track getTrackEntity(UUID trackId) {
		return trackRepository.findById(trackId)
				.orElseThrow(() -> new SoundConnectException(ErrorType.TRACK_NOT_FOUND));
	}

	private TrackOwnerType resolveOwnerType(UUID ownerId) {
		boolean musician = ownerExistsAsMusician(ownerId);
		boolean band = ownerExistsAsBand(ownerId);
		boolean studio = studioProfileRepository.existsById(ownerId);
		int matches = (musician ? 1 : 0) + (band ? 1 : 0) + (studio ? 1 : 0);
		if (matches != 1) {
			log.warn("Track owner could not be resolved uniquely ownerId={} matches={}", ownerId, matches);
			throw new SoundConnectException(ErrorType.TRACK_OWNER_INVALID);
		}
		if (musician) return TrackOwnerType.MUSICIAN_PROFILE;
		if (band) return TrackOwnerType.BAND;
		return TrackOwnerType.STUDIO_PROFILE;
	}

	private boolean ownerExistsAsMusician(UUID ownerId) {
		try {
			musicianProfileService.getProfileEntity(ownerId);
			return true;
		} catch (SoundConnectException exception) {
			if (exception.getErrorType() != ErrorType.PROFILE_NOT_FOUND) throw exception;
			return false;
		}
	}

	private boolean ownerExistsAsBand(UUID ownerId) {
		try {
			bandService.getBandEntity(ownerId);
			return true;
		} catch (SoundConnectException exception) {
			if (exception.getErrorType() != ErrorType.BAND_NOT_FOUND) throw exception;
			return false;
		}
	}

	private void validateOwner(UUID ownerId, UUID userId, TrackOwnerType ownerType) {
		switch (ownerType) {
			case MUSICIAN_PROFILE -> {
				var profile = musicianProfileService.getProfileEntity(ownerId);
				if (!profile.getUser().getId().equals(userId)) {
					throw new SoundConnectException(ErrorType.TRACK_OWNER_INVALID);
				}
			}
			case BAND -> {
				var band = bandService.getBandEntity(ownerId);
				boolean authorized = band.getMembers().stream().anyMatch(member ->
						member.getUser() != null
								&& member.getUser().getId().equals(userId)
								&& member.getStatus() == BandMemberShipStatus.ACTIVE
								&& (member.getBandRole() == BandRole.FOUNDER
								|| member.getBandRole() == BandRole.MANAGER));
				if (!authorized) throw new SoundConnectException(ErrorType.TRACK_OWNER_INVALID);
			}
			case STUDIO_PROFILE -> {
				var studio = studioProfileRepository.findById(ownerId)
						.orElseThrow(() -> new SoundConnectException(ErrorType.TRACK_OWNER_INVALID));
				if (!studio.getUser().getId().equals(userId)) {
					throw new SoundConnectException(ErrorType.TRACK_OWNER_INVALID);
				}
			}
			default -> throw new SoundConnectException(ErrorType.TRACK_OWNER_INVALID);
		}
	}

	private MediaOwnerType mediaOwnerType(TrackOwnerType ownerType) {
		return switch (ownerType) {
			case BAND -> MediaOwnerType.BAND;
			case MUSICIAN_PROFILE -> MediaOwnerType.MUSICIAN_PROFILE;
			case STUDIO_PROFILE -> MediaOwnerType.STUDIO_PROFILE;
			default -> throw new SoundConnectException(ErrorType.TRACK_OWNER_INVALID);
		};
	}

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

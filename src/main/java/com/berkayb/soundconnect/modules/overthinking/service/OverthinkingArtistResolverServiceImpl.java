package com.berkayb.soundconnect.modules.overthinking.service;

import com.berkayb.soundconnect.modules.overthinking.dto.request.OverthinkingPostSaveRequestDto;
import com.berkayb.soundconnect.modules.overthinking.entity.OverthinkingPost;
import com.berkayb.soundconnect.modules.overthinking.enums.OverthinkingArtistType;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity.Band;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.repository.BandRepository;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.entity.MusicianProfile;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.repository.MusicianProfileRepository;
import com.berkayb.soundconnect.modules.track.entity.Track;
import com.berkayb.soundconnect.modules.track.enums.TrackOwnerType;
import com.berkayb.soundconnect.modules.track.service.TrackService;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class OverthinkingArtistResolverServiceImpl implements OverthinkingArtistResolverService {
	
	private final MusicianProfileRepository musicianProfileRepository;
	private final BandRepository bandRepository;
	private final TrackService trackService;
	
	@Override
	public void resolveAndSetArtist(OverthinkingPost post, OverthinkingPostSaveRequestDto dto) {
		validateMusicSource(dto);
		
		// Spotify track URL varsa post Spotify baglantili kabul edilir.
		// spotifyArtistId opsiyoneldir; varsa SoundConnect artist eslestirmesi denenir.
		if (hasText(dto.spotifyTrackUrl())) {
			if (hasText(dto.spotifyArtistId())) {
				handleSpotify(post, dto.spotifyArtistId());
			}
			return;
		}
		
		if (dto.musicianTrackId() != null) {
			handleTrack(post, dto.musicianTrackId(), TrackOwnerType.MUSICIAN_PROFILE);
			return;
		}
		
		if (dto.bandTrackId() != null) {
			handleTrack(post, dto.bandTrackId(), TrackOwnerType.BAND);
		}
	}
	
	private void validateMusicSource(OverthinkingPostSaveRequestDto dto) {
		boolean spotifyTrackProvided = hasText(dto.spotifyTrackUrl());
		boolean spotifyArtistProvided = hasText(dto.spotifyArtistId());
		boolean musicianTrackProvided = dto.musicianTrackId() != null;
		boolean bandTrackProvided = dto.bandTrackId() != null;
		
		int sourceCount = 0;
		if (spotifyTrackProvided) sourceCount++;
		if (musicianTrackProvided) sourceCount++;
		if (bandTrackProvided) sourceCount++;
		
		if (sourceCount > 1) {
			log.warn("[Overthinking] Birden fazla muzik kaynagi gonderildi");
			throw new SoundConnectException(ErrorType.OVERTHINKING_MULTIPLE_MUSIC_SOURCE);
		}
		
		if (spotifyArtistProvided && !spotifyTrackProvided) {
			log.warn(
					"[Overthinking] Spotify artist id var ama track url yok. spotifyArtistId={}",
					dto.spotifyArtistId()
			);
			throw new SoundConnectException(ErrorType.OVERTHINKING_SPOTIFY_SOURCE_INVALID);
		}
	}
	
	private boolean hasText(String value) {
		return value != null && !value.isBlank();
	}
	
	private void handleSpotify(OverthinkingPost post, String spotifyArtistId) {
		MusicianProfile mp = musicianProfileRepository
				.findBySpotifyArtistId(spotifyArtistId)
				.orElse(null);
		
		if (mp != null) {
			post.setArtistId(mp.getId());
			post.setArtistType(OverthinkingArtistType.MUSICIAN_PROFILE);
			log.info("[Overthinking] Spotify artist match -> MusicianProfile: {}", mp.getId());
			return;
		}
		
		Band band = bandRepository.findBySpotifyArtistId(spotifyArtistId)
		                          .orElse(null);
		
		if (band != null) {
			post.setArtistId(band.getId());
			post.setArtistType(OverthinkingArtistType.BAND);
			log.info("[Overthinking] Spotify artist match -> Band: {}", band.getId());
			return;
		}
		
		log.info("[Overthinking] Spotify sanatcisi SoundConnect'te bulunamadi: {}", spotifyArtistId);
	}
	
	private void handleTrack(OverthinkingPost post, UUID trackId, TrackOwnerType expectedOwnerType) {
		Track track = trackService.getTrackEntity(trackId);
		
		if (!track.getOwnerType().equals(expectedOwnerType)) {
			log.warn(
					"[Overthinking] Track ownerType uyusmuyor. trackId={}, actual={}, expected={}",
					trackId,
					track.getOwnerType(),
					expectedOwnerType
			);
			throw new SoundConnectException(ErrorType.TRACK_OWNER_INVALID);
		}
		
		post.setArtistId(track.getOwnerId());
		
		if (track.getOwnerType() == TrackOwnerType.MUSICIAN_PROFILE) {
			post.setArtistType(OverthinkingArtistType.MUSICIAN_PROFILE);
		} else if (track.getOwnerType() == TrackOwnerType.BAND) {
			post.setArtistType(OverthinkingArtistType.BAND);
		}
		
		log.info("[Overthinking] Track match -> artistId={}, type={}", post.getArtistId(), post.getArtistType());
	}
}
package com.berkayb.soundconnect.modules.overthinking.service;

import com.berkayb.soundconnect.modules.overthinking.dto.request.OverthinkingPostSaveRequestDto;
import com.berkayb.soundconnect.modules.overthinking.entity.OverthinkingPost;
import com.berkayb.soundconnect.modules.overthinking.enums.OverthinkingArtistType;
import com.berkayb.soundconnect.modules.overthinking.support.OverthinkingSpotifyReference;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity.Band;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.repository.BandRepository;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.entity.MusicianProfile;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.repository.MusicianProfileRepository;
import com.berkayb.soundconnect.modules.track.entity.Track;
import com.berkayb.soundconnect.modules.track.enums.TrackOwnerType;
import com.berkayb.soundconnect.modules.track.repository.TrackRepository;
import com.berkayb.soundconnect.modules.comment.repository.CommentTargetAccessRepository;
import com.berkayb.soundconnect.modules.spotify.dto.response.SpotifyTrackItemDto;
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
	private final TrackRepository trackRepository;
	private final CommentTargetAccessRepository mediaAccess;
	
	@Override
	public void resolveAndSetArtist(OverthinkingPost post, OverthinkingPostSaveRequestDto dto) {
		resolveAndSetArtist(post, dto, null);
	}

	@Override
	public void resolveAndSetArtist(OverthinkingPost post, OverthinkingPostSaveRequestDto dto, SpotifyTrackItemDto snapshot) {
		validateMusicSource(dto);
		post.setArtistId(null);
		post.setArtistType(null);
		post.setSpotifyTrackUrl(null);
		post.setSpotifyArtistId(null);
		post.setSpotifyTrackName(null);
		post.setSpotifyArtistName(null);
		post.setSpotifyAlbumImageUrl(null);

		if (hasText(dto.spotifyTrackUrl())) {
			var reference = OverthinkingSpotifyReference.parse(dto.spotifyTrackUrl());
			post.setSpotifyTrackUrl(reference.canonicalUrl());
			post.setSpotifyTrackName(OverthinkingSpotifyReference.text(dto.spotifyTrackName(), 512));
			post.setSpotifyArtistName(OverthinkingSpotifyReference.text(dto.spotifyArtistName(), 512));
			post.setSpotifyAlbumImageUrl(OverthinkingSpotifyReference.imageUrl(dto.spotifyAlbumImageUrl()));
			String artistId = OverthinkingSpotifyReference.artistId(dto.spotifyArtistId());
			// This public Spotify ID is a retry/display hint, never an account binding.
			post.setSpotifyArtistId(artistId);
            if (snapshot != null && reference.trackId().equals(snapshot.spotifyTrackId())) {
                if (hasText(snapshot.name()) && snapshot.name().length() <= 512) post.setSpotifyTrackName(snapshot.name().strip());
                if (snapshot.artistNames() != null && !snapshot.artistNames().isEmpty()) {
                    String names = String.join(", ", snapshot.artistNames());
                    if (hasText(names) && names.length() <= 512) post.setSpotifyArtistName(names.strip());
                }
                String artwork = OverthinkingSpotifyReference.imageUrlOrNull(snapshot.albumImageUrl());
                if (artwork != null) post.setSpotifyAlbumImageUrl(artwork);
                if (artistId != null && snapshot.artistIds() != null && snapshot.artistIds().contains(artistId))
                    handleSpotify(post, artistId);
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
		boolean spotifyMetadataProvided = hasText(dto.spotifyArtistId()) || hasText(dto.spotifyTrackName())
				|| hasText(dto.spotifyArtistName()) || hasText(dto.spotifyAlbumImageUrl());
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
		
		if (spotifyMetadataProvided && !spotifyTrackProvided) {
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
		// A missing retained attachment is recoverable by the enclosing update.
		// Nonthrowing repository lookups avoid marking that transaction rollback-only.
		Track track = trackRepository.findById(trackId)
				.orElseThrow(() -> new SoundConnectException(ErrorType.TRACK_NOT_FOUND));
		if (!track.getOwnerType().equals(expectedOwnerType)) {
			throw new SoundConnectException(ErrorType.TRACK_OWNER_INVALID);
		}
		String mediaOwnerType = expectedOwnerType == TrackOwnerType.BAND ? "BAND" : "MUSICIAN_PROFILE";
		if (mediaAccess.lockPublicMedia(track.getMediaAssetId(), mediaOwnerType, track.getOwnerId()).isEmpty()) {
			if (!trackRepository.existsById(trackId)) {
				throw new SoundConnectException(ErrorType.TRACK_NOT_FOUND);
			}
			throw new SoundConnectException(ErrorType.ENGAGEMENT_NOT_FOUND);
		}
		var locked = trackRepository.lockForReference(trackId)
				.orElseThrow(() -> new SoundConnectException(ErrorType.TRACK_NOT_FOUND));
		if (!locked.getMediaAssetId().equals(track.getMediaAssetId())) {
			throw new SoundConnectException(ErrorType.TRACK_NOT_FOUND);
		}

		if (!locked.getOwnerType().equals(expectedOwnerType.name())) {
			log.warn(
					"[Overthinking] Track ownerType uyusmuyor. trackId={}, actual={}, expected={}",
					trackId,
					track.getOwnerType(),
					expectedOwnerType
			);
			throw new SoundConnectException(ErrorType.TRACK_OWNER_INVALID);
		}
		
		post.setArtistId(locked.getOwnerId());
		
		if (expectedOwnerType == TrackOwnerType.MUSICIAN_PROFILE) {
			post.setArtistType(OverthinkingArtistType.MUSICIAN_PROFILE);
		} else if (expectedOwnerType == TrackOwnerType.BAND) {
			post.setArtistType(OverthinkingArtistType.BAND);
		}
		
		log.info("[Overthinking] Track match -> artistId={}, type={}", post.getArtistId(), post.getArtistType());
	}
}

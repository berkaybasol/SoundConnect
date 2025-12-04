package com.berkayb.soundconnect.modules.overthinking.service;


import com.berkayb.soundconnect.modules.overthinking.dto.request.OverthinkingPostSaveRequestDto;
import com.berkayb.soundconnect.modules.overthinking.entity.OverthinkingPost;
import com.berkayb.soundconnect.modules.overthinking.enums.OverthinkingArtistType;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity.Band;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.repository.BandRepository;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.entity.MusicianProfile;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.repository.MusicianProfileRepository;
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
	// bandtrack repo ve musiciantrackrepo gelcek
	
	
	@Override
	public void resolveAndSetArtist(OverthinkingPost post, OverthinkingPostSaveRequestDto dto) {
		
		// birden fazla muzik kaynayi gondermeyi engelle
		validateSingleSource(dto);
		
		// spotify secilmisse
		if (dto.spotifyArtistId() != null) {
			handleSpotify(post, dto.spotifyArtistId());
			return;
		}
		
		// MusicianProfile track secilmisse
		if (dto.musicianTrackId() != null) {
			handleMusicianTrack(post, dto.musicianTrackId());
			return;
		}
		
		if (dto.bandTrackId() != null) {
			handleBandTrack(post, dto.bandTrackId());
			return;
		}
	}
	
	private void validateSingleSource(OverthinkingPostSaveRequestDto dto) {
		int count = 0;
		if (dto.spotifyArtistId() != null) count++;
		if (dto.musicianTrackId() != null) count++;
		if (dto.bandTrackId() != null) count++;
		
		if (count > 1) {
			log.warn("birden fazla muzik kaynagi gonderildi");
			throw new SoundConnectException(ErrorType.OVERTHINKING_MULTIPLE_MUSIC_SOURCE);
		}
	}
	
	// Spotify -> once MusicianProfile, sonra Band match edilen sanatci setlenir
	private void handleSpotify(OverthinkingPost post, String spotifyArtistId) {
		
		MusicianProfile mp = musicianProfileRepository
				.findBySpotifyArtistId(spotifyArtistId)
				.orElse(null);
		
		if (mp != null) {
			post.setArtistId(mp.getId());
			post.setArtistType(OverthinkingArtistType.MUSICIAN_PROFILE);
			log.info("Spotify artist match -> MusicianProfile: {}", mp.getId());
			return;
		}
		
		Band band = bandRepository.findBySpotifyArtistId(spotifyArtistId)
		                          .orElse(null);
		
		if (band != null) {
			post.setArtistId(band.getId());
			post.setArtistType(OverthinkingArtistType.BAND);
			log.info("Spotify artist match -> Band: {}", band.getId());
			return;
		}
		
		// spotify sanatcisi soundconnect'te yok artist null kalabilir
		log.info("Sanatci SoundConnect'te bulanamadi: {}", spotifyArtistId);
	}
	
	private void handleMusicianTrack(OverthinkingPost post, UUID musicianTrackId) {
		//FIXME media modulu gelecek track -> musicianProfile iliskisi burda cozulcek
		// gecici hata firlatiyoruz
		log.error("MusicianTrack destegi henuz implement edilmedi");
		throw new SoundConnectException(ErrorType.MEDIA_NOT_IMPLEMENTED);
	}
	
	private void handleBandTrack(OverthinkingPost post, UUID bandTrackId) {
		//FIXME media modulu gelecek track -> musicianProfile iliskisi burda cozulcek
		log.error("BandTrack desteği henüz implement edilmedi");
		throw new SoundConnectException(ErrorType.MEDIA_NOT_IMPLEMENTED);
	}
	
}
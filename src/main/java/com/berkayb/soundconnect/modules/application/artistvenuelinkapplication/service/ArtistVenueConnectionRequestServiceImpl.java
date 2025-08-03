package com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.service;

import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.dto.request.ArtistVenueConnectionRequestCreateDto;
import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.dto.response.ArtistVenueConnectionRequestResponseDto;
import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.entity.ArtistVenueConnectionRequest;
import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.enums.RequestByType;
import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.enums.RequestStatus;
import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.mapper.ArtistVenueConnectionRequestMapper;
import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.repository.ArtistVenueConnectionRequestRepository;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.entity.MusicianProfile;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.repository.MusicianProfileRepository;
import com.berkayb.soundconnect.modules.venue.entity.Venue;
import com.berkayb.soundconnect.modules.venue.repository.VenueRepository;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

// muzisyen ve mekan arasindaki iliski basvurularinin yonetildigi servis sinifidir.

@Service
@RequiredArgsConstructor
@Slf4j
public class ArtistVenueConnectionRequestServiceImpl implements ArtistVenueConnectionRequestService {
	private final ArtistVenueConnectionRequestRepository repository;
	private final MusicianProfileRepository musicianProfileRepository;
	private final VenueRepository venueRepository;
	private final ArtistVenueConnectionRequestMapper artistVenueConnectionRequestMapper;
	
	
	@Override
	public ArtistVenueConnectionRequestResponseDto createRequest(ArtistVenueConnectionRequestCreateDto dto, RequestByType requestType) {
		log.info("yeni artist-venue baglantisi basvurusu baslatiliyor. musicianProfileId={}, venueId={}, requestBy={}",
		         dto.musicianProfileId(), dto.venueId(), requestType);
		
		// dublicate kontrolu
		if (repository.existsByMusicianProfileIdAndVenueIdAndStatus(
				dto.musicianProfileId(), dto.venueId(), RequestStatus.PENDING)) {
			log.warn("zaten bekleyen bir basvuru mevcut. musicianProfileId={}, venueId={}", dto.musicianProfileId(), dto.venueId());
			throw new SoundConnectException(ErrorType.REQUEST_PENDING_ALREADY);
		}
		
		// entity'leri getir
		MusicianProfile musician = musicianProfileRepository.findById(dto.musicianProfileId())
		                                                    .orElseThrow(() -> {
			                                                    log.error("Musician profile bulunamadi. musicianProfileId={}", dto.musicianProfileId());
			                                                    throw new SoundConnectException(ErrorType.PROFILE_NOT_FOUND);
		                                                    });
		
		Venue venue = venueRepository.findById(dto.venueId())
		                             .orElseThrow(() -> {
			                             log.error("Venue bulunamadı! venueId={}", dto.venueId());
			                             throw new SoundConnectException(ErrorType.VENUE_NOT_FOUND);
		                             });
		
		// basvuru olustur
		ArtistVenueConnectionRequest request = new ArtistVenueConnectionRequest();
		request.setMusicianProfile(musician);
		request.setVenue(venue);
		request.setStatus(RequestStatus.PENDING);
		request.setRequestByType(requestType); // <<== düzeltildi
		request.setMessage(dto.message());
		
		// kaydet
		ArtistVenueConnectionRequest saved = repository.save(request);
		
		log.info("Bağlantı başvurusu oluşturuldu. requestId={}", saved.getId());
		
		// response'a çevir
		return artistVenueConnectionRequestMapper.toResponseDto(saved);
	}
	
	
	@Transactional // birden fazla entity kaydini ayni anda guncelliyoruz birinde hata olursa rollback olmasini saglicak
	@Override
	public ArtistVenueConnectionRequestResponseDto acceptRequest(UUID requestId) {
		log.info("Bağlantı başvurusu onaylanıyor, requestId={}", requestId);
		
		// 1. Başvuruyu bul
		ArtistVenueConnectionRequest request = repository.findById(requestId)
		                                                 .orElseThrow(() -> {
			                                                 log.error("Onay başvurusu bulunamadı. requestId={}", requestId);
			                                                 return new SoundConnectException(ErrorType.REQUEST_NOT_FOUND);
		                                                 });
		
		// 2. Zaten onaylanmış mı kontrol et (isteğe bağlı!)
		if (request.getStatus() == RequestStatus.ACCEPTED) {
			log.warn("Başvuru zaten onaylanmış. requestId={}", requestId);
			throw new SoundConnectException(ErrorType.REQUEST_ALREADY_ACCEPTED);
		}
		
		// 3. Statüyü güncelle
		request.setStatus(RequestStatus.ACCEPTED);
		
		// 4. ManyToMany bağlarını güncelle
		// - MusicianProfile'a Venue ekle
		// - Venue'ya MusicianProfile ekle
		var musician = request.getMusicianProfile();
		var venue = request.getVenue();
		
		musician.getActiveVenues().add(venue);
		venue.getActiveMusicians().add(musician);
		
		// 5. Save işlemleri (JPA Cascade varsa bir kere save yeterli olabilir ama iki tarafta da update garantisi için aşağıdaki gibi ikisini de save et)
		musicianProfileRepository.save(musician);
		venueRepository.save(venue);
		
		// 6. Başvuruyu güncelle (save)
		repository.save(request);
		
		log.info("Başvuru onaylandı ve ilişki kuruldu. requestId={}", requestId);
		
		// 7. Mapper ile response dön
		return artistVenueConnectionRequestMapper.toResponseDto(request);
	}
	
	
	@Transactional
	@Override
	public ArtistVenueConnectionRequestResponseDto rejectRequest(UUID requestId) {
		log.info("Bağlantı başvurusu reddediliyor, requestId={}", requestId);
		
		ArtistVenueConnectionRequest request = repository.findById(requestId)
		                                                 .orElseThrow(() -> {
			                                                 log.error("Reddetmek için başvuru bulunamadı. requestId={}", requestId);
			                                                 return new SoundConnectException(ErrorType.REQUEST_NOT_FOUND);
		                                                 });
		
		// Zaten reddedilmiş mi kontrol et
		if (request.getStatus() == RequestStatus.REJECTED) {
			log.warn("Başvuru zaten reddedilmiş. requestId={}", requestId);
			throw new SoundConnectException(ErrorType.REQUEST_ALREADY_REJECTED);
		}
		
		// Statüyü güncelle
		request.setStatus(RequestStatus.REJECTED);
		
		repository.save(request);
		
		log.info("Başvuru reddedildi. requestId={}", requestId);
		
		return artistVenueConnectionRequestMapper.toResponseDto(request);
	}
	
	@Override
	public List<ArtistVenueConnectionRequestResponseDto> getRequestByMusicianProfile(UUID musicianProfileId) {
		log.info("Müzisyen profilinin başvuruları çekiliyor. musicianProfileId={}", musicianProfileId);
		List<ArtistVenueConnectionRequest> requests = repository.findAllByMusicianProfileId(musicianProfileId);
		return requests.stream()
		               .map(artistVenueConnectionRequestMapper::toResponseDto)
		               .toList();
	}
	
	@Override
	public List<ArtistVenueConnectionRequestResponseDto> getRequestsByVenue(UUID venueId) {
		log.info("Venue başvuruları çekiliyor. venueId={}", venueId);
		List<ArtistVenueConnectionRequest> requests = repository.findAllByVenueId(venueId);
		return requests.stream()
		               .map(artistVenueConnectionRequestMapper::toResponseDto)
		               .toList();
	}
}
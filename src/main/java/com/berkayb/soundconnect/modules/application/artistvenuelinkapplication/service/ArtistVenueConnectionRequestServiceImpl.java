package com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.service;

import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.dto.request.ArtistVenueConnectionRequestCreateDto;
import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.dto.response.ArtistVenueConnectionRequestResponseDto;
import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.entity.ArtistVenueConnectionRequest;
import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.enums.RequestByType;
import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.enums.RequestStatus;
import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.mapper.ArtistVenueConnectionRequestMapper;
import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.repository.ArtistVenueConnectionRequestRepository;
import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity.Band;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity.BandMember;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.enums.BandMemberShipStatus;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.repository.BandMemberRepository;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.repository.BandRepository;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.entity.MusicianProfile;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.repository.MusicianProfileRepository;
import com.berkayb.soundconnect.modules.venue.entity.Venue;
import com.berkayb.soundconnect.modules.venue.repository.VenueRepository;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.berkayb.soundconnect.shared.messaging.events.notification.NotificationInboundEvent;
import com.berkayb.soundconnect.shared.messaging.events.notification.NotificationProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
	private final BandRepository bandRepository;
	private final BandMemberRepository bandMemberRepository;
	private final MediaAssetService mediaAssetService;
	private final NotificationProducer notificationProducer;
	
	@Override
	public List<ArtistVenueConnectionRequestResponseDto> getRequestsByBand(UUID bandId, RequestStatus status) {
		List<ArtistVenueConnectionRequest> requests =
				status == null
						? repository.findAllByBandId(bandId)
						: repository.findAllByBandIdAndStatus(bandId, status);
		
		return requests.stream()
		               .map(artistVenueConnectionRequestMapper::toResponseDto)
		               .map(this::enrichBandFields) //eklendi
		               .toList();
	}
	
	@Transactional
	@Override
	public ArtistVenueConnectionRequestResponseDto cancelRequest(UUID requestId) {
		log.info("Basvuru iptal ediliyor. requestId={}", requestId);
		
		ArtistVenueConnectionRequest request = repository.findById(requestId)
		                                                 .orElseThrow(() -> new SoundConnectException(ErrorType.REQUEST_NOT_FOUND));
		
		if (request.getStatus() != RequestStatus.PENDING) {
			if (request.getStatus() == RequestStatus.ACCEPTED) {
				throw new SoundConnectException(ErrorType.REQUEST_CANCEL_NOT_ALLOWED);
			}
			throw new SoundConnectException(ErrorType.REQUEST_ALREADY_REJECTED);
		}
		
		request.setStatus(RequestStatus.REJECTED);
		repository.save(request);
		
		log.info("Basvuru iptal edildi. requestId={}", requestId);
		return enrichBandFields(artistVenueConnectionRequestMapper.toResponseDto(request));
		
	}
	
	
	@Transactional
	@Override
	public ArtistVenueConnectionRequestResponseDto disconnect(UUID requestId) {
		log.info("Mekan baglantisi kaldiriliyor. requestId={}", requestId);
		
		ArtistVenueConnectionRequest request = repository.findById(requestId)
		                                                 .orElseThrow(() -> new SoundConnectException(ErrorType.REQUEST_NOT_FOUND));
		
		if (request.getStatus() != RequestStatus.ACCEPTED) {
			if (request.getStatus() == RequestStatus.PENDING) {
				throw new SoundConnectException(ErrorType.REQUEST_DISCONNECT_NOT_ALLOWED);
			}
			throw new SoundConnectException(ErrorType.REQUEST_ALREADY_REJECTED);
		}
		
		if (request.getRequestByType() == RequestByType.ARTIST || request.getRequestByType() == RequestByType.VENUE) {
			var musician = request.getMusicianProfile();
			var venue = request.getVenue();
			
			if (musician == null) throw new SoundConnectException(ErrorType.PROFILE_NOT_FOUND);
			if (venue == null) throw new SoundConnectException(ErrorType.VENUE_NOT_FOUND);
			
			musician.getActiveVenues().remove(venue);
			venue.getActiveMusicians().remove(musician);
			
			musicianProfileRepository.save(musician);
			venueRepository.save(venue);
		} else if (request.getRequestByType() == RequestByType.BAND) { //eklendi
			var band = request.getBand(); //eklendi
			var venue = request.getVenue(); //eklendi
			
			if (band == null) throw new SoundConnectException(ErrorType.BAND_NOT_FOUND); //eklendi
			if (venue == null) throw new SoundConnectException(ErrorType.VENUE_NOT_FOUND); //eklendi
			
			venue.getActiveBands().remove(band); //eklendi
			venueRepository.save(venue); //eklendi
		} //eklendi
		
		request.setStatus(RequestStatus.REJECTED);
		repository.save(request);
		
		log.info("Baglanti kaldirildi. requestId={}", requestId);
		return enrichBandFields(artistVenueConnectionRequestMapper.toResponseDto(request));
	}
	
	
	
	@Override
	public ArtistVenueConnectionRequestResponseDto createRequest(ArtistVenueConnectionRequestCreateDto dto, RequestByType requestType) {
		log.info("yeni artist-venue baglantisi basvurusu baslatiliyor. musicianProfileId={}, bandId={}, venueId={}, requestBy={}", //eklendi
		         dto.musicianProfileId(), dto.bandId(), dto.venueId(), requestType); //eklendi
		if (requestType == null) {
			throw new SoundConnectException(ErrorType.REQUEST_BY_TYPE_REQUIRED);
		}
		
		// entity'leri getir
		Venue venue = venueRepository.findById(dto.venueId())
		                             .orElseThrow(() -> {
			                             log.error("Venue bulunamadı! venueId={}", dto.venueId());
			                             throw new SoundConnectException(ErrorType.VENUE_NOT_FOUND);
		                             });
		
		// basvuru olustur
		ArtistVenueConnectionRequest request = new ArtistVenueConnectionRequest();
		request.setVenue(venue);
		request.setStatus(RequestStatus.PENDING);
		request.setRequestByType(requestType);
		request.setMessage(dto.message());
		
		// dublicate kontrolu
		if (requestType == RequestByType.ARTIST || requestType == RequestByType.VENUE) { //eklendi
			if (dto.musicianProfileId() == null) { //eklendi
				throw new SoundConnectException(ErrorType.PROFILE_NOT_FOUND); //eklendi
			} //eklendi
			
			if (repository.existsByMusicianProfileIdAndVenueIdAndStatus( //eklendi
			                                                             dto.musicianProfileId(), dto.venueId(), RequestStatus.PENDING)) { //eklendi
				log.warn("zaten bekleyen bir basvuru mevcut. musicianProfileId={}, venueId={}", dto.musicianProfileId(), dto.venueId());
				throw new SoundConnectException(ErrorType.REQUEST_PENDING_ALREADY);
			}
			
			MusicianProfile musician = musicianProfileRepository.findById(dto.musicianProfileId())
			                                                    .orElseThrow(() -> {
				                                                    log.error("Musician profile bulunamadi. musicianProfileId={}", dto.musicianProfileId());
				                                                    throw new SoundConnectException(ErrorType.PROFILE_NOT_FOUND);
			                                                    });
			
			request.setMusicianProfile(musician);
			request.setBand(null); //eklendi
		} else if (requestType == RequestByType.BAND) { //eklendi
			if (dto.bandId() == null) { //eklendi
				throw new SoundConnectException(ErrorType.BAND_NOT_FOUND); //eklendi
			} //eklendi
			
			if (repository.existsByBandIdAndVenueIdAndStatus(dto.bandId(), dto.venueId(), RequestStatus.PENDING)) { //eklendi
				log.warn("zaten bekleyen bir band basvurusu mevcut. bandId={}, venueId={}", dto.bandId(), dto.venueId()); //eklendi
				throw new SoundConnectException(ErrorType.REQUEST_PENDING_ALREADY); //eklendi
			} //eklendi
			
			var band = bandRepository.findById(dto.bandId()) //eklendi
			                         .orElseThrow(() -> { //eklendi
				                         log.error("Band bulunamadi! bandId={}", dto.bandId()); //eklendi
				                         throw new SoundConnectException(ErrorType.BAND_NOT_FOUND); //eklendi
			                         }); //eklendi
			
			request.setBand(band); //eklendi
			request.setMusicianProfile(null); //eklendi
		} else { //eklendi
			throw new SoundConnectException(ErrorType.REQUEST_BY_TYPE_REQUIRED); //eklendi
		} //eklendi
		
		// kaydet
		ArtistVenueConnectionRequest saved = repository.save(request);
		
		log.info("Bağlantı başvurusu oluşturuldu. requestId={}", saved.getId());
		publishRequestCreatedNotification(saved);
		
		// response'a çevir
		return enrichBandFields(artistVenueConnectionRequestMapper.toResponseDto(saved));
		
	}
	
	
	
	@Transactional
	@Override
	public ArtistVenueConnectionRequestResponseDto acceptRequest(UUID requestId) {
		log.info("Bağlantı başvurusu onaylanıyor, requestId={}", requestId);
		
		ArtistVenueConnectionRequest request = repository.findById(requestId)
		                                                 .orElseThrow(() -> {
			                                                 log.error("Onay başvurusu bulunamadı. requestId={}", requestId);
			                                                 return new SoundConnectException(ErrorType.REQUEST_NOT_FOUND);
		                                                 });
		
		if (request.getStatus() != RequestStatus.PENDING) {
			if (request.getStatus() == RequestStatus.REJECTED) {
				throw new SoundConnectException(ErrorType.REQUEST_ALREADY_REJECTED);
			}
			throw new SoundConnectException(ErrorType.REQUEST_ALREADY_ACCEPTED);
		}
		
		request.setStatus(RequestStatus.ACCEPTED);
		
		if (request.getRequestByType() == RequestByType.ARTIST || request.getRequestByType() == RequestByType.VENUE) {
			var musician = request.getMusicianProfile();
			var venue = request.getVenue();
			
			if (musician == null) throw new SoundConnectException(ErrorType.PROFILE_NOT_FOUND);
			if (venue == null) throw new SoundConnectException(ErrorType.VENUE_NOT_FOUND);
			
			musician.getActiveVenues().add(venue);
			venue.getActiveMusicians().add(musician);
			
			musicianProfileRepository.save(musician);
			venueRepository.save(venue);
		} else if (request.getRequestByType() == RequestByType.BAND) { //eklendi
			var band = request.getBand(); //eklendi
			var venue = request.getVenue(); //eklendi
			
			if (band == null) throw new SoundConnectException(ErrorType.BAND_NOT_FOUND); //eklendi
			if (venue == null) throw new SoundConnectException(ErrorType.VENUE_NOT_FOUND); //eklendi
			
			venue.getActiveBands().add(band); //eklendi
			venueRepository.save(venue); //eklendi
		} //eklendi
		
		repository.save(request);
		
		log.info("Başvuru onaylandı. requestId={}", requestId);
		publishRequestDecisionNotification(request, true);
		return enrichBandFields(artistVenueConnectionRequestMapper.toResponseDto(request));
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
		
		
		// zaten pending mi
		if (request.getStatus() != RequestStatus.PENDING) {
			if (request.getStatus() == RequestStatus.ACCEPTED) {
				throw new SoundConnectException(ErrorType.REQUEST_ALREADY_ACCEPTED);
			}
			throw new SoundConnectException(ErrorType.REQUEST_ALREADY_REJECTED);
		}
		
		// Statüyü güncelle
		request.setStatus(RequestStatus.REJECTED);
		
		repository.save(request);
		
		log.info("Başvuru reddedildi. requestId={}", requestId);
		publishRequestDecisionNotification(request, false);
		
		return enrichBandFields(artistVenueConnectionRequestMapper.toResponseDto(request));
		
	}
	
	@Override
	public List<ArtistVenueConnectionRequestResponseDto> getRequestByMusicianProfile(UUID musicianProfileId, RequestStatus status) {
		log.info("Müzisyen profilinin başvuruları çekiliyor. musicianProfileId={}", musicianProfileId);
		List<ArtistVenueConnectionRequest> requests =
				status == null
						? repository.findAllByMusicianProfileId(musicianProfileId)
						: repository.findAllByMusicianProfileIdAndStatus(musicianProfileId, status);
		return requests.stream()
		               .map(artistVenueConnectionRequestMapper::toResponseDto)
		               .map(this::enrichBandFields) //eklendi
		               .toList();
	}
	
	@Override
	public List<ArtistVenueConnectionRequestResponseDto> getRequestsByVenue(UUID venueId, RequestStatus status) {
		
		log.info("Venue başvuruları çekiliyor. venueId={}", venueId);
		List<ArtistVenueConnectionRequest> requests =
				status == null
						? repository.findAllByVenueId(venueId)
						: repository.findAllByVenueIdAndStatus(venueId, status);
		return requests.stream()
		               .map(artistVenueConnectionRequestMapper::toResponseDto)
		               .map(this::enrichBandFields) //eklendi
		               .toList();
	}
	
	private ArtistVenueConnectionRequestResponseDto enrichBandFields(ArtistVenueConnectionRequestResponseDto dto) {
		if (dto.bandId() == null) return dto;
		
		String ppUrl = null;
		try {
			Band band = bandRepository.findById(dto.bandId()).orElse(null);
			if (band != null && band.getProfilePictureMediaId() != null) {
				ppUrl = mediaAssetService.getById(band.getProfilePictureMediaId()).getSourceUrl();
			}
		} catch (Exception ignored) {}
		
		return new ArtistVenueConnectionRequestResponseDto(
				dto.id(),
				dto.musicianProfileId(),
				dto.bandId(),
				dto.venueId(),
				dto.musicianStageName(),
				dto.bandName(),
				ppUrl,
				dto.venueName(),
				dto.message(),
				dto.status(),
				dto.requestByType(),
				dto.createdAt()
		);
	}

	private void publishRequestCreatedNotification(ArtistVenueConnectionRequest request) {
		try {
			UUID recipientId = requestCreatedRecipientId(request);
			if (recipientId == null) return;

			notificationProducer.publish(
					NotificationInboundEvent.builder()
					                        .recipientId(recipientId)
					                        .type(NotificationType.ARTIST_VENUE_LINK_APPLICATION_REQUEST)
					                        .title(requestCreatedTitle(request))
					                        .message(safe(request.getMessage()))
					                        .payload(notificationPayload(request, "REQUEST_CREATED"))
					                        .emailForce(false)
					                        .occurredAt(Instant.now())
					                        .build()
			);
		} catch (Exception e) {
			log.warn("ArtistVenue notification request publish failed. requestId={}, err={}",
			         request.getId(), e.toString());
		}
	}

	private void publishRequestDecisionNotification(ArtistVenueConnectionRequest request, boolean accepted) {
		try {
			NotificationType type = accepted
					? NotificationType.ARTIST_VENUE_LINK_APPLICATION_ACCEPT
					: NotificationType.ARTIST_VENUE_LINK_APPLICATION_REJECT;
			String action = accepted ? "REQUEST_ACCEPTED" : "REQUEST_REJECTED";
			String title = accepted ? requestAcceptedTitle(request) : requestRejectedTitle(request);

			for (UUID recipientId : requestDecisionRecipientIds(request)) {
				notificationProducer.publish(
						NotificationInboundEvent.builder()
						                        .recipientId(recipientId)
						                        .type(type)
						                        .title(title)
						                        .message(safe(request.getMessage()))
						                        .payload(notificationPayload(request, action))
						                        .emailForce(false)
						                        .occurredAt(Instant.now())
						                        .build()
				);
			}
		} catch (Exception e) {
			log.warn("ArtistVenue notification decision publish failed. requestId={}, accepted={}, err={}",
			         request.getId(), accepted, e.toString());
		}
	}

	private UUID requestCreatedRecipientId(ArtistVenueConnectionRequest request) {
		if (request.getRequestByType() == RequestByType.VENUE) {
			return request.getMusicianProfile() == null || request.getMusicianProfile().getUser() == null
					? null
					: request.getMusicianProfile().getUser().getId();
		}
		return request.getVenue() == null || request.getVenue().getOwner() == null
				? null
				: request.getVenue().getOwner().getId();
	}

	private List<UUID> requestDecisionRecipientIds(ArtistVenueConnectionRequest request) {
		if (request.getRequestByType() == RequestByType.VENUE) {
			UUID ownerId = request.getVenue() == null || request.getVenue().getOwner() == null
					? null
					: request.getVenue().getOwner().getId();
			return ownerId == null ? List.of() : List.of(ownerId);
		}
		if (request.getRequestByType() == RequestByType.BAND && request.getBand() != null) {
			return bandMemberRepository.findByBandId(request.getBand().getId()).stream()
			                           .filter(member -> member.getStatus() == BandMemberShipStatus.ACTIVE)
			                           .map(BandMember::getUser)
			                           .filter(Objects::nonNull)
			                           .map(user -> user.getId())
			                           .filter(Objects::nonNull)
			                           .distinct()
			                           .toList();
		}
		UUID musicianUserId = request.getMusicianProfile() == null || request.getMusicianProfile().getUser() == null
				? null
				: request.getMusicianProfile().getUser().getId();
		return musicianUserId == null ? List.of() : List.of(musicianUserId);
	}

	private String requestCreatedTitle(ArtistVenueConnectionRequest request) {
		if (request.getRequestByType() == RequestByType.VENUE) {
			return displayVenueName(request) + " sana baglanti istegi gonderdi";
		}
		return displayApplicantName(request) + " mekanina baglanti istegi gonderdi";
	}

	private String requestAcceptedTitle(ArtistVenueConnectionRequest request) {
		return displayVenueName(request) + " baglanti istegini onayladi";
	}

	private String requestRejectedTitle(ArtistVenueConnectionRequest request) {
		return displayVenueName(request) + " baglanti istegini reddetti";
	}

	private Map<String, Object> notificationPayload(ArtistVenueConnectionRequest request, String action) {
		Map<String, Object> payload = new HashMap<>();
		put(payload, "module", "ARTIST_VENUE");
		put(payload, "action", action);
		put(payload, "requestId", request.getId());
		put(payload, "requestByType", request.getRequestByType());
		put(payload, "status", request.getStatus());
		put(payload, "musicianProfileId", request.getMusicianProfile() == null ? null : request.getMusicianProfile().getId());
		put(payload, "bandId", request.getBand() == null ? null : request.getBand().getId());
		put(payload, "venueId", request.getVenue() == null ? null : request.getVenue().getId());
		put(payload, "applicantName", displayApplicantName(request));
		put(payload, "venueName", displayVenueName(request));
		return payload;
	}

	private void put(Map<String, Object> payload, String key, Object value) {
		if (value != null) payload.put(key, value.toString());
	}

	private String displayApplicantName(ArtistVenueConnectionRequest request) {
		if (request.getRequestByType() == RequestByType.BAND && request.getBand() != null) {
			return safe(request.getBand().getName(), "Band");
		}
		if (request.getMusicianProfile() != null) {
			String stageName = request.getMusicianProfile().getStageName();
			if (hasText(stageName)) return stageName.trim();
			return safe(request.getMusicianProfile().getName(), "Sanatci");
		}
		return "Sanatci";
	}

	private String displayVenueName(ArtistVenueConnectionRequest request) {
		return request.getVenue() == null ? "Mekan" : safe(request.getVenue().getName(), "Mekan");
	}

	private String safe(String value) {
		return value == null ? "" : value.trim();
	}

	private String safe(String value, String fallback) {
		return hasText(value) ? value.trim() : fallback;
	}

	private boolean hasText(String value) {
		return value != null && !value.trim().isEmpty();
	}
	
}

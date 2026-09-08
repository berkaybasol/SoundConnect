package com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.service;

import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.dto.request.ArtistVenueConnectionRequestCreateDto;
import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.dto.response.ArtistVenueConnectionRequestResponseDto;
import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.dto.response.ArtistVenueConnectionRequestPageItemDto;
import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.entity.ArtistVenueConnectionRequest;
import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.enums.RequestByType;
import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.enums.RequestStatus;
import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.mapper.ArtistVenueConnectionRequestMapper;
import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.repository.ArtistVenueConnectionRequestRepository;
import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.repository.ConnectionRequestRow;
import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.modules.notification.service.TransactionalNotificationService;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity.Band;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity.BandMember;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.enums.BandMemberShipStatus;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.enums.BandRole;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.repository.BandMemberRepository;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.repository.BandRepository;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.entity.MusicianProfile;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.repository.MusicianProfileRepository;
import com.berkayb.soundconnect.modules.profile.VenueProfile.repository.VenueProfileRepository;
import com.berkayb.soundconnect.modules.venue.entity.Venue;
import com.berkayb.soundconnect.modules.venue.repository.VenueRepository;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.berkayb.soundconnect.shared.messaging.events.notification.NotificationInboundEvent;
import com.berkayb.soundconnect.shared.response.PageResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

import java.time.Instant;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

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
	private final VenueProfileRepository venueProfileRepository;
	private final MediaAssetService mediaAssetService;
	private final TransactionalNotificationService transactionalNotificationService;

	@Override
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public PageResponse<ArtistVenueConnectionRequestPageItemDto> getBandPage(UUID actorUserId, UUID bandId,
			RequestStatus status, Boolean incoming, int page, int size) {
		Band band = bandRepository.findById(bandId)
				.orElseThrow(() -> new SoundConnectException(ErrorType.BAND_NOT_FOUND));
		assertIsActiveBandMember(actorUserId, band);
		return mapPage(repository.findBandPage(bandId, status, directionTypes(RequestByType.BAND, incoming), pageRequest(page, size)));
	}

	@Override
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public PageResponse<ArtistVenueConnectionRequestPageItemDto> getMusicianPage(UUID actorUserId, UUID musicianProfileId,
			RequestStatus status, Boolean incoming, int page, int size) {
		MusicianProfile musician = musicianProfileRepository.findById(musicianProfileId)
				.orElseThrow(() -> new SoundConnectException(ErrorType.PROFILE_NOT_FOUND));
		assertCanActForMusician(actorUserId, musician);
		return mapPage(repository.findMusicianPage(musicianProfileId, status,
				directionTypes(RequestByType.ARTIST, incoming), pageRequest(page, size)));
	}

	@Override
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public PageResponse<ArtistVenueConnectionRequestPageItemDto> getVenuePage(UUID actorUserId, UUID venueId,
			RequestStatus status, Boolean incoming, int page, int size) {
		Venue venue = venueRepository.findById(venueId)
				.orElseThrow(() -> new SoundConnectException(ErrorType.VENUE_NOT_FOUND));
		assertCanActForVenue(actorUserId, venue);
		return mapPage(repository.findVenuePage(venueId, status, directionTypes(RequestByType.VENUE, incoming), pageRequest(page, size)));
	}

	private PageRequest pageRequest(int page, int size) {
		if (page < 0 || page > 10000 || size < 1 || size > 100) {
			throw new SoundConnectException(ErrorType.REQUEST_PAGE_INVALID);
		}
		return PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt", "id"));
	}

	private List<RequestByType> directionTypes(RequestByType outgoing, Boolean incoming) {
		if (incoming == null) return List.of(RequestByType.ARTIST, RequestByType.BAND, RequestByType.VENUE);
		if (!incoming) return List.of(outgoing);
		return outgoing == RequestByType.VENUE ? List.of(RequestByType.ARTIST, RequestByType.BAND) : List.of(RequestByType.VENUE);
	}

	@Override
	@Transactional(readOnly = true)
	public List<ArtistVenueConnectionRequestResponseDto> getRequestsByBand(UUID actorUserId, UUID bandId, RequestStatus status) {
		Band band = bandRepository.findById(bandId)
		                          .orElseThrow(() -> new SoundConnectException(ErrorType.BAND_NOT_FOUND));
		assertIsActiveBandMember(actorUserId, band);

		List<ArtistVenueConnectionRequest> requests =
				status == null
						? repository.findAllByBandId(bandId)
						: repository.findAllByBandIdAndStatus(bandId, status);

		return mapRequests(requests);
	}

	@Transactional
	@Override
	public ArtistVenueConnectionRequestResponseDto cancelRequest(UUID actorUserId, UUID requestId) {
		log.info("Basvuru iptal ediliyor. requestId={}", requestId);

		ArtistVenueConnectionRequest request = findRequest(requestId);
		assertCanCancel(actorUserId, request);

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
	public ArtistVenueConnectionRequestResponseDto disconnect(UUID actorUserId, UUID requestId) {
		log.info("Mekan baglantisi kaldiriliyor. requestId={}", requestId);

		ArtistVenueConnectionRequest request = findRequest(requestId);
		assertCanDisconnect(actorUserId, request);

		if (request.getStatus() != RequestStatus.ACCEPTED) {
			if (request.getStatus() == RequestStatus.PENDING) {
				throw new SoundConnectException(ErrorType.REQUEST_DISCONNECT_NOT_ALLOWED);
			}
			throw new SoundConnectException(ErrorType.REQUEST_ALREADY_REJECTED);
		}

		if (!targetsBand(request)) {
			var musician = request.getMusicianProfile();
			var venue = request.getVenue();

			if (musician == null) throw new SoundConnectException(ErrorType.PROFILE_NOT_FOUND);
			if (venue == null) throw new SoundConnectException(ErrorType.VENUE_NOT_FOUND);

			musician.getActiveVenues().remove(venue);
			venue.getActiveMusicians().remove(musician);

			musicianProfileRepository.save(musician);
			venueRepository.save(venue);
		} else {
			var band = request.getBand();
			var venue = request.getVenue();

			if (band == null) throw new SoundConnectException(ErrorType.BAND_NOT_FOUND);
			if (venue == null) throw new SoundConnectException(ErrorType.VENUE_NOT_FOUND);

			venue.getActiveBands().remove(band);
			band.getActiveVenues().remove(venue);
			venueRepository.save(venue);
		}

		request.setStatus(RequestStatus.REJECTED);
		repository.save(request);

		log.info("Baglanti kaldirildi. requestId={}", requestId);
		return enrichBandFields(artistVenueConnectionRequestMapper.toResponseDto(request));
	}

	@Override
	@Transactional
	public ArtistVenueConnectionRequestResponseDto createRequest(UUID actorUserId, ArtistVenueConnectionRequestCreateDto dto, RequestByType requestType) {
		log.info("Yeni artist-venue baglantisi baslatiliyor. musicianProfileId={}, bandId={}, venueId={}, requestBy={}",
		         dto.musicianProfileId(), dto.bandId(), dto.venueId(), requestType);
		if (requestType == null) {
			throw new SoundConnectException(ErrorType.REQUEST_BY_TYPE_REQUIRED);
		}
		boolean hasMusicianTarget = dto.musicianProfileId() != null;
		boolean hasBandTarget = dto.bandId() != null;
		if (hasMusicianTarget == hasBandTarget
				|| (requestType == RequestByType.ARTIST && !hasMusicianTarget)
				|| (requestType == RequestByType.BAND && !hasBandTarget)) {
			throw new SoundConnectException(ErrorType.REQUEST_BY_TYPE_REQUIRED);
		}

		// Band first: membership changes and band deletion use this same aggregate lock.
		Band targetBand = hasBandTarget ? bandRepository.findByIdForUpdate(dto.bandId())
				.orElseThrow(() -> new SoundConnectException(ErrorType.BAND_NOT_FOUND)) : null;
		Venue venue = repository.findVenueByIdForUpdate(dto.venueId())
		                             .orElseThrow(() -> new SoundConnectException(ErrorType.VENUE_NOT_FOUND));

		ArtistVenueConnectionRequest request = new ArtistVenueConnectionRequest();
		request.setVenue(venue);
		request.setStatus(RequestStatus.PENDING);
		request.setRequestByType(requestType);
		request.setMessage(dto.message());

		if (requestType == RequestByType.ARTIST) {
			MusicianProfile musician = musicianProfileRepository.findById(dto.musicianProfileId())
			                                                    .orElseThrow(() -> new SoundConnectException(ErrorType.PROFILE_NOT_FOUND));
			assertCanActForMusician(actorUserId, musician);

			request.setMusicianProfile(musician);
			request.setBand(null);
		} else if (requestType == RequestByType.BAND) {
			assertCanManageBand(actorUserId, targetBand);

			request.setBand(targetBand);
			request.setMusicianProfile(null);
		} else if (requestType == RequestByType.VENUE) {
			assertCanActForVenue(actorUserId, venue);

			if (hasBandTarget) {
				request.setBand(targetBand);
				request.setMusicianProfile(null);
			} else {
				MusicianProfile musician = musicianProfileRepository.findById(dto.musicianProfileId())
				                                                    .orElseThrow(() -> new SoundConnectException(ErrorType.PROFILE_NOT_FOUND));
				request.setMusicianProfile(musician);
				request.setBand(null);
			}
		} else {
			throw new SoundConnectException(ErrorType.REQUEST_BY_TYPE_REQUIRED);
		}

		// Authorize the caller before exposing whether this private pair has a request.
		assertBothSidesCanConnect(actorUserId, request);
		assertNotAlreadyConnected(request);
		if (hasRequestWithStatus(request, RequestStatus.PENDING)) {
			throw new SoundConnectException(ErrorType.REQUEST_PENDING_ALREADY);
		}
		ArtistVenueConnectionRequest saved = repository.save(request);

		log.info("Baglanti basvurusu olusturuldu. requestId={}", saved.getId());
		publishRequestCreatedNotification(saved);

		return enrichBandFields(artistVenueConnectionRequestMapper.toResponseDto(saved));
	}

	@Transactional
	@Override
	public ArtistVenueConnectionRequestResponseDto acceptRequest(UUID actorUserId, UUID requestId) {
		log.info("Baglanti basvurusu onaylaniyor, requestId={}", requestId);

		ArtistVenueConnectionRequest request = findRequest(requestId);
		assertCanDecide(actorUserId, request);

		if (request.getStatus() != RequestStatus.PENDING) {
			if (request.getStatus() == RequestStatus.REJECTED) {
				throw new SoundConnectException(ErrorType.REQUEST_ALREADY_REJECTED);
			}
			throw new SoundConnectException(ErrorType.REQUEST_ALREADY_ACCEPTED);
		}

		assertBothSidesCanConnect(actorUserId, request);
		assertNotAlreadyConnected(request);
		request.setStatus(RequestStatus.ACCEPTED);

		if (!targetsBand(request)) {
			var musician = request.getMusicianProfile();
			var venue = request.getVenue();

			if (musician == null) throw new SoundConnectException(ErrorType.PROFILE_NOT_FOUND);
			if (venue == null) throw new SoundConnectException(ErrorType.VENUE_NOT_FOUND);

			musician.getActiveVenues().add(venue);
			venue.getActiveMusicians().add(musician);

			musicianProfileRepository.save(musician);
			venueRepository.save(venue);
		} else {
			var band = request.getBand();
			var venue = request.getVenue();

			if (band == null) throw new SoundConnectException(ErrorType.BAND_NOT_FOUND);
			if (venue == null) throw new SoundConnectException(ErrorType.VENUE_NOT_FOUND);

			venue.getActiveBands().add(band);
			band.getActiveVenues().add(venue);
			venueRepository.save(venue);
		}

		repository.save(request);

		log.info("Basvuru onaylandi. requestId={}", requestId);
		publishRequestDecisionNotification(request, true);
		return enrichBandFields(artistVenueConnectionRequestMapper.toResponseDto(request));
	}

	@Transactional
	@Override
	public ArtistVenueConnectionRequestResponseDto rejectRequest(UUID actorUserId, UUID requestId) {
		log.info("Baglanti basvurusu reddediliyor, requestId={}", requestId);

		ArtistVenueConnectionRequest request = findRequest(requestId);
		assertCanDecide(actorUserId, request);

		if (request.getStatus() != RequestStatus.PENDING) {
			if (request.getStatus() == RequestStatus.ACCEPTED) {
				throw new SoundConnectException(ErrorType.REQUEST_ALREADY_ACCEPTED);
			}
			throw new SoundConnectException(ErrorType.REQUEST_ALREADY_REJECTED);
		}

		request.setStatus(RequestStatus.REJECTED);
		repository.save(request);

		log.info("Basvuru reddedildi. requestId={}", requestId);
		publishRequestDecisionNotification(request, false);

		return enrichBandFields(artistVenueConnectionRequestMapper.toResponseDto(request));
	}

	@Override
	@Transactional(readOnly = true)
	public List<ArtistVenueConnectionRequestResponseDto> getRequestByMusicianProfile(UUID actorUserId, UUID musicianProfileId, RequestStatus status) {
		log.info("Muzisyen profilinin basvurulari cekiliyor. musicianProfileId={}", musicianProfileId);
		MusicianProfile musician = musicianProfileRepository.findById(musicianProfileId)
		                                                    .orElseThrow(() -> new SoundConnectException(ErrorType.PROFILE_NOT_FOUND));
		assertCanActForMusician(actorUserId, musician);

		List<ArtistVenueConnectionRequest> requests =
				status == null
						? repository.findAllByMusicianProfileId(musicianProfileId)
						: repository.findAllByMusicianProfileIdAndStatus(musicianProfileId, status);
		return mapRequests(requests);
	}

	@Override
	@Transactional(readOnly = true)
	public List<ArtistVenueConnectionRequestResponseDto> getRequestsByVenue(UUID actorUserId, UUID venueId, RequestStatus status) {
		log.info("Venue basvurulari cekiliyor. venueId={}", venueId);
		Venue venue = venueRepository.findById(venueId)
		                             .orElseThrow(() -> new SoundConnectException(ErrorType.VENUE_NOT_FOUND));
		assertCanActForVenue(actorUserId, venue);

		List<ArtistVenueConnectionRequest> requests =
				status == null
						? repository.findAllByVenueId(venueId)
						: repository.findAllByVenueIdAndStatus(venueId, status);
		return mapRequests(requests);
	}

	private ArtistVenueConnectionRequest findRequest(UUID requestId) {
		// Read only the scalar target before locking; never retain a stale request
		// entity while waiting for the band membership/deletion fence.
		repository.findBandIdByRequestId(requestId).ifPresent(bandId ->
				bandRepository.findByIdForUpdate(bandId)
						.orElseThrow(() -> new SoundConnectException(ErrorType.REQUEST_NOT_FOUND)));
		ArtistVenueConnectionRequest request = repository.findByIdForUpdate(requestId)
		                 .orElseThrow(() -> new SoundConnectException(ErrorType.REQUEST_NOT_FOUND));
		if (request.getVenue() == null) {
			throw new SoundConnectException(ErrorType.VENUE_NOT_FOUND);
		}
		Venue venue = repository.findVenueByIdForUpdate(request.getVenue().getId())
		                       .orElseThrow(() -> new SoundConnectException(ErrorType.VENUE_NOT_FOUND));
		request.setVenue(venue);
		return request;
	}

	private void assertNotAlreadyConnected(ArtistVenueConnectionRequest request) {
		if (hasRequestWithStatus(request, RequestStatus.ACCEPTED)) {
			throw new SoundConnectException(ErrorType.REQUEST_ALREADY_ACCEPTED);
		}
	}

	private void assertBothSidesCanConnect(UUID actorUserId, ArtistVenueConnectionRequest request) {
		UUID ownerId = request.getVenue() == null || request.getVenue().getOwner() == null
				? null : request.getVenue().getOwner().getId();
		Set<UUID> representatives = new HashSet<>();
		if (targetsBand(request)) {
			request.getBand().getMembers().stream()
					.filter(member -> member.getStatus() == BandMemberShipStatus.ACTIVE)
					.filter(member -> member.getBandRole() == BandRole.FOUNDER || member.getBandRole() == BandRole.MANAGER)
					.filter(member -> member.getUser() != null && member.getUser().getId() != null)
					.forEach(member -> representatives.add(member.getUser().getId()));
		} else if (request.getMusicianProfile() != null && request.getMusicianProfile().getUser() != null) {
			representatives.add(request.getMusicianProfile().getUser().getId());
		}
		representatives.remove(null);
		if (ownerId == null || actorUserId == null || representatives.isEmpty()) {
			throw new SoundConnectException(ErrorType.REQUEST_PARTICIPANT_UNAVAILABLE);
		}
		Set<UUID> accountIds = new HashSet<>(representatives);
		accountIds.add(ownerId);
		accountIds.add(actorUserId);
		Set<UUID> usable = new HashSet<>(repository.lockUsableAccountIds(accountIds));
		if (!usable.contains(ownerId) || !usable.contains(actorUserId)
				|| representatives.stream().noneMatch(usable::contains)) {
			throw new SoundConnectException(ErrorType.REQUEST_PARTICIPANT_UNAVAILABLE);
		}
	}

	private boolean hasRequestWithStatus(ArtistVenueConnectionRequest request, RequestStatus status) {
		UUID venueId = request.getVenue().getId();
		return targetsBand(request)
				? repository.existsByBandIdAndVenueIdAndStatus(request.getBand().getId(), venueId, status)
				: repository.existsByMusicianProfileIdAndVenueIdAndStatus(request.getMusicianProfile().getId(), venueId, status);
	}

	private void assertCanCancel(UUID actorUserId, ArtistVenueConnectionRequest request) {
		if (request.getRequestByType() == RequestByType.VENUE) {
			assertCanActForVenue(actorUserId, request.getVenue());
			return;
		}
		assertCanActForApplicant(actorUserId, request);
	}

	private void assertCanDecide(UUID actorUserId, ArtistVenueConnectionRequest request) {
		if (request.getRequestByType() == RequestByType.VENUE) {
			assertCanActForApplicant(actorUserId, request);
			return;
		}
		assertCanActForVenue(actorUserId, request.getVenue());
	}

	private void assertCanDisconnect(UUID actorUserId, ArtistVenueConnectionRequest request) {
		if (canActForVenue(actorUserId, request.getVenue()) || canActForApplicant(actorUserId, request)) {
			return;
		}
		throw new SoundConnectException(ErrorType.FORBIDDEN_ACCESS);
	}

	private void assertCanActForApplicant(UUID actorUserId, ArtistVenueConnectionRequest request) {
		if (targetsBand(request)) {
			assertCanManageBand(actorUserId, request.getBand());
			return;
		}
		assertCanActForMusician(actorUserId, request.getMusicianProfile());
	}

	private boolean canActForApplicant(UUID actorUserId, ArtistVenueConnectionRequest request) {
		if (targetsBand(request)) {
			return canManageBand(actorUserId, request.getBand());
		}
		return canActForMusician(actorUserId, request.getMusicianProfile());
	}

	private void assertCanActForMusician(UUID actorUserId, MusicianProfile musician) {
		if (!canActForMusician(actorUserId, musician)) {
			throw new SoundConnectException(ErrorType.FORBIDDEN_ACCESS);
		}
	}

	private boolean canActForMusician(UUID actorUserId, MusicianProfile musician) {
		return musician != null
				&& musician.getUser() != null
				&& musician.getUser().getId().equals(actorUserId);
	}

	private void assertCanActForVenue(UUID actorUserId, Venue venue) {
		if (!canActForVenue(actorUserId, venue)) {
			throw new SoundConnectException(ErrorType.FORBIDDEN_ACCESS);
		}
	}

	private boolean canActForVenue(UUID actorUserId, Venue venue) {
		return venue != null
				&& venue.getOwner() != null
				&& venue.getOwner().getId().equals(actorUserId);
	}

	private void assertIsActiveBandMember(UUID actorUserId, Band band) {
		if (!isActiveBandMember(actorUserId, band)) {
			throw new SoundConnectException(ErrorType.FORBIDDEN_ACCESS);
		}
	}

	private boolean isActiveBandMember(UUID actorUserId, Band band) {
		return band != null && band.getMembers().stream()
		                          .anyMatch(member -> member.getUser() != null
				                          && member.getUser().getId().equals(actorUserId)
				                          && member.getStatus() == BandMemberShipStatus.ACTIVE);
	}

	private void assertCanManageBand(UUID actorUserId, Band band) {
		if (!canManageBand(actorUserId, band)) {
			throw new SoundConnectException(ErrorType.FORBIDDEN_ACCESS);
		}
	}

	private boolean canManageBand(UUID actorUserId, Band band) {
		return band != null && band.getMembers().stream()
		                          .anyMatch(member -> member.getUser() != null
				                          && member.getUser().getId().equals(actorUserId)
				                          && member.getStatus() == BandMemberShipStatus.ACTIVE
				                          && (member.getBandRole() == BandRole.FOUNDER || member.getBandRole() == BandRole.MANAGER));
	}

	private ArtistVenueConnectionRequestResponseDto enrichBandFields(ArtistVenueConnectionRequestResponseDto dto) {
		String bandPpUrl = dto.bandProfilePictureUrl();
		if (dto.bandId() != null) {
			try {
				Band band = bandRepository.findById(dto.bandId()).orElse(null);
				if (band != null && band.getProfilePictureMediaId() != null) {
					bandPpUrl = mediaAssetService.getDisplayUrl(band.getProfilePictureMediaId());
				}
			} catch (Exception ignored) {
			}
		}

		String venuePpUrl = dto.venueProfilePictureUrl();
		if (dto.venueId() != null) {
			venuePpUrl = venueProfilePictureUrl(dto.venueId());
		}

		return withAvatars(dto, bandPpUrl, venuePpUrl);
	}

	private ArtistVenueConnectionRequestResponseDto withAvatars(ArtistVenueConnectionRequestResponseDto dto, String bandPpUrl, String venuePpUrl) {
		return new ArtistVenueConnectionRequestResponseDto(
				dto.id(),
				dto.musicianProfileId(),
				dto.bandId(),
				dto.venueId(),
				dto.musicianStageName(),
				dto.bandName(),
				bandPpUrl,
				venuePpUrl,
				dto.venueName(),
				dto.message(),
				dto.status(),
				dto.requestByType(),
				dto.createdAt()
		);
	}

	private List<ArtistVenueConnectionRequestResponseDto> mapRequests(List<ArtistVenueConnectionRequest> requests) {
		AvatarUrls avatars = resolveAvatars(requests, false);
		return requests.stream().map(artistVenueConnectionRequestMapper::toResponseDto)
				.map(row -> withAvatars(row, avatars.bands().get(row.bandId()), avatars.venues().get(row.venueId())))
				.toList();
	}

	private PageResponse<ArtistVenueConnectionRequestPageItemDto> mapPage(Page<ConnectionRequestRow> page) {
		Set<UUID> mediaIds = new HashSet<>();
		page.forEach(row -> {
			mediaIds.add(row.musicianMediaId());
			mediaIds.add(row.bandMediaId());
			mediaIds.add(row.venueMediaId());
		});
		mediaIds.remove(null);
		Map<UUID, String> urls = mediaIds.isEmpty() ? new HashMap<>() : new HashMap<>(mediaAssetService.getDisplayUrlMap(List.copyOf(mediaIds)));
		List<ArtistVenueConnectionRequestPageItemDto> rows = new ArrayList<>(page.getNumberOfElements());
		for (ConnectionRequestRow row : page.getContent()) {
			String displayName = row.musicianProfileId() == null ? null
					: hasText(row.musicianStageName()) ? row.musicianStageName().trim() : row.musicianUsername();
			rows.add(new ArtistVenueConnectionRequestPageItemDto(row.id(), row.musicianProfileId(), row.bandId(), row.venueId(),
					row.musicianStageName(), row.bandName(), urls.get(row.bandMediaId()), urls.get(row.venueMediaId()),
					row.venueName(), row.message(), row.status().name(), row.requestByType(),
					row.createdAt() == null ? null : row.createdAt().toString(),
					urls.get(row.musicianMediaId()), row.musicianUsername(), displayName));
		}
		return PageResponse.from(new PageImpl<>(rows, page.getPageable(), page.getTotalElements()));
	}

	private AvatarUrls resolveAvatars(List<ArtistVenueConnectionRequest> requests, boolean includeMusicians) {
		Map<UUID, UUID> bandMedia = new HashMap<>(), venueMedia = new HashMap<>(), musicianMedia = new HashMap<>();
		Set<UUID> venueIds = new HashSet<>();
		for (ArtistVenueConnectionRequest request : requests) {
			if (request.getVenue() != null) venueIds.add(request.getVenue().getId());
			if (request.getBand() != null) bandMedia.put(request.getBand().getId(), request.getBand().getProfilePictureMediaId());
			if (includeMusicians && request.getMusicianProfile() != null) {
				musicianMedia.put(request.getMusicianProfile().getId(), request.getMusicianProfile().getProfilePictureMediaId());
			}
		}
		if (!venueIds.isEmpty()) {
			repository.findVenueAvatars(venueIds).forEach(avatar -> venueMedia.put(avatar.venueId(), avatar.mediaAssetId()));
		}
		Set<UUID> mediaIds = new HashSet<>(bandMedia.values());
		mediaIds.addAll(venueMedia.values());
		mediaIds.addAll(musicianMedia.values());
		mediaIds.remove(null);
		Map<UUID, String> urls = mediaIds.isEmpty() ? Map.of() : mediaAssetService.getDisplayUrlMap(List.copyOf(mediaIds));
		return new AvatarUrls(resolveUrls(bandMedia, urls), resolveUrls(venueMedia, urls), resolveUrls(musicianMedia, urls));
	}

	private Map<UUID, String> resolveUrls(Map<UUID, UUID> profileMedia, Map<UUID, String> urls) {
		Map<UUID, String> result = new HashMap<>();
		profileMedia.forEach((id, mediaId) -> {
			if (mediaId != null && urls.containsKey(mediaId)) result.put(id, urls.get(mediaId));
		});
		return result;
	}

	private record AvatarUrls(Map<UUID, String> bands, Map<UUID, String> venues, Map<UUID, String> musicians) {}

	private void publishRequestCreatedNotification(ArtistVenueConnectionRequest request) {
		for (UUID recipientId : requestCreatedRecipientIds(request)) {
			transactionalNotificationService.persistInCurrentTransaction(
					NotificationInboundEvent.builder()
					                        .eventId(UUID.randomUUID())
					                        .recipientId(recipientId)
					                        .type(NotificationType.ARTIST_VENUE_LINK_APPLICATION_REQUEST)
					                        .title(notificationTitle(requestCreatedTitle(request)))
					                        .message(safe(request.getMessage()))
					                        .payload(notificationPayload(request, "REQUEST_CREATED"))
					                        .emailForce(false)
					                        .occurredAt(Instant.now())
					                        .build()
			);
		}
	}

	private void publishRequestDecisionNotification(ArtistVenueConnectionRequest request, boolean accepted) {
		NotificationType type = accepted
				? NotificationType.ARTIST_VENUE_LINK_APPLICATION_ACCEPT
				: NotificationType.ARTIST_VENUE_LINK_APPLICATION_REJECT;
		String action = accepted ? "REQUEST_ACCEPTED" : "REQUEST_REJECTED";
		String title = accepted ? requestAcceptedTitle(request) : requestRejectedTitle(request);

		for (UUID recipientId : requestDecisionRecipientIds(request)) {
			transactionalNotificationService.persistInCurrentTransaction(
					NotificationInboundEvent.builder()
					                        .eventId(UUID.randomUUID())
					                        .recipientId(recipientId)
					                        .type(type)
					                        .title(notificationTitle(title))
					                        .message(safe(request.getMessage()))
					                        .payload(notificationPayload(request, action))
					                        .emailForce(false)
					                        .occurredAt(Instant.now())
					                        .build()
			);
		}
	}

	private String notificationTitle(String title) {
		return title.length() <= 160 ? title : title.substring(0, 157) + "...";
	}

	private List<UUID> requestCreatedRecipientIds(ArtistVenueConnectionRequest request) {
		if (request.getRequestByType() == RequestByType.VENUE) {
			if (targetsBand(request)) {
				return activeBandMemberUserIds(request.getBand());
			}
			UUID musicianUserId = request.getMusicianProfile() == null || request.getMusicianProfile().getUser() == null
					? null
					: request.getMusicianProfile().getUser().getId();
			return musicianUserId == null ? List.of() : List.of(musicianUserId);
		}
		UUID venueOwnerId = request.getVenue() == null || request.getVenue().getOwner() == null
				? null
				: request.getVenue().getOwner().getId();
		return venueOwnerId == null ? List.of() : List.of(venueOwnerId);
	}

	private List<UUID> requestDecisionRecipientIds(ArtistVenueConnectionRequest request) {
		if (request.getRequestByType() == RequestByType.VENUE) {
			UUID ownerId = request.getVenue() == null || request.getVenue().getOwner() == null
					? null
					: request.getVenue().getOwner().getId();
			return ownerId == null ? List.of() : List.of(ownerId);
		}
		if (targetsBand(request)) {
			return activeBandMemberUserIds(request.getBand());
		}
		UUID musicianUserId = request.getMusicianProfile() == null || request.getMusicianProfile().getUser() == null
				? null
				: request.getMusicianProfile().getUser().getId();
		return musicianUserId == null ? List.of() : List.of(musicianUserId);
	}

	private String requestCreatedTitle(ArtistVenueConnectionRequest request) {
		if (request.getRequestByType() == RequestByType.VENUE) {
			return displayVenueName(request) + " sana bağlantı isteği gönderdi";
		}
		if (request.getRequestByType() == RequestByType.ARTIST) {
			return displayArtistUsername(request) + " mekânına bağlantı isteği gönderdi";
		}
		return displayApplicantName(request) + " mekânına bağlantı isteği gönderdi";
	}

	private String requestAcceptedTitle(ArtistVenueConnectionRequest request) {
		if (request.getRequestByType() == RequestByType.VENUE) {
			return displayArtistUsername(request) + " bağlantı isteğini onayladı";
		}
		if (targetsBand(request)) {
			return displayVenueName(request) + " " + displayApplicantName(request)
					+ " adlı bandının bağlantı isteğini onayladı";
		}
		return displayVenueName(request) + " bağlantı isteğini onayladı";
	}

	private String requestRejectedTitle(ArtistVenueConnectionRequest request) {
		if (request.getRequestByType() == RequestByType.VENUE) {
			return displayArtistUsername(request) + " bağlantı isteğini reddetti";
		}
		if (targetsBand(request)) {
			return displayVenueName(request) + " " + displayApplicantName(request)
					+ " adlı bandının bağlantı isteğini reddetti";
		}
		return displayVenueName(request) + " bağlantı isteğini reddetti";
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
		String avatarUrl = notificationAvatarUrl(request, action);
		put(payload, "actorAvatarUrl", avatarUrl);
		put(payload, "avatarUrl", avatarUrl);
		if (("REQUEST_ACCEPTED".equals(action) || "REQUEST_REJECTED".equals(action))
				&& request.getRequestByType() == RequestByType.VENUE) {
			put(payload, "applicantAvatarUrl", avatarUrl);
			put(payload, "applicantProfilePictureUrl", avatarUrl);
		} else if ("REQUEST_ACCEPTED".equals(action)
				|| "REQUEST_REJECTED".equals(action)
				|| request.getRequestByType() == RequestByType.VENUE) {
			put(payload, "venueAvatarUrl", avatarUrl);
			put(payload, "venueProfilePictureUrl", avatarUrl);
		} else {
			put(payload, "applicantAvatarUrl", avatarUrl);
			put(payload, "applicantProfilePictureUrl", avatarUrl);
		}
		return payload;
	}

	private String notificationAvatarUrl(ArtistVenueConnectionRequest request, String action) {
		if ("REQUEST_ACCEPTED".equals(action) || "REQUEST_REJECTED".equals(action)) {
			if (request.getRequestByType() == RequestByType.VENUE) {
				return targetsBand(request)
						? profileMediaSourceUrl(request.getBand().getProfilePictureMediaId())
						: profileMediaSourceUrl(request.getMusicianProfile().getProfilePictureMediaId());
			}
			return venueProfilePictureUrl(request);
		}
		if (request.getRequestByType() == RequestByType.BAND && request.getBand() != null) {
			return profileMediaSourceUrl(request.getBand().getProfilePictureMediaId());
		}
		if (request.getRequestByType() == RequestByType.ARTIST && request.getMusicianProfile() != null) {
			return profileMediaSourceUrl(request.getMusicianProfile().getProfilePictureMediaId());
		}
		if (request.getRequestByType() == RequestByType.VENUE) {
			return venueProfilePictureUrl(request);
		}
		return null;
	}

	private String venueProfilePictureUrl(ArtistVenueConnectionRequest request) {
		if (request.getVenue() == null || request.getVenue().getId() == null) {
			return null;
		}
		return venueProfilePictureUrl(request.getVenue().getId());
	}

	private String venueProfilePictureUrl(UUID venueId) {
		if (venueId == null) {
			return null;
		}
		return venueProfileRepository.findByVenueId(venueId)
		                             .map(profile -> profileMediaSourceUrl(profile.getProfilePictureMediaId()))
		                             .orElse(null);
	}

	private String profileMediaSourceUrl(UUID mediaAssetId) {
		if (mediaAssetId == null) {
			return null;
		}
		try {
			String sourceUrl = mediaAssetService.getDisplayUrl(mediaAssetId);
			return hasText(sourceUrl) ? sourceUrl.trim() : null;
		} catch (Exception e) {
			log.debug("Profile image could not be resolved for notification. mediaAssetId={}, err={}",
			          mediaAssetId, e.toString());
			return null;
		}
	}

	private void put(Map<String, Object> payload, String key, Object value) {
		if (value != null) payload.put(key, value.toString());
	}

	private String displayApplicantName(ArtistVenueConnectionRequest request) {
		if (targetsBand(request)) {
			return safe(request.getBand().getName(), "Band");
		}
		if (request.getMusicianProfile() != null) {
			String stageName = request.getMusicianProfile().getStageName();
			if (hasText(stageName)) return stageName.trim();
			return safe(request.getMusicianProfile().getName(), "Sanatci");
		}
		return "Sanatci";
	}

	private String displayArtistUsername(ArtistVenueConnectionRequest request) {
		if (targetsBand(request)) {
			return displayApplicantName(request);
		}
		if (request.getMusicianProfile() != null && request.getMusicianProfile().getUser() != null) {
			return safe(request.getMusicianProfile().getUser().getUsername(), "Sanatci");
		}
		return "Sanatci";
	}

	private boolean targetsBand(ArtistVenueConnectionRequest request) {
		return request != null && request.getBand() != null;
	}

	private List<UUID> activeBandMemberUserIds(Band band) {
		if (band == null || band.getId() == null) return List.of();
		return bandMemberRepository.findByBandId(band.getId()).stream()
		                           .filter(member -> member.getStatus() == BandMemberShipStatus.ACTIVE)
		                           .map(BandMember::getUser)
		                           .filter(Objects::nonNull)
		                           .map(user -> user.getId())
		                           .filter(Objects::nonNull)
		                           .distinct()
		                           .toList();
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

package com.berkayb.soundconnect.modules.profile.MusicianProfile.band.service;

import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.repository.ArtistVenueConnectionRequestRepository;
import com.berkayb.soundconnect.modules.event.entity.Event;
import com.berkayb.soundconnect.modules.event.repository.EventRepository;
import com.berkayb.soundconnect.modules.follow.band.repository.BandFollowRepository;
import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.dto.request.BandCreateRequestDto;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.dto.response.BandResponseDto;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.dto.response.BandSearchItemDto; //eklendi
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity.Band;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity.BandMember;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.enums.BandMemberShipStatus;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.enums.BandRole;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.mapper.BandMapper;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.repository.BandMemberRepository;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.repository.BandRepository;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.support.BandEntityFinder;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.repository.MusicianProfileRepository;
import com.berkayb.soundconnect.modules.setlistcreator.repository.SetlistRepository;
import com.berkayb.soundconnect.modules.track.enums.TrackOwnerType;
import com.berkayb.soundconnect.modules.track.repository.TrackRepository;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.support.UserEntityFinder;
import com.berkayb.soundconnect.modules.venue.entity.Venue;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class BandServiceImpl implements BandService {
	private static final int MAX_ACTIVE_BANDS_PER_USER = 3;
	
	private final BandRepository bandRepository;
	private final BandMemberRepository bandMemberRepository;
	private final UserEntityFinder userEntityFinder;
	private final BandEntityFinder bandEntityFinder;
	private final BandMapper bandMapper;
	private final MusicianProfileRepository musicianProfileRepository;
	private final MediaAssetService mediaAssetService;
	private final NotificationProducer notificationProducer;
	private final BandFollowRepository bandFollowRepository;
	private final ArtistVenueConnectionRequestRepository artistVenueConnectionRequestRepository;
	private final SetlistRepository setlistRepository;
	private final EventRepository eventRepository;
	private final TrackRepository trackRepository;
	
	
	@Override
	public Band getBandEntity(UUID bandId) {
		return bandRepository.findById(bandId)
		                     .orElseThrow(() -> new SoundConnectException(ErrorType.BAND_NOT_FOUND));
	}
	
	
	@Override
	@Transactional
	public void inviteMember(UUID bandId, UUID inviterId, UUID invitedUserId, String message) {
		// bandi getir
		Band band = bandEntityFinder.getBand(bandId);
		
		// davet eden ve davet edilen user'lari getir
		User inviter = userEntityFinder.getUser(inviterId);
		User invited = userEntityFinder.getUser(invitedUserId);
		
		// davet eden kisinin yetkisi founder olmali
		BandMember inviterMember = bandMemberRepository.findByBandIdAndUserId(bandId, inviterId)
		                                               .orElseThrow(() -> new SoundConnectException(ErrorType.BAND_MEMBER_NOT_FOUND));
		
		if (inviterMember.getStatus() != BandMemberShipStatus.ACTIVE) {
			throw new SoundConnectException(ErrorType.BAND_MEMBER_NOT_ACTIVE);
		}
		
		if (inviterMember.getBandRole() != BandRole.FOUNDER) {
			log.warn("Kullanıcı yetkisiz davet girişimi: inviterId={}, bandId={}", inviterId, bandId);
			throw new SoundConnectException(ErrorType.BAND_INVITE_UNAUTHORIZED);
		}
		
		BandMember existingMember = bandMemberRepository.findByBandIdAndUserId(bandId, invitedUserId).orElse(null);
		
		if (existingMember != null) {
			if (existingMember.getStatus() == BandMemberShipStatus.ACTIVE || existingMember.getStatus() == BandMemberShipStatus.PENDING) {
				log.warn("Kullanici zaten bandde veya bekleyen daveti var: userId={}, bandId={}", invitedUserId, bandId);
				throw new SoundConnectException(ErrorType.BAND_MEMBER_ALREADY_EXISTS);
			}
			
			existingMember.setStatus(BandMemberShipStatus.PENDING);
			existingMember.setBandRole(BandRole.MEMBER);
			bandMemberRepository.save(existingMember);
			
			log.info("Band daveti yeniden gonderildi. inviter={}, invited={}, band={}", inviterId, invitedUserId, bandId);
			publishBandNotification(
					invited.getId(),
					NotificationType.BAND_INVITE_RECEIVED,
					safe(band.getName(), "Band") + " seni banda davet etti",
					safe(inviter.getUsername(), "Bir kullanıcı") + " tarafından band daveti aldın.",
					band,
					Map.of("action", "INVITE_RECEIVED", "inviterId", inviter.getId().toString())
			);
			return;
		}
		
		// BandMember olustur (pending)
		BandMember invite = BandMember.builder()
		                              .band(band)
		                              .user(invited)
		                              .bandRole(BandRole.MEMBER)
		                              .status(BandMemberShipStatus.PENDING)
		                              .build();
		
		bandMemberRepository.save(invite);
		
		log.info("Band daveti gönderildi. inviter={}, invited={}, band={}", inviterId, invitedUserId, bandId);
		
		publishBandNotification(
				invited.getId(),
				NotificationType.BAND_INVITE_RECEIVED,
				safe(band.getName(), "Band") + " seni banda davet etti",
				safe(inviter.getUsername(), "Bir kullanıcı") + " tarafından band daveti aldın.",
				band,
				Map.of("action", "INVITE_RECEIVED", "inviterId", inviter.getId().toString())
		);
	}
	
	@Override
	@Transactional
	public void acceptInvite(UUID bandId, UUID userId) {
		BandMember member = bandEntityFinder.getBandMember(bandId, userId);
		if (member.getStatus() != BandMemberShipStatus.PENDING) {
			throw new SoundConnectException(ErrorType.BAND_INVITE_STATUS_INVALID);
		}
		member.setStatus(BandMemberShipStatus.ACTIVE);
		bandMemberRepository.save(member);
		
		log.info("Band daveti kabul edildi. userId={}, bandId={}", userId, bandId);
		notifyActiveFounders(
				member.getBand(),
				userId,
				NotificationType.BAND_INVITE_ACCEPTED,
				safe(member.getUser().getUsername(), "Bir kullanıcı") + " band davetini kabul etti",
				safe(member.getBand().getName(), "Band") + " için gönderilen davet kabul edildi.",
				Map.of("action", "INVITE_ACCEPTED", "memberId", userId.toString())
		);
	}
	
	@Override
	@Transactional
	public void rejectInvite(UUID bandId, UUID userId) {
		BandMember member = bandEntityFinder.getBandMember(bandId, userId);
		if (member.getStatus() != BandMemberShipStatus.PENDING) {
			throw new SoundConnectException(ErrorType.BAND_INVITE_STATUS_INVALID);
		}
		member.setStatus(BandMemberShipStatus.REJECTED);
		bandMemberRepository.save(member);
		
		log.info("Band daveti reddedildi. userId={}, bandId={}", userId, bandId);
		notifyActiveFounders(
				member.getBand(),
				userId,
				NotificationType.BAND_INVITE_REJECTED,
				safe(member.getUser().getUsername(), "Bir kullanıcı") + " band davetini reddetti",
				safe(member.getBand().getName(), "Band") + " için gönderilen davet reddedildi.",
				Map.of("action", "INVITE_REJECTED", "memberId", userId.toString())
		);
	}
	
	@Override
	@Transactional
	public void removeMember(UUID bandId, UUID requesterId, UUID targetUserId) {
		// sadece founder uyeleri cikarabilir
		BandMember requester = bandMemberRepository.findByBandIdAndUserId(bandId, requesterId)
		                                           .orElseThrow(() -> new SoundConnectException(ErrorType.BAND_MEMBER_NOT_FOUND));
		
		if (requester.getStatus() != BandMemberShipStatus.ACTIVE) {
			throw new SoundConnectException(ErrorType.BAND_MEMBER_NOT_ACTIVE);
		}
		
		if (requester.getBandRole() != BandRole.FOUNDER) {
			log.warn("Yetkisiz cikarma girisimi: requesterId={}, bandId={}", requesterId, bandId);
			throw new SoundConnectException(ErrorType.BAND_REMOVE_UNAUTHORIZED);
		}
		
		BandMember member = bandMemberRepository.findByBandIdAndUserId(bandId, targetUserId)
		                                        .orElseThrow(() -> new SoundConnectException(ErrorType.BAND_MEMBER_NOT_FOUND));
		
		// founder baska founderi cikaramasin
		if (member.getBandRole() == BandRole.FOUNDER) {
			throw new SoundConnectException(ErrorType.BAND_CANNOT_REMOVE_FOUNDER);
		}
		
		member.setStatus(BandMemberShipStatus.LEFT);
		bandMemberRepository.save(member);
		
		log.info("Band üyesi çıkarıldı. memberId={}, bandId={}", targetUserId, bandId);
		publishBandNotification(
				targetUserId,
				NotificationType.BAND_MEMBER_REMOVED,
				safe(member.getBand().getName(), "Band") + " bandından çıkarıldın",
				"Band üyeliğin sonlandırıldı.",
				member.getBand(),
				Map.of("action", "MEMBER_REMOVED", "requesterId", requesterId.toString())
		);
	}
	
	@Override
	@Transactional
	public void leaveBand(UUID bandId, UUID userId) {
		BandMember member = bandEntityFinder.getBandMember(bandId, userId);
		
		if (member.getStatus() != BandMemberShipStatus.ACTIVE) {
			throw new SoundConnectException(ErrorType.BAND_MEMBER_NOT_ACTIVE);
		}
		
		// founder ise cikamasin once baska founder atanmasi gerek
		if (member.getBandRole() == BandRole.FOUNDER) {
			throw new SoundConnectException(ErrorType.BAND_FOUNDER_CANNOT_LEAVE);
		}
		
		member.setStatus(BandMemberShipStatus.LEFT);
		bandMemberRepository.save(member);
		
		notifyActiveFounders(
				member.getBand(),
				userId,
				NotificationType.BAND_MEMBER_LEFT,
				safe(member.getUser().getUsername(), "Bir kullanıcı") + " banddan ayrıldı",
				safe(member.getBand().getName(), "Band") + " üyelerinden biri ayrıldı.",
				Map.of("action", "MEMBER_LEFT", "memberId", userId.toString())
		);
	}

	@Override
	@Transactional
	public void deleteBand(UUID bandId, UUID userId) {
		Band band = bandEntityFinder.getBand(bandId);
		BandMember requester = bandMemberRepository.findByBandIdAndUserId(bandId, userId)
		                                           .orElseThrow(() -> new SoundConnectException(ErrorType.BAND_MEMBER_NOT_FOUND));

		if (requester.getStatus() != BandMemberShipStatus.ACTIVE) {
			throw new SoundConnectException(ErrorType.BAND_MEMBER_NOT_ACTIVE);
		}

		if (requester.getBandRole() != BandRole.FOUNDER) {
			log.warn("Yetkisiz band silme girisimi: requesterId={}, bandId={}", userId, bandId);
			throw new SoundConnectException(ErrorType.BAND_REMOVE_UNAUTHORIZED);
		}

		String deletedBandName = safe(band.getName(), "Band");

		for (Venue venue : new HashSet<>(band.getActiveVenues())) {
			venue.getActiveBands().remove(band);
		}
		band.getActiveVenues().clear();

		for (Event event : eventRepository.findAllByBand_Id(bandId)) {
			if (event.getManualPerformerName() == null || event.getManualPerformerName().isBlank()) {
				event.setManualPerformerName(deletedBandName);
			}
			event.setBand(null);
		}

		artistVenueConnectionRequestRepository.deleteAllByBandId(bandId);
		setlistRepository.deleteAllByBand_Id(bandId);
		trackRepository.deleteAllByOwnerIdAndOwnerType(bandId, TrackOwnerType.BAND);
		bandFollowRepository.deleteAllByBand(band);
		bandRepository.delete(band);

		log.info("Band silindi. bandId={}, requesterId={}", bandId, userId);
	}
	
	// band olusturur. olusturan kullanici otomatik olarak founder ve active statusunde uye olur
	@Override
	@Transactional
	public BandResponseDto createBand(UUID userId, BandCreateRequestDto dto) {
		// kullaniciyi getir
		User user = userEntityFinder.getUser(userId);
		
		// band sadece musician profile sahibi kullanici tarafindan olusturulabilir
		if (musicianProfileRepository.findByUserId(userId).isEmpty()) {
			log.warn("Band olusturmak icin musician profile bulunamadi. userId={}", userId);
			throw new SoundConnectException(ErrorType.PROFILE_NOT_FOUND);
		}

		long activeBandCount = bandMemberRepository.countByUserIdAndStatus(userId, BandMemberShipStatus.ACTIVE);
		if (activeBandCount >= MAX_ACTIVE_BANDS_PER_USER) {
			log.warn("Band olusturma limiti asildi. userId={}, activeBandCount={}", userId, activeBandCount);
			throw new SoundConnectException(ErrorType.BAND_CREATE_LIMIT_EXCEEDED);
		}
		
		// band adi daha once kullanilmis mi?
		bandRepository.findByName(dto.name()).ifPresent(existing -> {
			log.warn("Band adi daha once kullanilmis. Band: {}", dto.name());
			throw new SoundConnectException(ErrorType.BAND_ALREADY_EXISTS);
		});
		
		// yeni band entitsini kur
		Band band = Band.builder()
		                .name(dto.name())
		                .description(dto.description())
		                .profilePictureMediaId(dto.profilePicture())
		                .instagramUrl(dto.instagramUrl())
		                .youtubeUrl(dto.youtubeUrl())
		                .soundCloudUrl(dto.soundCloudUrl())
		                .spotifyEmbedUrl(dto.spotifyEmbedUrl())
		                .spotifyArtistId(dto.spotifyArtistId())
		                .spotifyTrackIds(dto.spotifyTrackIds() != null ? dto.spotifyTrackIds() : List.of())
		                .build();
		
		// band founder'ii olarak ilk uyeyi ekle
		BandMember founderMember = BandMember.builder()
		                                     .band(band)
		                                     .user(user)
		                                     .bandRole(BandRole.FOUNDER)
		                                     .status(BandMemberShipStatus.ACTIVE)
		                                     .build();
		
		// band ve uyelik iliskisini kur
		band.setMembers(new HashSet<>(List.of(founderMember)));
		
		// kaydet
		Band saved = bandRepository.save(band);
		log.info("Yeni band olusturuldu. [name: {}, founder: {}]", dto.name(), user.getId());
		
		// response DTO ile dondur
		return toResponseDto(saved);
	}
	
	@Override
	@Transactional
	public BandResponseDto updateBand(UUID bandId, UUID userId, BandCreateRequestDto dto) {
		Band band = bandEntityFinder.getBand(bandId);
		BandMember requester = bandMemberRepository.findByBandIdAndUserId(bandId, userId)
		                                           .orElseThrow(() -> new SoundConnectException(ErrorType.BAND_MEMBER_NOT_FOUND));
		
		if (requester.getStatus() != BandMemberShipStatus.ACTIVE) {
			throw new SoundConnectException(ErrorType.BAND_MEMBER_NOT_ACTIVE);
		}
		
		if (requester.getBandRole() != BandRole.FOUNDER) {
			throw new SoundConnectException(ErrorType.BAND_INVITE_UNAUTHORIZED);
		}
		
		if (dto.name() != null && !dto.name().equalsIgnoreCase(band.getName())) {
			bandRepository.findByName(dto.name()).ifPresent(existing -> {
				if (!existing.getId().equals(bandId)) {
					log.warn("Band adi daha once kullanilmis. Band: {}", dto.name());
					throw new SoundConnectException(ErrorType.BAND_ALREADY_EXISTS);
				}
			});
			band.setName(dto.name());
		}
		
		if (dto.description() != null) band.setDescription(dto.description());
		if (dto.profilePicture() != null) band.setProfilePictureMediaId(dto.profilePicture());
		if (dto.instagramUrl() != null) band.setInstagramUrl(dto.instagramUrl());
		if (dto.youtubeUrl() != null) band.setYoutubeUrl(dto.youtubeUrl());
		if (dto.soundCloudUrl() != null) band.setSoundCloudUrl(dto.soundCloudUrl());
		if (dto.spotifyEmbedUrl() != null) band.setSpotifyEmbedUrl(dto.spotifyEmbedUrl());
		if (dto.spotifyArtistId() != null) band.setSpotifyArtistId(dto.spotifyArtistId());
		if (dto.spotifyTrackIds() != null) band.setSpotifyTrackIds(dto.spotifyTrackIds());
		
		Band updated = bandRepository.save(band);
		log.info("Band guncellendi. bandId={}, userId={}", bandId, userId);
		
		return toResponseDto(updated);
	}
	
	@Override
	@Transactional(readOnly = true)
	public List<BandResponseDto> getBandsByUser(UUID userId) {
		List<BandMember> memberships = bandMemberRepository.findByUserIdAndStatus(
				userId,
				BandMemberShipStatus.ACTIVE
		);
		
		return memberships.stream()
		                  .map(BandMember::getBand)
		                  .map(this::toResponseDto)
		                  .toList();
	}
	
	@Override
	@Transactional(readOnly = true)
	public BandResponseDto getBandById(UUID bandId, UUID userId) {
		// kullanici band uyesi mi?
		BandMember member = bandMemberRepository.findByBandIdAndUserId(bandId, userId)
		                                        .orElseThrow(() -> new SoundConnectException(ErrorType.BAND_MEMBER_NOT_FOUND));
		if (member.getStatus() != BandMemberShipStatus.ACTIVE) {
			throw new SoundConnectException(ErrorType.BAND_MEMBER_NOT_ACTIVE);
		}
		// bandi getir ve dto yap
		Band band = member.getBand();
		return toResponseDto(band);
	}
	
	@Override //eklendi
	@Transactional(readOnly = true) //eklendi
	public BandResponseDto getPublicBandById(UUID bandId) { //eklendi
		Band band = bandEntityFinder.getBand(bandId); //eklendi
		return toResponseDto(band); //eklendi
	} //eklendi
	
	@Override //eklendi
	@Transactional(readOnly = true) //eklendi
	public List<BandSearchItemDto> searchBands(String query) { //eklendi
		String q = query == null ? "" : query.trim(); //eklendi
		if (q.isEmpty()) return List.of(); //eklendi
		
		return bandRepository.searchByName(q) //eklendi
		                     .stream() //eklendi
		                     .limit(10) //eklendi
		                     .map(band -> new BandSearchItemDto( //eklendi
		                                                         band.getId(), //eklendi
		                                                         band.getName(), //eklendi
		                                                         resolveProfilePictureUrl(band.getProfilePictureMediaId()) //eklendi
		                     )) //eklendi
		                     .toList(); //eklendi
	} //eklendi
	
	private void notifyActiveFounders(
			Band band,
			UUID actorId,
			NotificationType type,
			String title,
			String message,
			Map<String, Object> extraPayload
	) {
		band.getMembers().stream()
		    .filter(member -> member.getStatus() == BandMemberShipStatus.ACTIVE)
		    .filter(member -> member.getBandRole() == BandRole.FOUNDER)
		    .map(BandMember::getUser)
		    .filter(user -> user != null && user.getId() != null && !user.getId().equals(actorId))
		    .forEach(user -> publishBandNotification(user.getId(), type, title, message, band, extraPayload));
	}
	
	private void publishBandNotification(
			UUID recipientId,
			NotificationType type,
			String title,
			String message,
			Band band,
			Map<String, Object> extraPayload
	) {
		try {
			Map<String, Object> payload = new HashMap<>();
			payload.put("module", "BAND");
			payload.put("bandId", band.getId().toString());
			payload.put("bandName", safe(band.getName(), "Band"));
			if (extraPayload != null) payload.putAll(extraPayload);
			
			notificationProducer.publish(
					NotificationInboundEvent.builder()
					                        .recipientId(recipientId)
					                        .type(type)
					                        .title(title)
					                        .message(message)
					                        .payload(payload)
					                        .emailForce(false)
					                        .occurredAt(Instant.now())
					                        .build()
			);
		} catch (Exception e) {
			log.warn("Band notification publish failed. recipient={}, band={}, type={}, err={}",
			         recipientId, band.getId(), type, e.toString());
		}
	}
	
	private String safe(String value, String fallback) {
		return value == null || value.isBlank() ? fallback : value.trim();
	}
	
	private BandResponseDto toResponseDto(Band band) {
		var base = bandMapper.toDto(band);
		String profilePictureUrl = resolveProfilePictureUrl(band.getProfilePictureMediaId());
		
		return new BandResponseDto(
				base.id(),
				base.name(),
				base.description(),
				base.profilePictureMediaId(),
				profilePictureUrl,
				base.instagramUrl(),
				base.youtubeUrl(),
				base.soundCloudUrl(),
				base.spotifyEmbedUrl(),
				base.spotifyArtistId(),
				base.spotifyTrackIds(),
				base.members()
		);
	}
	
	private String resolveProfilePictureUrl(UUID mediaAssetId) {
		if (mediaAssetId == null) return null;
		try {
			return mediaAssetService.getById(mediaAssetId).getSourceUrl();
		} catch (Exception e) {
			log.warn("Band profile picture resolve failed. mediaAssetId={}", mediaAssetId);
			return null;
		}
	}
}

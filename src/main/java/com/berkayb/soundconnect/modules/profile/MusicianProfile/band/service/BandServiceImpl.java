package com.berkayb.soundconnect.modules.profile.MusicianProfile.band.service;

import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.repository.ArtistVenueConnectionRequestRepository;
import com.berkayb.soundconnect.modules.event.entity.Event;
import com.berkayb.soundconnect.modules.event.repository.EventRepository;
import com.berkayb.soundconnect.modules.event.enums.EventPerformerApprovalStatus;
import com.berkayb.soundconnect.modules.event.performer.service.EventPerformerRequestService;
import com.berkayb.soundconnect.modules.event.publication.EventMemberPublicationRepository;
import com.berkayb.soundconnect.modules.follow.band.repository.BandFollowRepository;
import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.media.enums.MediaKind;
import com.berkayb.soundconnect.modules.media.enums.MediaOwnerType;
import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.modules.notification.service.TransactionalNotificationService;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.dto.request.BandCreateRequestDto;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.dto.request.BandMemberTitleUpdateDto;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.dto.response.BandMemberResponseDto;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.dto.response.BandPendingInvitationResponseDto;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.dto.response.BandReceivedInvitationResponseDto;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.repository.BandReceivedInvitationRow;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.dto.response.BandResponseDto;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.dto.response.BandSearchItemDto; //eklendi
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity.Band;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity.BandMember;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.enums.BandMemberShipStatus;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.enums.BandRole;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.mapper.BandMapper;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.repository.BandMemberRepository;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.repository.BandPendingInvitationRow;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.repository.BandRepository;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.support.BandEntityFinder;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.support.BandMemberTitle;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.repository.MusicianProfileRepository;
import com.berkayb.soundconnect.modules.profile.shared.ProfileInputValidation;
import com.berkayb.soundconnect.shared.util.UsernameUtils;
import com.berkayb.soundconnect.modules.setlistcreator.repository.SetlistRepository;
import com.berkayb.soundconnect.modules.track.enums.TrackOwnerType;
import com.berkayb.soundconnect.modules.track.repository.TrackRepository;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.berkayb.soundconnect.modules.user.support.UserEntityFinder;
import com.berkayb.soundconnect.modules.venue.entity.Venue;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.berkayb.soundconnect.shared.response.PageResponse;
import com.berkayb.soundconnect.shared.messaging.events.notification.NotificationInboundEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class BandServiceImpl implements BandService {
	private static final int MAX_FOUNDED_BANDS_PER_USER = 3;
	private static final int MAX_PENDING_INVITATION_PAGE = 10_000;
	private static final int MAX_PENDING_INVITATION_SIZE = 50;
	private static final long MAX_PENDING_INVITATION_OFFSET = 100_000L;
	
	private final BandRepository bandRepository;
	private final BandMemberRepository bandMemberRepository;
	private final UserEntityFinder userEntityFinder;
	private final BandEntityFinder bandEntityFinder;
	private final BandMapper bandMapper;
	private final MusicianProfileRepository musicianProfileRepository;
	private final MediaAssetService mediaAssetService;
	private final TransactionalNotificationService transactionalNotificationService;
	private final BandFollowRepository bandFollowRepository;
	private final ArtistVenueConnectionRequestRepository artistVenueConnectionRequestRepository;
	private final SetlistRepository setlistRepository;
	private final EventRepository eventRepository;
	private final TrackRepository trackRepository;
	private final EventPerformerRequestService eventPerformerRequestService;
	private final EventMemberPublicationRepository eventMemberPublicationRepository;
	private final UserRepository userRepository;
	
	
	@Override
	public Band getBandEntity(UUID bandId) {
		return bandRepository.findById(bandId)
		                     .orElseThrow(() -> new SoundConnectException(ErrorType.BAND_NOT_FOUND));
	}
	
	
	@Override
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public PageResponse<BandReceivedInvitationResponseDto> getReceivedInvitations(UUID requesterId, int page, int size) {
		if (page < 0 || page > MAX_PENDING_INVITATION_PAGE || size < 1 ||
				size > MAX_PENDING_INVITATION_SIZE || (long) page * size > MAX_PENDING_INVITATION_OFFSET) {
			throw new SoundConnectException(ErrorType.BAND_PENDING_INVITATIONS_PAGE_INVALID);
		}
		assertCanReadReceivedInvitations(requesterId);
		var invitations = bandMemberRepository.findReceivedInvitationSummaries(
				requesterId, BandMemberShipStatus.PENDING, PageRequest.of(page, size));
		var mediaIds = invitations.getContent().stream().map(BandReceivedInvitationRow::profilePictureMediaId)
				.filter(Objects::nonNull).distinct().toList();
		Map<UUID, String> urls = mediaIds.isEmpty() ? Map.of() : mediaAssetService.getDisplayUrlMap(mediaIds);
		return PageResponse.from(invitations.map(row -> new BandReceivedInvitationResponseDto(
				row.bandId(), row.bandName(), row.profilePictureMediaId() == null ? null : urls.get(row.profilePictureMediaId()),
				BandMemberShipStatus.PENDING.name(), row.invitationId())));
	}

	@Override
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public BandReceivedInvitationResponseDto getCurrentReceivedInvitation(UUID bandId, UUID requesterId) {
		assertCanReadReceivedInvitations(requesterId);
		var row = bandMemberRepository.findCurrentReceivedInvitation(bandId, requesterId, BandMemberShipStatus.PENDING)
				.orElseThrow(() -> new SoundConnectException(ErrorType.BAND_INVITE_STATUS_INVALID));
		Map<UUID, String> urls = row.profilePictureMediaId() == null ? Map.of()
				: mediaAssetService.getDisplayUrlMap(List.of(row.profilePictureMediaId()));
		return new BandReceivedInvitationResponseDto(row.bandId(), row.bandName(),
				row.profilePictureMediaId() == null ? null : urls.get(row.profilePictureMediaId()),
				BandMemberShipStatus.PENDING.name(), row.invitationId());
	}

	private void assertCanReadReceivedInvitations(UUID requesterId) {
		User user = userEntityFinder.getUser(requesterId);
		if (user.getStatus() != UserStatus.ACTIVE || !Boolean.TRUE.equals(user.getEmailVerified()) ||
				user.getRoles() == null || user.getRoles().stream().filter(Objects::nonNull)
						.noneMatch(role -> "ROLE_MUSICIAN".equals(role.getName()))) {
			throw new SoundConnectException(ErrorType.BAND_RECEIVED_INVITATIONS_FORBIDDEN);
		}
	}

	@Override
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public PageResponse<BandPendingInvitationResponseDto> getPendingInvitations(
			UUID bandId, UUID requesterId, int page, int size) {
		if (page < 0 || page > MAX_PENDING_INVITATION_PAGE || size < 1 ||
				size > MAX_PENDING_INVITATION_SIZE || (long) page * size > MAX_PENDING_INVITATION_OFFSET) {
			throw new SoundConnectException(ErrorType.BAND_PENDING_INVITATIONS_PAGE_INVALID);
		}
		// The current database membership/account is authoritative, not a cached
		// founder badge or JWT claim. The same read snapshot covers gate and page.
		BandMember requester = bandMemberRepository.findByBandIdAndUserId(bandId, requesterId)
				.orElseThrow(() -> new SoundConnectException(ErrorType.BAND_PENDING_INVITATIONS_FORBIDDEN));
		User user = requester.getUser();
		if (requester.getStatus() != BandMemberShipStatus.ACTIVE ||
				requester.getBandRole() != BandRole.FOUNDER || user == null ||
				user.getStatus() != UserStatus.ACTIVE || !Boolean.TRUE.equals(user.getEmailVerified()) ||
				user.getRoles() == null || user.getRoles().stream().filter(Objects::nonNull)
						.noneMatch(role -> "ROLE_MUSICIAN".equals(role.getName()))) {
			throw new SoundConnectException(ErrorType.BAND_PENDING_INVITATIONS_FORBIDDEN);
		}
		var invitations = bandMemberRepository.findPendingInvitationSummaries(
				bandId, BandMemberShipStatus.PENDING, PageRequest.of(page, size));
		// Resolve only this page's canonical musician photos in one bounded batch.
		// Missing, private or unfinished media stays a placeholder, never a legacy avatar.
		List<UUID> mediaIds = invitations.getContent().stream()
				.map(BandPendingInvitationRow::profilePictureMediaId)
				.filter(Objects::nonNull)
				.distinct()
				.toList();
		Map<UUID, String> avatarUrls = mediaIds.isEmpty()
				? Map.of() : mediaAssetService.getDisplayUrlMap(mediaIds);
		return PageResponse.from(invitations.map(invitation -> new BandPendingInvitationResponseDto(
				invitation.userId(), invitation.username(),
				invitation.profilePictureMediaId() == null
						? null : avatarUrls.get(invitation.profilePictureMediaId()),
				BandMemberShipStatus.PENDING.name())));
	}

	@Override
	@Transactional
	public void inviteMember(UUID bandId, UUID inviterId, UUID invitedUserId, String message) {
		Band band = lockBandForMembershipChange(bandId);
		
		// Resolve the requester before accessing any recipient data.
		User inviter = userEntityFinder.getUser(inviterId);
		
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
		User invited = userEntityFinder.getUser(invitedUserId);
		// A band invitation must be answerable by its recipient. The decision
		// endpoints require an active, verified musician with a canonical profile.
		if (invited.getStatus() != UserStatus.ACTIVE || !Boolean.TRUE.equals(invited.getEmailVerified()) ||
				invited.getRoles() == null || invited.getRoles().stream().filter(Objects::nonNull)
						.noneMatch(role -> "ROLE_MUSICIAN".equals(role.getName())) ||
				musicianProfileRepository.findByUserId(invitedUserId).isEmpty()) {
			throw new SoundConnectException(ErrorType.PROFILE_NOT_FOUND);
		}
		
		BandMember existingMember = bandMemberRepository.findByBandIdAndUserId(bandId, invitedUserId).orElse(null);
		
		if (existingMember != null) {
			if (existingMember.getStatus() == BandMemberShipStatus.ACTIVE || existingMember.getStatus() == BandMemberShipStatus.PENDING) {
				log.warn("Kullanici zaten bandde veya bekleyen daveti var: userId={}, bandId={}", invitedUserId, bandId);
				throw new SoundConnectException(ErrorType.BAND_MEMBER_ALREADY_EXISTS);
			}
			
			// A new membership invitation cannot revive the previous membership's
			// event publication choices. Preserve version tombstones for old clients.
			eventMemberPublicationRepository.hideForBandMember(bandId, invitedUserId);
			clearMemberTitleForNewTenure(existingMember);
			existingMember.setStatus(BandMemberShipStatus.PENDING);
			existingMember.setInvitationId(UUID.randomUUID());
			existingMember.setBandRole(BandRole.MEMBER);
			bandMemberRepository.save(existingMember);
			
			log.info("Band daveti yeniden gonderildi. inviter={}, invited={}, band={}", inviterId, invitedUserId, bandId);
			publishBandNotification(
					invited.getId(),
					NotificationType.BAND_INVITE_RECEIVED,
					safe(band.getName(), "Grup") + " seni gruba davet etti",
					safe(inviter.getUsername(), "Bir kullanıcı") + " tarafından davet aldın.",
					band,
					Map.of("action", "INVITE_RECEIVED", "inviterId", inviter.getId().toString(),
							"invitationId", existingMember.getInvitationId().toString())
			);
			return;
		}
		
		// BandMember olustur (pending)
		BandMember invite = BandMember.builder()
		                              .band(band)
		                              .user(invited)
		                              .bandRole(BandRole.MEMBER)
		                              .status(BandMemberShipStatus.PENDING)
		                              .invitationId(UUID.randomUUID())
		                              .build();
		
		bandMemberRepository.save(invite);
		
		log.info("Band daveti gönderildi. inviter={}, invited={}, band={}", inviterId, invitedUserId, bandId);
		
		publishBandNotification(
				invited.getId(),
				NotificationType.BAND_INVITE_RECEIVED,
				safe(band.getName(), "Grup") + " seni gruba davet etti",
				safe(inviter.getUsername(), "Bir kullanıcı") + " tarafından davet aldın.",
				band,
				Map.of("action", "INVITE_RECEIVED", "inviterId", inviter.getId().toString(),
						"invitationId", invite.getInvitationId().toString())
		);
	}
	
	@Override
	@Transactional
	public void acceptInvite(UUID bandId, UUID userId, UUID invitationId) {
		lockBandForMembershipChange(bandId);
		BandMember member = bandEntityFinder.getBandMember(bandId, userId);
		if (member.getStatus() != BandMemberShipStatus.PENDING) {
			throw new SoundConnectException(ErrorType.BAND_INVITE_STATUS_INVALID);
		}
		assertCurrentInvitation(member, invitationId);
		// Explicitly reset publication before reactivating membership. This also
		// covers pending invitations created by an older application version.
		eventMemberPublicationRepository.hideForBandMember(bandId, userId);
		// Pending rows cannot be edited. Clear legacy pending titles as well,
		// without touching an active member's title on duplicate acceptance.
		clearMemberTitleForNewTenure(member);
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
	public void rejectInvite(UUID bandId, UUID userId, UUID invitationId) {
		lockBandForMembershipChange(bandId);
		BandMember member = bandEntityFinder.getBandMember(bandId, userId);
		if (member.getStatus() != BandMemberShipStatus.PENDING) {
			throw new SoundConnectException(ErrorType.BAND_INVITE_STATUS_INVALID);
		}
		assertCurrentInvitation(member, invitationId);
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
	
	private static void assertCurrentInvitation(BandMember member, UUID invitationId) {
		if (invitationId == null || !invitationId.equals(member.getInvitationId())) {
			throw new SoundConnectException(ErrorType.BAND_INVITE_STALE);
		}
	}

	@Override
	@Transactional
	public void removeMember(UUID bandId, UUID requesterId, UUID targetUserId, Long expectedTitleVersion) {
		lockBandForMembershipChange(bandId);
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
		// This version advances for title edits AND every invitation/acceptance
		// boundary, including legacy null titles. An old roster must never remove
		// a later membership; missing versions from older clients fail closed.
		if (expectedTitleVersion == null || expectedTitleVersion < 0 ||
				expectedTitleVersion != member.getTitleVersion()) {
			throw new SoundConnectException(ErrorType.BAND_MEMBER_VERSION_CONFLICT);
		}
		// Retried removals must not create another notification or advance the
		// publication tombstones for a membership that has already ended.
		if (member.getStatus() == BandMemberShipStatus.LEFT ||
				member.getStatus() == BandMemberShipStatus.REJECTED) {
			return;
		}
		
		eventMemberPublicationRepository.hideForBandMember(bandId, targetUserId);
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
	public BandMemberResponseDto updateMemberTitle(UUID bandId, UUID requesterId, UUID targetUserId,
	                                               BandMemberTitleUpdateDto update) {
		if (update == null || update.expectedTitleVersion() == null || update.expectedTitleVersion() < 0) {
			throw new SoundConnectException(ErrorType.BAND_MEMBER_TITLE_INVALID);
		}
		String normalizedTitle = BandMemberTitle.normalize(update.memberTitle());
		lockBandForMembershipChange(bandId);
		BandMember requester = bandMemberRepository.findByBandIdAndUserId(bandId, requesterId)
				.orElseThrow(() -> new SoundConnectException(ErrorType.BAND_MEMBER_NOT_FOUND));
		if (requester.getStatus() != BandMemberShipStatus.ACTIVE) {
			throw new SoundConnectException(ErrorType.BAND_MEMBER_NOT_ACTIVE);
		}
		if (requester.getBandRole() != BandRole.FOUNDER || requester.getUser() == null ||
				requester.getUser().getStatus() != UserStatus.ACTIVE ||
				!Boolean.TRUE.equals(requester.getUser().getEmailVerified())) {
			throw new SoundConnectException(ErrorType.BAND_MEMBER_TITLE_UNAUTHORIZED);
		}
		BandMember target = requesterId.equals(targetUserId) ? requester :
				bandMemberRepository.findByBandIdAndUserId(bandId, targetUserId)
						.orElseThrow(() -> new SoundConnectException(ErrorType.BAND_MEMBER_NOT_FOUND));
		if (target.getStatus() != BandMemberShipStatus.ACTIVE) {
			throw new SoundConnectException(ErrorType.BAND_MEMBER_NOT_ACTIVE);
		}
		if (target.getTitleVersion() != update.expectedTitleVersion()) {
			throw new SoundConnectException(ErrorType.BAND_MEMBER_TITLE_VERSION_CONFLICT);
		}
		if (!Objects.equals(target.getMemberTitle(), normalizedTitle)) {
			target.setTitleVersion(nextTitleVersion(target.getTitleVersion()));
			target.setMemberTitle(normalizedTitle);
			bandMemberRepository.save(target);
		}
		return bandMapper.toMemberDto(target);
	}

	private static long nextTitleVersion(long version) {
		if (version < 0 || version == Long.MAX_VALUE) {
			throw new SoundConnectException(ErrorType.BAND_MEMBER_TITLE_VERSION_CONFLICT);
		}
		return version + 1;
	}

	private static void clearMemberTitleForNewTenure(BandMember member) {
		// A null old title still has an optimistic version held by old editors.
		// Advance on every new-tenure boundary so those editors cannot publish
		// into a later membership. Duplicate ACTIVE acceptance is rejected above.
		member.setTitleVersion(nextTitleVersion(member.getTitleVersion()));
		member.setMemberTitle(null);
	}

	@Override
	@Transactional
	public void leaveBand(UUID bandId, UUID userId, Long expectedTitleVersion) {
		lockBandForMembershipChange(bandId);
		BandMember member = bandEntityFinder.getBandMember(bandId, userId);
		
		if (member.getStatus() != BandMemberShipStatus.ACTIVE) {
			throw new SoundConnectException(ErrorType.BAND_MEMBER_NOT_ACTIVE);
		}
		
		// founder ise cikamasin once baska founder atanmasi gerek
		if (member.getBandRole() == BandRole.FOUNDER) {
			throw new SoundConnectException(ErrorType.BAND_FOUNDER_CANNOT_LEAVE);
		}
		if (expectedTitleVersion == null || expectedTitleVersion < 0 ||
				expectedTitleVersion != member.getTitleVersion()) {
			throw new SoundConnectException(ErrorType.BAND_MEMBER_VERSION_CONFLICT);
		}
		
		eventMemberPublicationRepository.hideForBandMember(bandId, userId);
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
		// This is the aggregate fence shared with event creation and performer
		// decisions. Holding it prevents a request from acquiring a soon-to-be
		// deleted band target after invalidation has already scanned the requests.
		Band band = bandRepository.findByIdForUpdate(bandId)
				.orElseThrow(() -> new SoundConnectException(ErrorType.BAND_NOT_FOUND));
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

		for (Event event : eventRepository.findAllByBandIdForUpdate(bandId)) {
			if (event.getManualPerformerName() == null || event.getManualPerformerName().isBlank()) {
				event.setManualPerformerName(deletedBandName);
			}
			event.setBand(null);
			event.setPerformerApprovalStatus(EventPerformerApprovalStatus.NOT_REQUIRED);
			event.setProfileCalendarApproved(false);
		}

		eventPerformerRequestService.invalidateForBand(bandId);
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
		validateContent(dto, true);
		// Serialize quota reads and founder insertion for this account. Two
		// concurrent creates cannot both spend its final creation slot.
		User user = userRepository.findByIdForUpdate(userId)
				.orElseThrow(() -> new SoundConnectException(ErrorType.USER_NOT_FOUND));
		
		// band sadece musician profile sahibi kullanici tarafindan olusturulabilir
		if (musicianProfileRepository.findByUserId(userId).isEmpty()) {
			log.warn("Band olusturmak icin musician profile bulunamadi. userId={}", userId);
			throw new SoundConnectException(ErrorType.PROFILE_NOT_FOUND);
		}

		long foundedBandCount = bandMemberRepository.countByUserIdAndStatusAndBandRole(
				userId, BandMemberShipStatus.ACTIVE, BandRole.FOUNDER);
		if (foundedBandCount >= MAX_FOUNDED_BANDS_PER_USER) {
			log.warn("Band olusturma limiti asildi. userId={}, foundedBandCount={}", userId, foundedBandCount);
			throw new SoundConnectException(ErrorType.BAND_CREATE_LIMIT_EXCEEDED);
		}
		if (dto.profilePicture() != null) {
			// The band id does not exist yet, so creation-time media belongs to
			// the founder's USER scope. Updates use the BAND scope below.
			mediaAssetService.validateAssignableMedia(
					userId, dto.profilePicture(), MediaOwnerType.USER, userId, MediaKind.IMAGE
			);
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
		                .instagramUrl(ProfileInputValidation.webUrl(dto.instagramUrl(), "instagramUrl"))
		                .youtubeUrl(ProfileInputValidation.webUrl(dto.youtubeUrl(), "youtubeUrl"))
		                .soundCloudUrl(ProfileInputValidation.webUrl(dto.soundCloudUrl(), "soundCloudUrl"))
		                .spotifyEmbedUrl(ProfileInputValidation.webUrl(dto.spotifyEmbedUrl(), "spotifyEmbedUrl"))
		                .spotifyArtistId(ProfileInputValidation.optionalIdentifier(dto.spotifyArtistId()))
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
		return toResponseDto(saved, true);
	}
	
	@Override
	@Transactional
	public BandResponseDto updateBand(UUID bandId, UUID userId, BandCreateRequestDto dto) {
		validateContent(dto, false);
		// Serialize profile changes with membership, connection and deletion
		// snapshots; an unlocked stale entity must not race the aggregate fence.
		Band band = lockBandForMembershipChange(bandId);
		BandMember requester = bandMemberRepository.findByBandIdAndUserId(bandId, userId)
		                                           .orElseThrow(() -> new SoundConnectException(ErrorType.BAND_MEMBER_NOT_FOUND));
		
		if (requester.getStatus() != BandMemberShipStatus.ACTIVE) {
			throw new SoundConnectException(ErrorType.BAND_MEMBER_NOT_ACTIVE);
		}
		
		if (requester.getBandRole() != BandRole.FOUNDER) {
			throw new SoundConnectException(ErrorType.BAND_INVITE_UNAUTHORIZED);
		}
		
		if (dto.name() != null && !dto.name().equals(band.getName())) {
			bandRepository.findByName(dto.name()).ifPresent(existing -> {
				if (!existing.getId().equals(bandId)) {
					log.warn("Band adi daha once kullanilmis. Band: {}", dto.name());
					throw new SoundConnectException(ErrorType.BAND_ALREADY_EXISTS);
				}
			});
			band.setName(dto.name());
		}
		
		if (dto.description() != null) band.setDescription(dto.description());
		if (dto.profilePicture() != null) {
			mediaAssetService.validateAssignableMedia(
					userId, dto.profilePicture(), MediaOwnerType.BAND, bandId, MediaKind.IMAGE
			);
			band.setProfilePictureMediaId(dto.profilePicture());
		}
		if (dto.instagramUrl() != null) band.setInstagramUrl(ProfileInputValidation.webUrl(dto.instagramUrl(), "instagramUrl"));
		if (dto.youtubeUrl() != null) band.setYoutubeUrl(ProfileInputValidation.webUrl(dto.youtubeUrl(), "youtubeUrl"));
		if (dto.soundCloudUrl() != null) band.setSoundCloudUrl(ProfileInputValidation.webUrl(dto.soundCloudUrl(), "soundCloudUrl"));
		if (dto.spotifyEmbedUrl() != null) band.setSpotifyEmbedUrl(ProfileInputValidation.webUrl(dto.spotifyEmbedUrl(), "spotifyEmbedUrl"));
		if (dto.spotifyArtistId() != null) band.setSpotifyArtistId(ProfileInputValidation.optionalIdentifier(dto.spotifyArtistId()));
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
		                  .map(member -> toResponseDto(member.getBand(),
							member.getStatus() == BandMemberShipStatus.ACTIVE && member.getBandRole() == BandRole.FOUNDER))
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
		String q = query == null ? "" : UsernameUtils.stripBoundaryWhitespace(query); //eklendi
		if (q.isEmpty()) return List.of(); //eklendi
		ProfileInputValidation.text(q, 100, "query");
		
		return bandRepository.searchByName(q, PageRequest.of(0, 10)) //eklendi
		                     .stream() //eklendi
		                     .limit(10) //eklendi
		                     .map(band -> new BandSearchItemDto( //eklendi
		                                                         band.getId(), //eklendi
		                                                         band.getName(), //eklendi
		                                                         resolveProfilePictureUrl(band.getProfilePictureMediaId()) //eklendi
		                     )) //eklendi
		                     .toList(); //eklendi
	} //eklendi
	
	private void validateContent(BandCreateRequestDto dto, boolean creating) {
		if (dto == null) throw ProfileInputValidation.invalid("Band content is required");
		if ((creating && dto.name() == null) || (dto.name() != null
				&& UsernameUtils.stripBoundaryWhitespace(dto.name()).isBlank())) {
			throw ProfileInputValidation.invalid("Band name is required");
		}
		ProfileInputValidation.text(dto.name(), 100, "name");
		ProfileInputValidation.text(dto.description(), 1024, "description");
		ProfileInputValidation.trackIds(dto.spotifyTrackIds());
		ProfileInputValidation.optionalIdentifier(dto.spotifyArtistId());
		ProfileInputValidation.webUrl(dto.instagramUrl(), "instagramUrl");
		ProfileInputValidation.webUrl(dto.youtubeUrl(), "youtubeUrl");
		ProfileInputValidation.webUrl(dto.soundCloudUrl(), "soundCloudUrl");
		ProfileInputValidation.webUrl(dto.spotifyEmbedUrl(), "spotifyEmbedUrl");
	}

	/**
	 * Acquire the aggregate fence before loading any mutable membership state.
	 * Publication changes and public calendar reads lock this same band parent,
	 * so a member cannot publish concurrently with leaving or being removed.
	 */
	private Band lockBandForMembershipChange(UUID bandId) {
		return bandRepository.findByIdForUpdate(bandId)
				.orElseThrow(() -> new SoundConnectException(ErrorType.BAND_NOT_FOUND));
	}

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
		Map<String, Object> payload = new HashMap<>();
		payload.put("module", "BAND");
		payload.put("bandId", band.getId().toString());
		payload.put("bandName", safe(band.getName(), "Band"));
		if (extraPayload != null) payload.putAll(extraPayload);
		transactionalNotificationService.persistInCurrentTransaction(
				NotificationInboundEvent.builder()
						.eventId(UUID.randomUUID())
						.recipientId(recipientId)
						.type(type)
						.title(title)
						.message(message)
						.payload(payload)
						.emailForce(false)
						.occurredAt(Instant.now())
						.build()
		);
	}
	
	private String safe(String value, String fallback) {
		return value == null || value.isBlank() ? fallback : value.trim();
	}
	
	private BandResponseDto toResponseDto(Band band) {
		return toResponseDto(band, null);
	}

	private BandResponseDto toResponseDto(Band band, Boolean countsTowardCreationLimit) {
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
				base.members(),
				countsTowardCreationLimit
		);
	}
	
	private String resolveProfilePictureUrl(UUID mediaAssetId) {
		if (mediaAssetId == null) return null;
		try {
			return mediaAssetService.getDisplayUrl(mediaAssetId);
		} catch (Exception e) {
			log.warn("Band profile picture resolve failed. mediaAssetId={}", mediaAssetId);
			return null;
		}
	}
}

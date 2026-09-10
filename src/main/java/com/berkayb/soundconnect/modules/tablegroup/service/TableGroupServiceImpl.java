package com.berkayb.soundconnect.modules.tablegroup.service;

import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.location.entity.District;
import com.berkayb.soundconnect.modules.location.entity.Neighborhood;
import com.berkayb.soundconnect.modules.location.repository.CityRepository;
import com.berkayb.soundconnect.modules.location.repository.DistrictRepository;
import com.berkayb.soundconnect.modules.location.repository.NeighborhoodRepository;
import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.modules.profile.shared.avatar.PersonalProfileAvatarBatchResolver;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.enums.ListenerVisibilityMode;
import com.berkayb.soundconnect.modules.profile.shared.identity.GhostListenerIdentity;
import com.berkayb.soundconnect.modules.profile.shared.identity.GhostListenerIdentityBatchResolver;
import com.berkayb.soundconnect.modules.profile.StudioProfile.repository.StudioProfileRepository;
import com.berkayb.soundconnect.modules.tablegroup.abuse.TableGroupRateLimitGuard;
import com.berkayb.soundconnect.modules.tablegroup.chat.cache.TableGroupChatUnreadHelper;
import com.berkayb.soundconnect.modules.tablegroup.chat.repository.TableGroupMessageRepository;
import com.berkayb.soundconnect.modules.tablegroup.dto.request.TableGroupCreateRequestDto;
import com.berkayb.soundconnect.modules.tablegroup.dto.response.TableGroupParticipantDto;
import com.berkayb.soundconnect.modules.tablegroup.dto.response.TableGroupResponseDto;
import com.berkayb.soundconnect.modules.tablegroup.dto.response.TableGroupVenueOptionDto;
import com.berkayb.soundconnect.modules.tablegroup.entity.TableGroup;
import com.berkayb.soundconnect.modules.tablegroup.entity.TableGroupParticipant;
import com.berkayb.soundconnect.modules.tablegroup.enums.ParticipantStatus;
import com.berkayb.soundconnect.modules.tablegroup.enums.TableGroupStatus;
import com.berkayb.soundconnect.modules.tablegroup.game.service.TableGroupGameLifecycleService;
import com.berkayb.soundconnect.modules.tablegroup.mapper.TableGroupMapper;
import com.berkayb.soundconnect.modules.tablegroup.notification.outbox.TableGroupNotificationOutboxService;
import com.berkayb.soundconnect.modules.tablegroup.observability.TableGroupMetrics;
import com.berkayb.soundconnect.modules.tablegroup.repository.TableGroupRepository;
import com.berkayb.soundconnect.modules.tablegroup.scheduler.TableGroupExpiryWorker;
import com.berkayb.soundconnect.modules.tablegroup.security.TableGroupActorPolicy;
import com.berkayb.soundconnect.modules.tablegroup.support.TableGroupEntityFinder;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.berkayb.soundconnect.modules.venue.entity.Venue;
import com.berkayb.soundconnect.modules.venue.enums.VenueStatus;
import com.berkayb.soundconnect.modules.venue.repository.VenueRepository;
import com.berkayb.soundconnect.modules.venue.repository.projection.TableGroupVenueOptionProjection;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import jakarta.transaction.Transactional;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.Instant;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class TableGroupServiceImpl implements TableGroupService{
	private static final Set<String> VALID_GENDER_PREFERENCES = Set.of("FEMALE", "MALE", "OTHER");
	private static final Duration MAX_TABLE_LIFETIME = Duration.ofHours(24);
	private static final int EXPIRY_BATCH_SIZE = 100;
	private static final int DEFAULT_LIST_PAGE_SIZE = 20;
	private static final int MAX_LIST_PAGE_SIZE = 50;
	private static final int PERSONAL_AVATAR_BATCH_SIZE = 50;
	private static final int MAX_DESCRIPTION_LENGTH = 280;
	private static final int MAX_LIST_PAGE_NUMBER = 1_000;
	private static final int MAX_PENDING_APPLICATIONS = 50;
	private static final int MAX_TERMINAL_PARTICIPANT_RECORDS = 100;
	private static final int VENUE_OPTION_QUERY_MIN_LENGTH = 2;
	private static final int VENUE_OPTION_QUERY_MAX_LENGTH = 64;
	private static final int VENUE_OPTION_LIMIT_MIN = 1;
	private static final int VENUE_OPTION_LIMIT_MAX = 10;
	private static final Duration TERMINAL_PARTICIPANT_RETENTION = Duration.ofDays(7);
	private static final int TERMINAL_PARTICIPANT_RETENTION_BATCH_SIZE = 20;
	private static final int CHAT_RETENTION_BATCH_SIZE = 20;
	private static final Sort ACTIVE_LIST_SORT = Sort.by(
			Sort.Order.desc("createdAt"),
			Sort.Order.desc("id")
	);

	private final TableGroupNotificationOutboxService notificationOutboxService;
	private final TableGroupRepository tableGroupRepository;
	private final TableGroupMapper tableGroupMapper;
	private final CityRepository cityRepository;
	private final DistrictRepository districtRepository;
	private final NeighborhoodRepository neighborhoodRepository;
	private final TableGroupEntityFinder tableGroupEntityFinder;
	private final TableGroupMessageRepository tableGroupMessageRepository;
	private final TableGroupChatUnreadHelper unreadHelper;
	private final UserRepository userRepository;
	private final PersonalProfileAvatarBatchResolver personalProfileAvatarBatchResolver;
	private final GhostListenerIdentityBatchResolver ghostListenerIdentityBatchResolver;
	private final StudioProfileRepository studioProfileRepository;
	private final VenueRepository venueRepository;
	private final MediaAssetService mediaAssetService;
	private final TableGroupRateLimitGuard rateLimitGuard;
	private final TableGroupMetrics metrics;
	private final TableGroupGameLifecycleService gameLifecycleService;
	private final TableGroupExpiryWorker expiryWorker;

	@Value("${app.table-group.chat.retention:P30D}")
	private Duration chatRetention = Duration.ofDays(30);

	@PostConstruct
	void validateChatRetention() {
		if (chatRetention == null
				|| chatRetention.compareTo(Duration.ofDays(1)) < 0
				|| chatRetention.compareTo(Duration.ofDays(365)) > 0) {
			throw new IllegalStateException("Table-group chat retention must be between 1 and 365 days");
		}
	}

	@Override
	public List<TableGroupVenueOptionDto> findVenueOptions(String query, int limit) {
		String normalizedQuery = query == null ? "" : query.trim();
		if (normalizedQuery.length() < VENUE_OPTION_QUERY_MIN_LENGTH
				|| normalizedQuery.length() > VENUE_OPTION_QUERY_MAX_LENGTH) {
			throw new SoundConnectException(ErrorType.TABLE_GROUP_VENUE_OPTION_QUERY_INVALID);
		}
		if (limit < VENUE_OPTION_LIMIT_MIN || limit > VENUE_OPTION_LIMIT_MAX) {
			throw new SoundConnectException(ErrorType.TABLE_GROUP_VENUE_OPTION_LIMIT_INVALID);
		}

		List<TableGroupVenueOptionProjection> venueOptions = venueRepository.searchTableGroupVenueOptions(
					normalizedQuery,
					VenueStatus.APPROVED,
					PageRequest.of(0, limit)
				);
		List<UUID> profilePictureMediaIds = venueOptions.stream()
				.map(TableGroupVenueOptionProjection::getProfilePictureMediaId)
				.filter(Objects::nonNull)
				.distinct()
				.toList();
		Map<UUID, String> profilePictureUrls = profilePictureMediaIds.isEmpty()
				? Map.of()
				: mediaAssetService.getDisplayUrlMap(profilePictureMediaIds);

		return venueOptions
				.stream()
				.map(venue -> toVenueOptionDto(
						venue,
						venue.getProfilePictureMediaId() == null
								? null
								: profilePictureUrls.get(venue.getProfilePictureMediaId())
				))
				.toList();
	}

	// Owner bir katilimciyi masadan kickler
	@Override
	public void removeParticipantFromTableGroup(UUID ownerId, UUID tableGroupId, UUID participantId) {
		requireOwnerPreflight(tableGroupId, ownerId);
		if (Objects.equals(ownerId, participantId)) {
			throw new SoundConnectException(ErrorType.PARTICIPANT_NOT_FOUND);
		}
		ParticipantStatus preflightStatus = tableGroupRepository.findParticipantStatusForOwner(
				tableGroupId, ownerId, participantId)
				.orElseThrow(() -> new SoundConnectException(ErrorType.PARTICIPANT_NOT_FOUND));
		if (preflightStatus != ParticipantStatus.ACCEPTED
				&& preflightStatus != ParticipantStatus.KICKED) {
			throw new SoundConnectException(ErrorType.PARTICIPANT_NOT_FOUND);
		}
		TableGroup tableGroup = tableGroupEntityFinder.getTableGroupByIdForUpdate(tableGroupId);
		requireOwner(tableGroup, ownerId);

		if (tableGroup.getOwnerId().equals(participantId)) {
			throw new SoundConnectException(ErrorType.PARTICIPANT_NOT_FOUND);
		}

		TableGroupParticipant participant = findParticipant(tableGroup, participantId)
				.orElseThrow(() -> new SoundConnectException(ErrorType.PARTICIPANT_NOT_FOUND));
		if (participant.getStatus() == ParticipantStatus.KICKED) {
			return;
		}
		Instant now = Instant.now();
		requireActiveAndUnexpired(tableGroup, now);
		if (participant.getStatus() != ParticipantStatus.ACCEPTED) {
			throw new SoundConnectException(ErrorType.PARTICIPANT_NOT_FOUND);
		}

		participant.setStatus(ParticipantStatus.KICKED);
		participant.setJoinedAt(now);
		pruneTerminalParticipantHistory(tableGroup, now, participantId);
		gameLifecycleService.participantRemoved(tableGroup, participantId, "PLAYER_REMOVED");
		tableGroupRepository.save(tableGroup);

		notificationOutboxService.enqueue(
				participantId,
				NotificationType.TABLE_REMOVED,
				"Masadan çıkarıldın",
				"Bir masa etkinliğinden çıkarıldın.",
				tablePayload(tableGroupId, "PARTICIPANT_REMOVED", Map.of("ownerId", ownerId))
		);
		runAfterCommit("participant_kicked", metrics::participantKicked);
		log.info("Participant {} kicked from tableGroup {}", participantId, tableGroupId);
	}

	// Owner masayi iptal eder ve kabul edilmis kullanicilara bildirim gider
	@Override
	public void cancelTableGroup(UUID ownerId, UUID tableGroupId) {
		requireOwnerPreflight(tableGroupId, ownerId);
		TableGroupStatus preflightStatus = tableGroupRepository.findStatusById(tableGroupId)
				.orElseThrow(() -> new SoundConnectException(ErrorType.TABLE_GROUP_NOT_FOUND));
		if (preflightStatus != TableGroupStatus.ACTIVE
				&& preflightStatus != TableGroupStatus.CANCELLED) {
			throw new SoundConnectException(ErrorType.TABLE_GROUP_NOT_FOUND);
		}
		TableGroup tableGroup = tableGroupEntityFinder.getTableGroupByIdForUpdate(tableGroupId);
		requireOwner(tableGroup, ownerId);
		if (tableGroup.getStatus() == TableGroupStatus.CANCELLED) {
			return;
		}
		Instant now = Instant.now();
		requireActiveAndUnexpired(tableGroup, now);
		cancelLockedTableGroups(
				List.of(tableGroup),
				"TABLE_CANCELLED",
				"Katıldığın masa etkinliği iptal edildi.",
				"OWNER_CANCELLED"
		);
		log.info("TableGroup {} cancelled by owner {}", tableGroupId, ownerId);
	}

	// kullanicinin masaya katilma basvurusu
	@Override
	public void joinTableGroup(UUID userId, UUID tableGroupId, String joinNote) {
		if (userId == null) {
			throw new SoundConnectException(ErrorType.UNAUTHORIZED);
		}
		// Reject forbidden roles before quota/aggregate locks through a scalar
		// projection. It deliberately does not attach a User entity, avoiding a
		// stale first-level-cache snapshot at the authoritative locked recheck.
		Set<String> roleNames = userRepository.findRoleNamesByUserId(userId);
		if ((roleNames == null || roleNames.isEmpty()) && !userRepository.existsById(userId)) {
			throw new SoundConnectException(ErrorType.UNAUTHORIZED);
		}
		TableGroupActorPolicy.requireEligibleRoleNames(roleNames);
		requireNoInstitutionalFootprint(userId);
		rateLimitGuard.checkJoin(userId);
		// Every admission path takes the actor row before table rows. Approval may
		// need both the target and the applicant's owned table; this common order
		// prevents join/approval from forming a user-table lock cycle.
		User lockedActor = userRepository.findByIdForUpdate(userId).orElse(null);
		TableGroupActorPolicy.requireEligible(lockedActor);
		requireNoInstitutionalFootprint(userId);
		TableGroup tableGroup = tableGroupEntityFinder.getTableGroupByIdForUpdate(tableGroupId);
		Instant now = Instant.now();
		requireActiveAndUnexpired(tableGroup, now);

		if (tableGroup.getOwnerId().equals(userId)) {
			throw new SoundConnectException(ErrorType.ALREADY_PARTICIPANT);
		}
		pruneTerminalParticipantHistory(tableGroup, now, userId);
		Optional<TableGroupParticipant> existing = findParticipant(tableGroup, userId);
		if (existing.filter(participant -> isCurrentParticipantStatus(participant.getStatus())).isPresent()) {
			throw new SoundConnectException(ErrorType.ALREADY_PARTICIPANT);
		}

		// Pending requests do not reserve a seat. Approval is serialized by the
		// aggregate row lock and performs the authoritative capacity check.
		if (acceptedParticipantCount(tableGroup) >= tableGroup.getMaxPersonCount()) {
			throw new SoundConnectException(ErrorType.MAX_PARTICIPANT_LIMIT);
		}
		if (pendingParticipantCount(tableGroup) >= MAX_PENDING_APPLICATIONS) {
			throw new SoundConnectException(ErrorType.TABLE_GROUP_APPLICATION_LIMIT_REACHED);
		}

		String normalizedNote = normalizeJoinNote(joinNote);

		TableGroupParticipant joinRequest;
		if (existing.isPresent()) {
			// One collection row per user. Reapplication revives the terminal row,
			// avoiding Set equality silently discarding a newly-created object.
			joinRequest = existing.get();
			joinRequest.setStatus(ParticipantStatus.PENDING);
			joinRequest.setJoinedAt(now);
			joinRequest.setJoinNote(normalizedNote);
		} else {
			joinRequest = TableGroupParticipant.builder()
					.userId(userId)
					.joinedAt(now)
					.status(ParticipantStatus.PENDING)
					.joinNote(normalizedNote)
					.build();
			tableGroup.getParticipants().add(joinRequest);
		}
		tableGroupRepository.save(tableGroup);

		log.info("Join request: user={} tableGroup={} status={}", userId, tableGroupId, joinRequest.getStatus());

		// Notification Event (owner'a basvuru bildirimi fire et)
		if (!tableGroup.getOwnerId().equals(userId)) {
			notificationOutboxService.enqueue(
					tableGroup.getOwnerId(),
					NotificationType.TABLE_JOIN_REQUEST_RECEIVED,
					"Yeni masa başvurusu!",
					"Masana yeni bir başvuru geldi. Katılımcı onayı bekliyor.",
					tablePayload(tableGroup.getId(), "JOIN_REQUEST_RECEIVED", Map.of("applicantId", userId))
			);
		}
		runAfterCommit("join_requested", metrics::joinRequested);
	}

	// owner basvurani kabul eder
	@Override
	public void approveJoinRequest(UUID ownerId, UUID tableGroupId, UUID participantId) {
		if (participantId == null) {
			throw new SoundConnectException(ErrorType.PARTICIPANT_NOT_FOUND);
		}
		UUID persistedOwnerId = tableGroupRepository.findOwnerIdById(tableGroupId)
				.orElseThrow(() -> new SoundConnectException(ErrorType.TABLE_GROUP_NOT_FOUND));
		if (!Objects.equals(persistedOwnerId, ownerId)) {
			throw new SoundConnectException(ErrorType.FORBIDDEN_ACCESS);
		}
		ParticipantStatus preflightStatus = tableGroupRepository.findParticipantStatusForOwner(
				tableGroupId, ownerId, participantId)
				.orElseThrow(() -> new SoundConnectException(ErrorType.PARTICIPANT_NOT_FOUND));
		if (preflightStatus == ParticipantStatus.ACCEPTED) {
			// A completed approval is an idempotent success even if the participant's
			// role/profile changed afterwards. This path locks no user row and cannot
			// participate in the multi-table lifecycle lock graph.
			TableGroup tableGroup = tableGroupEntityFinder.getTableGroupByIdForUpdate(tableGroupId);
			requireOwner(tableGroup, ownerId);
			TableGroupParticipant participant = findParticipant(tableGroup, participantId)
					.orElseThrow(() -> new SoundConnectException(ErrorType.PARTICIPANT_NOT_FOUND));
			if (participant.getStatus() == ParticipantStatus.ACCEPTED) {
				return;
			}
			throw new SoundConnectException(ErrorType.PARTICIPANT_NOT_FOUND);
		}
		if (preflightStatus != ParticipantStatus.PENDING) {
			throw new SoundConnectException(ErrorType.PARTICIPANT_NOT_FOUND);
		}
		// The applicant row is the lifecycle mutex for both create and approval.
		// Discover the applicant's open aggregate ids while holding it, then lock
		// every involved table in UUID order. Two users cross-joining each other's
		// tables therefore acquire the same table pair in the same order.
		User applicant = userRepository.findByIdForUpdate(participantId).orElse(null);
		Instant discoveryNow = Instant.now();
		List<TableGroup> lifecycleTables = lockTargetAndOpenOwnedTables(
				tableGroupId, participantId, discoveryNow);
		// A lock wait may cross either table's expiry boundary. Lifecycle decisions
		// must use a timestamp captured after every involved row is locked.
		Instant decisionNow = Instant.now();
		TableGroup tableGroup = lifecycleTables.stream()
				.filter(candidate -> tableGroupId.equals(candidate.getId()))
				.findFirst()
				.orElseThrow(() -> new SoundConnectException(ErrorType.TABLE_GROUP_NOT_FOUND));
		requireOwner(tableGroup, ownerId);
		TableGroupParticipant participant = findParticipant(tableGroup, participantId)
				.orElseThrow(() -> new SoundConnectException(ErrorType.PARTICIPANT_NOT_FOUND));
		if (participant.getStatus() == ParticipantStatus.ACCEPTED) {
			return;
		}
		requireActiveAndUnexpired(tableGroup, decisionNow);
		if (participant.getStatus() != ParticipantStatus.PENDING) {
			throw new SoundConnectException(ErrorType.PARTICIPANT_NOT_FOUND);
		}
		TableGroupActorPolicy.requireEligible(applicant);
		requireNoInstitutionalFootprint(participantId);

		if (acceptedParticipantCount(tableGroup) >= tableGroup.getMaxPersonCount()) {
			throw new SoundConnectException(ErrorType.MAX_PARTICIPANT_LIMIT);
		}

		participant.setStatus(ParticipantStatus.ACCEPTED);
		// Cancellation purges games with clearAutomatically=true. Flush the target
		// acceptance first so that clearing the persistence context cannot discard
		// it; the surrounding transaction still rolls both changes back together.
		tableGroupRepository.saveAndFlush(tableGroup);

		List<TableGroup> openOwnedTables = lifecycleTables.stream()
				.filter(candidate -> !tableGroupId.equals(candidate.getId()))
				.filter(candidate -> participantId.equals(candidate.getOwnerId()))
				.filter(candidate -> isActiveAndUnexpired(candidate, decisionNow))
				.toList();
		if (!openOwnedTables.isEmpty()) {
			cancelLockedTableGroups(
					openOwnedTables,
					"TABLE_OWNER_JOINED_ANOTHER_TABLE",
					"Masa sahibi başka bir masaya katıldığı için masa kapatıldı.",
					"OWNER_JOINED_ANOTHER_TABLE"
			);
			log.info(
					"Auto-closed {} owned table group(s) after participant {} joined tableGroup {}",
					openOwnedTables.size(), participantId, tableGroupId);
		}

		log.info("Join request APPROVED: tableGroup={}, participant={}", tableGroupId, participantId);

		notificationOutboxService.enqueue(
				participantId,
				NotificationType.TABLE_JOIN_REQUEST_APPROVED,
				"Başvurun onaylandı",
				"Masana Mesajlar bölümünden ulaşabilirsin.",
				tablePayload(tableGroup.getId(), "JOIN_REQUEST_APPROVED", Map.of("ownerId", ownerId))
		);
		runAfterCommit("join_approved", metrics::joinApproved);

	}

	// owner basvuruyu reddeder
	@Override
	public void rejectJoinRequest(UUID ownerId, UUID tableGroupId, UUID participantId) {
		requireOwnerPreflight(tableGroupId, ownerId);
		ParticipantStatus preflightStatus = tableGroupRepository.findParticipantStatusForOwner(
				tableGroupId, ownerId, participantId)
				.orElseThrow(() -> new SoundConnectException(ErrorType.PARTICIPANT_NOT_FOUND));
		if (preflightStatus != ParticipantStatus.PENDING
				&& preflightStatus != ParticipantStatus.REJECTED) {
			throw new SoundConnectException(ErrorType.PARTICIPANT_NOT_FOUND);
		}
		TableGroup tableGroup = tableGroupEntityFinder.getTableGroupByIdForUpdate(tableGroupId);
		requireOwner(tableGroup, ownerId);
		TableGroupParticipant participant = findParticipant(tableGroup, participantId)
				.orElseThrow(() -> new SoundConnectException(ErrorType.PARTICIPANT_NOT_FOUND));
		if (participant.getStatus() == ParticipantStatus.REJECTED) {
			return;
		}
		Instant now = Instant.now();
		requireActiveAndUnexpired(tableGroup, now);
		if (participant.getStatus() != ParticipantStatus.PENDING) {
			throw new SoundConnectException(ErrorType.PARTICIPANT_NOT_FOUND);
		}

		// status rejectle
		participant.setStatus(ParticipantStatus.REJECTED);
		participant.setJoinedAt(now);
		pruneTerminalParticipantHistory(tableGroup, now, participantId);

		tableGroupRepository.save(tableGroup);
		log.info("Join request REJECTED: tableGroup={}, participant={}", tableGroupId, participantId);

		notificationOutboxService.enqueue(
				participantId,
				NotificationType.TABLE_JOIN_REQUEST_REJECTED,
				"Başvurun reddedildi",
				"Katıldığın masa başvurun reddedildi.",
				tablePayload(tableGroup.getId(), "JOIN_REQUEST_REJECTED", Map.of("ownerId", ownerId))
		);
		runAfterCommit("join_rejected", metrics::joinRejected);
	}

	// kullanici masadan ayrilir (owner ayrilamaz)
	@Override
	public void leaveTableGroup(UUID userId, UUID tableGroupId) {
		UUID persistedOwnerId = tableGroupRepository.findOwnerIdById(tableGroupId)
				.orElseThrow(() -> new SoundConnectException(ErrorType.TABLE_GROUP_NOT_FOUND));
		if (Objects.equals(persistedOwnerId, userId)) {
			throw new SoundConnectException(ErrorType.OWNER_CANNOT_LEAVE);
		}
		ParticipantStatus preflightStatus = tableGroupRepository.findParticipantStatus(
				tableGroupId, userId)
				.orElseThrow(() -> new SoundConnectException(ErrorType.PARTICIPANT_NOT_FOUND));
		if (preflightStatus != ParticipantStatus.ACCEPTED
				&& preflightStatus != ParticipantStatus.LEFT) {
			throw new SoundConnectException(ErrorType.PARTICIPANT_NOT_FOUND);
		}
		TableGroup tableGroup = tableGroupEntityFinder.getTableGroupByIdForUpdate(tableGroupId);

		// kullanici owner mi ownersa masadan ayrilamaz
		if (tableGroup.getOwnerId().equals(userId)) {
			throw new SoundConnectException(ErrorType.OWNER_CANNOT_LEAVE);
		}

		TableGroupParticipant participant = findParticipant(tableGroup, userId)
				.orElseThrow(() -> new SoundConnectException(ErrorType.PARTICIPANT_NOT_FOUND));
		if (participant.getStatus() == ParticipantStatus.LEFT) {
			return;
		}
		Instant now = Instant.now();
		requireActiveAndUnexpired(tableGroup, now);
		if (participant.getStatus() != ParticipantStatus.ACCEPTED) {
			throw new SoundConnectException(ErrorType.PARTICIPANT_NOT_FOUND);
		}
		// statuyu left yap
		participant.setStatus(ParticipantStatus.LEFT);
		participant.setJoinedAt(now);
		pruneTerminalParticipantHistory(tableGroup, now, userId);
		gameLifecycleService.participantRemoved(tableGroup, userId, "PLAYER_LEFT");

		tableGroupRepository.save(tableGroup);
		log.info("User {} left table group {}", userId, tableGroupId);

		notificationOutboxService.enqueue(
				tableGroup.getOwnerId(),
				NotificationType.TABLE_PARTICIPANT_LEFT,
				"Katılımcı ayrıldı",
				"Masandaki bir katılımcı ayrıldı.",
				tablePayload(tableGroupId, "PARTICIPANT_LEFT", Map.of("leaverId", userId))
		);
		runAfterCommit("participant_left", metrics::participantLeft);


	}

	// yeni masa olusturma owner otomatik accepted.
	@Override
	public TableGroupResponseDto createTableGroup(UUID ownerId, TableGroupCreateRequestDto requestDto) {
		if (requestDto == null) {
			throw new SoundConnectException(ErrorType.VALIDATION_ERROR);
		}
		if (ownerId == null) {
			throw new SoundConnectException(ErrorType.UNAUTHORIZED);
		}
		// Presence is the wire contract: even an all-whitespace venueName is a
		// supplied second venue selector and must not be normalized into bypassing
		// the mutually-exclusive venueId/venueName rule.
		if (requestDto.venueId() != null && requestDto.venueName() != null) {
			throw new SoundConnectException(ErrorType.VENUE_ID_AND_NAME_CONFLICT);
		}
		String normalizedVenueName = normalizeVenueName(requestDto.venueName());
		String normalizedDescription = normalizeRequiredDescription(requestDto.description());
		// Only deterministic shape checks needed by the fingerprint run before
		// replay. A committed retry must not consume quota or depend on clocks,
		// Redis, or venue/location state that may have changed after commit.
		if (requestDto.genderPrefs() == null
				|| requestDto.genderPrefs().stream().anyMatch(Objects::isNull)) {
			throw new SoundConnectException(ErrorType.GENDER_AND_COUNT_MISMATCH);
		}
		if (requestDto.meetingAt() == null) {
			throw new SoundConnectException(ErrorType.TABLE_END_DATE_PASSED);
		}
		if (requestDto.cityId() == null) {
			throw new SoundConnectException(ErrorType.CITY_NOT_FOUND);
		}
		UUID createRequestKey = createRequestKey(
				ownerId,
				requestDto,
				normalizedVenueName,
				normalizedDescription
		);
		User owner = userRepository.findByIdForUpdate(ownerId)
				.orElseThrow(() -> new SoundConnectException(ErrorType.UNAUTHORIZED));
		TableGroupActorPolicy.requireEligible(owner);
		requireNoInstitutionalFootprint(ownerId);
		Optional<TableGroup> replay = tableGroupRepository.findByOwnerIdAndCreateRequestKey(
				ownerId, createRequestKey);
		if (replay.isPresent()) {
			return renderDetail(replay.get(), ownerId);
		}
		if (!lockOpenOwnedTables(ownerId, Instant.now()).isEmpty()) {
			throw new SoundConnectException(ErrorType.TABLE_GROUP_OWNER_ACTIVE_EXISTS);
		}
		// The owner-table lock can block behind expiry/cancellation work. Validate
		// the requested lifetime against a fresh post-lock timestamp as well.
		Instant now = Instant.now();
		rateLimitGuard.checkCreate(ownerId);

		// Validasyonlar
		Venue registeredVenue = requestDto.venueId() == null
				? null
				: venueRepository.findById(requestDto.venueId())
						.filter(venue -> venue.getStatus() == VenueStatus.APPROVED)
						.orElseThrow(() -> new SoundConnectException(ErrorType.VENUE_NOT_FOUND));
		String venueNameSnapshot = registeredVenue == null
				? normalizedVenueName
				: requireRegisteredVenueName(registeredVenue);

		// Yaş aralığı
		if (requestDto.ageMin() < 19 || requestDto.ageMax() > 99 || requestDto.ageMin() > requestDto.ageMax()) {
			throw new SoundConnectException(ErrorType.INVALID_AGE_RANGE);
		}

		// Cinsiyet dağılımı kişi sayısıyla uyuşmalı
		if (requestDto.maxPersonCount() < 2 || requestDto.maxPersonCount() > 6
				|| requestDto.genderPrefs() == null
				|| requestDto.genderPrefs().size() != requestDto.maxPersonCount()
				|| requestDto.genderPrefs().stream().anyMatch(
						pref -> pref == null || !VALID_GENDER_PREFERENCES.contains(pref))) {
			throw new SoundConnectException(ErrorType.GENDER_AND_COUNT_MISMATCH);
		}

		// Buluşma zamanı geçmiş olamaz ve masa ömrünün içinde kalmalıdır.
		if (requestDto.meetingAt() == null || !requestDto.meetingAt().isAfter(now)) {
			throw new SoundConnectException(ErrorType.TABLE_END_DATE_PASSED);
		}
		if (requestDto.meetingAt().isAfter(now.plus(MAX_TABLE_LIFETIME))) {
			throw new SoundConnectException(ErrorType.TABLE_GROUP_DURATION_INVALID);
		}

		if (requestDto.cityId() == null) {
			throw new SoundConnectException(ErrorType.CITY_NOT_FOUND);
		}

		// Lokasyon doğrulama
		City city = cityRepository.findById(requestDto.cityId())
		                          .orElseThrow(() -> new SoundConnectException(ErrorType.CITY_NOT_FOUND));

		District district;
		Neighborhood neighborhood;
		if (registeredVenue != null) {
			requireRegisteredVenueLocation(registeredVenue, city, requestDto.districtId(), requestDto.neighborhoodId());
			district = registeredVenue.getDistrict();
			neighborhood = registeredVenue.getNeighborhood();
		} else {
			district = null;
			if (requestDto.districtId() != null) {
				district = districtRepository.findById(requestDto.districtId())
						.orElseThrow(() -> new SoundConnectException(ErrorType.DISTRICT_NOT_FOUND));

				if (district.getCity() == null || !Objects.equals(district.getCity().getId(), city.getId())) {
					throw new SoundConnectException(ErrorType.DISTRICT_CITY_MISMATCH);
				}
			}

			neighborhood = null;
			if (requestDto.neighborhoodId() != null) {
				if (district == null) {
					throw new SoundConnectException(
							ErrorType.NEIGHBORHOOD_DISTRICT_MISMATCH,
							"Neighborhood requires its district"
					);
				}
				neighborhood = neighborhoodRepository.findById(requestDto.neighborhoodId())
						.orElseThrow(() -> new SoundConnectException(ErrorType.NEIGHBORHOOD_NOT_FOUND));

				if (neighborhood.getDistrict() == null
						|| !Objects.equals(neighborhood.getDistrict().getId(), district.getId())) {
					throw new SoundConnectException(ErrorType.NEIGHBORHOOD_DISTRICT_MISMATCH);
				}
			}
		}

		// Entity build
		TableGroup entity = tableGroupMapper.toEntity(requestDto);
		entity.setOwnerId(ownerId);
		entity.setCreateRequestKey(createRequestKey);
		entity.setVenueName(venueNameSnapshot);
		entity.setDescription(normalizedDescription);
		entity.setStartAt(now);
		entity.setMeetingAt(requestDto.meetingAt());
		entity.setExpiresAt(now.plus(MAX_TABLE_LIFETIME));
		entity.setStatus(TableGroupStatus.ACTIVE);
		entity.setCity(city);
		entity.setDistrict(district);
		entity.setNeighborhood(neighborhood);

		// Owner'ı otomatik ACCEPTED participant olarak ekle
		TableGroupParticipant ownerParticipant = TableGroupParticipant.builder()
		                                                              .userId(ownerId)
		                                                              .joinedAt(now)
		                                                              .status(ParticipantStatus.ACCEPTED)
		                                                              .build();

		entity.getParticipants().add(ownerParticipant);

		entity = tableGroupRepository.save(entity);
		runAfterCommit("created", metrics::created);

		log.info("TableGroup created: id={}, owner={}, venueId={}, venueName={}, city={}",
		         entity.getId(), ownerId, entity.getVenueId(), entity.getVenueName(),
		         city.getName()
		);

		return renderDetail(entity, ownerId);
	}

	// aktif masalari lokasyona gore listele.
	@Override
	public Page<TableGroupResponseDto> listActiveTableGroups(
			UUID viewerId,
			UUID cityId,
			UUID districtId,
			UUID neighborhoodId,
			Pageable pageable
	) {
		Instant now = Instant.now();
		Page<TableGroup> page;
		Pageable boundedPageable = boundedActiveListPageable(pageable);

		if (neighborhoodId != null && districtId == null) {
			throw new SoundConnectException(
					ErrorType.DISTRICT_NOT_FOUND,
					"Neighborhood filtresi icin district zorunlu"
			);
		}
		if (cityId == null && districtId != null) {
			throw new SoundConnectException(
					ErrorType.DISTRICT_CITY_MISMATCH,
					"District filtresi icin city zorunlu"
			);
		}

		if (cityId == null) {
			page = tableGroupRepository.findByStatusAndExpiresAtAfter(
					TableGroupStatus.ACTIVE,
					now,
					boundedPageable
			);
		} else if (neighborhoodId != null) {
			page = tableGroupRepository.findByCityIdAndDistrictIdAndNeighborhoodIdAndStatusAndExpiresAtAfter(
					cityId,
					districtId,
					neighborhoodId,
					TableGroupStatus.ACTIVE,
					now,
					boundedPageable
			);
		} else if (districtId != null) {
			page = tableGroupRepository.findByCityIdAndDistrictIdAndStatusAndExpiresAtAfter(
					cityId,
					districtId,
					TableGroupStatus.ACTIVE,
					now,
					boundedPageable
			);
		} else {
			page = tableGroupRepository.findByCityIdAndStatusAndExpiresAtAfter(
					cityId,
					TableGroupStatus.ACTIVE,
					now,
					boundedPageable
			);
		}

		return renderSummaryPage(page, viewerId);
	}

	@Override
	public Page<TableGroupResponseDto> listMyActiveTableGroups(UUID viewerId, Pageable pageable) {
		if (viewerId == null) {
			throw new SoundConnectException(ErrorType.UNAUTHORIZED);
		}
		Page<TableGroup> page = tableGroupRepository.findActiveAccessibleByUser(
				viewerId,
				TableGroupStatus.ACTIVE,
				ParticipantStatus.ACCEPTED,
				Instant.now(),
				boundedActiveListPageable(pageable)
		);
		return renderSummaryPage(page, viewerId);
	}


	// tek masa detayi
	@Override
	public TableGroupResponseDto getTableGroupDetail(UUID viewerId, UUID tableGroupId) {
		TableGroup entity = tableGroupRepository.findById(tableGroupId)
				.orElseThrow(() -> new SoundConnectException(ErrorType.TABLE_GROUP_NOT_FOUND));
		boolean active = entity.getStatus() == TableGroupStatus.ACTIVE
				&& entity.getExpiresAt() != null
				&& entity.getExpiresAt().isAfter(Instant.now());
		boolean member = Objects.equals(entity.getOwnerId(), viewerId)
				|| findParticipant(entity, viewerId)
						.filter(participant -> participant.getStatus() == ParticipantStatus.ACCEPTED)
						.isPresent();
		if (!active && !member) {
			throw new SoundConnectException(ErrorType.TABLE_GROUP_NOT_FOUND);
		}
		return renderDetail(entity, viewerId);
	}

	@org.springframework.transaction.annotation.Transactional(
			propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED
	)
	public void expireExpiredTableGroups() {
		Instant now = Instant.now();

		List<UUID> expiredIds = tableGroupRepository.findExpiredIds(
				TableGroupStatus.ACTIVE,
				now,
				PageRequest.of(0, EXPIRY_BATCH_SIZE)
		);

		if (expiredIds.isEmpty()) {
			return;
		}

		int expiredCount = 0;
		// Keep the established UUID lock order, but give each aggregate its own
		// transaction. A poison row is logged and skipped without rolling back the
		// successful transitions that follow it in this batch.
		List<UUID> orderedIds = expiredIds.stream()
				.filter(Objects::nonNull)
				.distinct()
				.sorted()
				.toList();
		for (UUID tableGroupId : orderedIds) {
			try {
				if (expiryWorker.expireIfDue(tableGroupId, now)) {
					expiredCount++;
				}
			} catch (RuntimeException exception) {
				log.error(
						"Table-group expiry transition failed. tableGroupId={}, exceptionType={}",
						tableGroupId,
						exception.getClass().getName(),
						exception
				);
				try {
					metrics.expiryTransitionFailed();
				} catch (RuntimeException metricException) {
					log.warn(
							"Could not record table-group expiry failure metric. tableGroupId={}, exceptionType={}",
							tableGroupId,
							metricException.getClass().getName()
					);
				}
			}
		}

		if (expiredCount > 0) {
			log.info("Expired {} table groups at {}", expiredCount, now);
		}
	}

	public int purgeRetainedChatMessages() {
		List<UUID> tableGroupIds = tableGroupMessageRepository.findTableGroupIdsEligibleForRetention(
				Instant.now().minus(chatRetention),
				PageRequest.of(0, CHAT_RETENTION_BATCH_SIZE)
		);
		if (tableGroupIds.isEmpty()) {
			return 0;
		}
		gameLifecycleService.purgeForTableGroups(tableGroupIds);
		int deleted = tableGroupMessageRepository.deleteAllByTableGroupIdIn(tableGroupIds);
		log.info("Purged {} retained chat messages for {} table groups", deleted, tableGroupIds.size());
		return deleted;
	}

	public int purgeRetainedParticipantHistory() {
		Instant now = Instant.now();
		List<UUID> tableGroupIds = tableGroupRepository.findIdsWithTerminalParticipantsBefore(
				Set.of(ParticipantStatus.REJECTED, ParticipantStatus.KICKED, ParticipantStatus.LEFT),
				now.minus(TERMINAL_PARTICIPANT_RETENTION),
				PageRequest.of(0, TERMINAL_PARTICIPANT_RETENTION_BATCH_SIZE)
		);
		int deleted = 0;
		for (UUID tableGroupId : tableGroupIds) {
			TableGroup locked = tableGroupEntityFinder.getTableGroupByIdForUpdate(tableGroupId);
			int before = locked.getParticipants() == null ? 0 : locked.getParticipants().size();
			pruneTerminalParticipantHistory(locked, now, null);
			int after = locked.getParticipants() == null ? 0 : locked.getParticipants().size();
			if (after < before) {
				tableGroupRepository.save(locked);
				deleted += before - after;
			}
		}
		if (deleted > 0) {
			log.info("Purged {} retained terminal participant records", deleted);
		}
		return deleted;
	}

	private Page<TableGroupResponseDto> renderSummaryPage(Page<TableGroup> page, UUID viewerId) {
		Map<UUID, TableGroupResponseDto> projectedById = new LinkedHashMap<>();
		for (TableGroup entity : page.getContent()) {
			projectedById.put(entity.getId(), projectParticipants(tableGroupMapper.toDto(entity), viewerId, false));
		}

		UserMetadata metadata = resolveUserMetadata(projectedById.values(), false);
		Map<UUID, TableGroupResponseDto> renderedById = projectedById.entrySet().stream()
				.collect(Collectors.toMap(
						Map.Entry::getKey,
						entry -> enrichUserMetadata(entry.getValue(), metadata, false),
						(first, ignored) -> first,
						LinkedHashMap::new
				));
		return page.map(entity -> renderedById.get(entity.getId()));
	}

	private TableGroupResponseDto renderDetail(TableGroup entity, UUID viewerId) {
		TableGroupResponseDto projected = projectParticipants(
				tableGroupMapper.toDto(entity),
				viewerId,
				true
		);
		return enrichUserMetadata(projected, resolveUserMetadata(List.of(projected), true), true);
	}

	/**
	 * Summary responses expose accepted members plus the caller's own pending
	 * application. Detail responses additionally expose pending applications to
	 * the owner. Terminal participant history is never part of the client
	 * contract, and join notes are owner-only.
	 */
	private TableGroupResponseDto projectParticipants(
			TableGroupResponseDto dto,
			UUID viewerId,
			boolean detail
	) {
		boolean ownerDetail = detail && Objects.equals(dto.ownerId(), viewerId);
		Set<TableGroupParticipantDto> projectedParticipants = Optional.ofNullable(dto.participants())
				.orElseGet(Set::of)
				.stream()
				.filter(Objects::nonNull)
				.filter(participant -> isVisibleParticipant(participant, viewerId, ownerDetail))
				.sorted(Comparator
						.comparing(
								TableGroupParticipantDto::joinedAt,
								Comparator.nullsLast(Comparator.naturalOrder())
						)
						.thenComparing(participant -> participant.userId().toString()))
				.map(participant -> TableGroupParticipantDto.builder()
						.userId(participant.userId())
						.joinedAt(participant.joinedAt())
						.status(participant.status())
						.joinNote(ownerDetail ? participant.joinNote() : null)
						.build())
				.collect(Collectors.toCollection(LinkedHashSet::new));

		return copyResponse(
				dto,
				dto.ownerUsername(),
				dto.ownerProfileImageUrl(),
				dto.ownerVisibilityMode(),
				projectedParticipants
		);
	}

	private boolean isVisibleParticipant(
			TableGroupParticipantDto participant,
			UUID viewerId,
			boolean ownerDetail
	) {
		if (participant.status() == ParticipantStatus.ACCEPTED) {
			return true;
		}
		if (participant.status() != ParticipantStatus.PENDING) {
			return false;
		}
		return ownerDetail || Objects.equals(participant.userId(), viewerId);
	}

	private UserMetadata resolveUserMetadata(
			Collection<TableGroupResponseDto> dtos,
			boolean includeProfileImages
	) {
		Set<UUID> visibleUserIds = new LinkedHashSet<>();
		Set<UUID> ownerUserIds = new LinkedHashSet<>();
		for (TableGroupResponseDto dto : dtos) {
			if (dto.ownerId() != null) {
				visibleUserIds.add(dto.ownerId());
				ownerUserIds.add(dto.ownerId());
			}
			Optional.ofNullable(dto.participants()).orElseGet(Set::of).stream()
					.map(TableGroupParticipantDto::userId)
					.filter(Objects::nonNull)
					.forEach(visibleUserIds::add);
		}

		Map<UUID, String> usernames = new HashMap<>();
		if (!visibleUserIds.isEmpty()) {
			for (User user : userRepository.findAllById(visibleUserIds)) {
				if (user.getId() != null && user.getUsername() != null) {
					usernames.put(user.getId(), user.getUsername());
				}
			}
		}

		Map<UUID, String> profileImages = new HashMap<>();
		if (includeProfileImages) {
			// Owner-visible detail may include 50 pending applicants in addition
			// to the owner, so keep each projection within the resolver contract.
			profileImages.putAll(resolvePersonalAvatarsSafely(visibleUserIds, "detail"));
		} else {
			// Active-list pages are capped at 50. Resolve owner-only personal
			// avatars with one profile projection plus one media batch query.
			profileImages.putAll(resolveOwnerAvatarsSafely(ownerUserIds));
		}

		Map<UUID, ListenerVisibilityMode> visibilityModes = new HashMap<>();
		Map<UUID, GhostListenerIdentity> ghostIdentities =
				ghostListenerIdentityBatchResolver.resolve(visibleUserIds);
		if (ghostIdentities != null) {
			for (GhostListenerIdentity identity : ghostIdentities.values()) {
				if (identity == null || identity.userId() == null) continue;
				usernames.put(identity.userId(), identity.username());
				// Never retain an alternate-profile avatar for a ghost listener.
				profileImages.remove(identity.userId());
				if (identity.profilePictureUrl() != null
						&& (includeProfileImages || ownerUserIds.contains(identity.userId()))) {
					profileImages.put(identity.userId(), identity.profilePictureUrl());
				}
				visibilityModes.put(identity.userId(), ListenerVisibilityMode.GHOST);
			}
		}
		return new UserMetadata(usernames, profileImages, visibilityModes);
	}

	private Map<UUID, String> resolveOwnerAvatarsSafely(Set<UUID> ownerUserIds) {
		return resolvePersonalAvatarsSafely(ownerUserIds, "active table-group owner");
	}

	private Map<UUID, String> resolvePersonalAvatarsSafely(
			Collection<UUID> userIds,
			String context
	) {
		List<UUID> distinctUserIds = userIds == null
				? List.of()
				: userIds.stream().filter(Objects::nonNull).distinct().toList();
		if (distinctUserIds.isEmpty()) {
			return Map.of();
		}

		Map<UUID, String> profileImages = new HashMap<>();
		for (int offset = 0; offset < distinctUserIds.size(); offset += PERSONAL_AVATAR_BATCH_SIZE) {
			int end = Math.min(offset + PERSONAL_AVATAR_BATCH_SIZE, distinctUserIds.size());
			Set<UUID> batch = new LinkedHashSet<>(distinctUserIds.subList(offset, end));
			try {
				Map<UUID, String> resolved = personalProfileAvatarBatchResolver.resolve(batch);
				if (resolved != null) {
					profileImages.putAll(resolved);
				}
			} catch (RuntimeException exception) {
				// Avatar enrichment is optional. Continue other chunks so one failed
				// auxiliary read cannot make detail/feed unavailable or erase successes.
				log.warn("Could not enrich {} avatars for batch {}", context, offset / PERSONAL_AVATAR_BATCH_SIZE, exception);
			}
		}
		return profileImages;
	}

	private TableGroupResponseDto enrichUserMetadata(
			TableGroupResponseDto dto,
			UserMetadata metadata,
			boolean includeParticipantProfileImages
	) {
		Set<TableGroupParticipantDto> participants = Optional.ofNullable(dto.participants())
				.orElseGet(Set::of)
				.stream()
				.map(participant -> TableGroupParticipantDto.builder()
						.userId(participant.userId())
						.joinedAt(participant.joinedAt())
						.status(participant.status())
						.joinNote(participant.joinNote())
						.username(metadata.usernames().get(participant.userId()))
						.profilePictureUrl(includeParticipantProfileImages
								? metadata.profileImages().get(participant.userId())
								: null)
						.visibilityMode(metadata.visibilityModes().get(participant.userId()))
						.build())
				.collect(Collectors.toCollection(LinkedHashSet::new));

		return copyResponse(
				dto,
				metadata.usernames().get(dto.ownerId()),
				metadata.profileImages().get(dto.ownerId()),
				metadata.visibilityModes().get(dto.ownerId()),
				participants
		);
	}

	private TableGroupResponseDto copyResponse(
			TableGroupResponseDto dto,
			String ownerUsername,
			String ownerProfileImageUrl,
			ListenerVisibilityMode ownerVisibilityMode,
			Set<TableGroupParticipantDto> participants
	) {
		return new TableGroupResponseDto(
				dto.id(),
				dto.ownerId(),
				ownerUsername,
				ownerProfileImageUrl,
				dto.venueId(),
				dto.venueName(),
				dto.description(),
				dto.maxPersonCount(),
				dto.genderPrefs(),
				dto.ageMin(),
				dto.ageMax(),
				dto.startAt(),
				dto.meetingAt(),
				dto.expiresAt(),
				dto.status(),
				participants,
				dto.city(),
				dto.district(),
				dto.neighborhood(),
				ownerVisibilityMode
		);
	}

	private Pageable boundedActiveListPageable(Pageable pageable) {
		if (pageable == null || pageable.isUnpaged()) {
			return PageRequest.of(0, DEFAULT_LIST_PAGE_SIZE, ACTIVE_LIST_SORT);
		}
		if (pageable.getPageNumber() < 0 || pageable.getPageNumber() > MAX_LIST_PAGE_NUMBER
				|| pageable.getPageSize() < 1) {
			throw new SoundConnectException(ErrorType.TABLE_GROUP_PAGE_REQUEST_INVALID);
		}
		return PageRequest.of(
				pageable.getPageNumber(),
				Math.min(pageable.getPageSize(), MAX_LIST_PAGE_SIZE),
				ACTIVE_LIST_SORT
		);
	}

	private void requireRegisteredVenueLocation(
			Venue venue,
			City requestedCity,
			UUID requestedDistrictId,
			UUID requestedNeighborhoodId
	) {
		District venueDistrict = venue.getDistrict();
		Neighborhood venueNeighborhood = venue.getNeighborhood();
		UUID venueCityId = venue.getCity() == null ? null : venue.getCity().getId();
		UUID venueDistrictId = venueDistrict == null ? null : venueDistrict.getId();
		UUID venueNeighborhoodId = venueNeighborhood == null ? null : venueNeighborhood.getId();

		boolean hierarchyInvalid = venueCityId == null
				|| venueDistrictId == null
				|| venueNeighborhoodId == null
				|| venueDistrict.getCity() == null
				|| !Objects.equals(venueDistrict.getCity().getId(), venueCityId)
				|| venueNeighborhood.getDistrict() == null
				|| !Objects.equals(venueNeighborhood.getDistrict().getId(), venueDistrictId);
		boolean requestMismatch = !Objects.equals(venueCityId, requestedCity.getId())
				|| (requestedDistrictId != null && !Objects.equals(requestedDistrictId, venueDistrictId))
				|| (requestedNeighborhoodId != null && !Objects.equals(requestedNeighborhoodId, venueNeighborhoodId));
		if (hierarchyInvalid || requestMismatch) {
			throw new SoundConnectException(ErrorType.TABLE_GROUP_VENUE_LOCATION_MISMATCH);
		}
	}

	private void requireOwner(TableGroup tableGroup, UUID ownerId) {
		if (!Objects.equals(tableGroup.getOwnerId(), ownerId)) {
			throw new SoundConnectException(ErrorType.FORBIDDEN_ACCESS);
		}
	}

	private void requireOwnerPreflight(UUID tableGroupId, UUID ownerId) {
		UUID persistedOwnerId = tableGroupRepository.findOwnerIdById(tableGroupId)
				.orElseThrow(() -> new SoundConnectException(ErrorType.TABLE_GROUP_NOT_FOUND));
		if (!Objects.equals(persistedOwnerId, ownerId)) {
			throw new SoundConnectException(ErrorType.FORBIDDEN_ACCESS);
		}
	}

	private void requireNoInstitutionalFootprint(UUID userId) {
		if (venueRepository.existsByOwner_Id(userId)
				|| studioProfileRepository.existsByUserId(userId)) {
			throw new SoundConnectException(ErrorType.TABLE_GROUP_ACTOR_ROLE_FORBIDDEN);
		}
	}

	private List<TableGroup> lockOpenOwnedTables(UUID ownerId, Instant now) {
		List<TableGroup> locked = lockTableGroupsInOrder(tableGroupRepository.findOpenIdsByOwner(
				ownerId, TableGroupStatus.ACTIVE, now));
		Instant decisionNow = Instant.now();
		return locked.stream()
				.filter(tableGroup -> ownerId.equals(tableGroup.getOwnerId()))
				.filter(tableGroup -> isActiveAndUnexpired(tableGroup, decisionNow))
				.toList();
	}

	private List<TableGroup> lockTargetAndOpenOwnedTables(
			UUID targetTableGroupId,
			UUID ownerId,
			Instant now
	) {
		List<UUID> ids = new ArrayList<>(tableGroupRepository.findOpenIdsByOwner(
				ownerId, TableGroupStatus.ACTIVE, now));
		ids.add(targetTableGroupId);
		return lockTableGroupsInOrder(ids);
	}

	private List<TableGroup> lockTableGroupsInOrder(Collection<UUID> tableGroupIds) {
		if (tableGroupIds == null || tableGroupIds.isEmpty()) {
			return List.of();
		}
		return tableGroupIds.stream()
				.filter(Objects::nonNull)
				.distinct()
				.sorted()
				.map(tableGroupEntityFinder::getTableGroupByIdForUpdate)
				.toList();
	}

	/**
	 * Shared terminal cancellation path for manual and acceptance-triggered
	 * closure. All lazy notification recipients are captured before the game and
	 * chat bulk deletes clear the persistence context.
	 */
	private void cancelLockedTableGroups(
			Collection<TableGroup> lockedTableGroups,
			String gameClosureReason,
			String notificationBody,
			String closureReason
	) {
		Map<UUID, TableGroup> uniqueTables = new LinkedHashMap<>();
		for (TableGroup tableGroup : lockedTableGroups) {
			if (tableGroup != null && tableGroup.getId() != null) {
				uniqueTables.putIfAbsent(tableGroup.getId(), tableGroup);
			}
		}
		if (uniqueTables.isEmpty()) {
			return;
		}

		Map<UUID, List<UUID>> recipientsByTable = new LinkedHashMap<>();
		for (TableGroup tableGroup : uniqueTables.values()) {
			List<UUID> recipientIds = acceptedParticipantIdsExcludingOwner(tableGroup);
			recipientsByTable.put(tableGroup.getId(), recipientIds);
			removePendingParticipants(tableGroup);
			gameLifecycleService.tableClosed(tableGroup, gameClosureReason);
			tableGroup.setStatus(TableGroupStatus.CANCELLED);
		}

		List<TableGroup> tables = List.copyOf(uniqueTables.values());
		List<UUID> tableGroupIds = List.copyOf(uniqueTables.keySet());
		tableGroupRepository.saveAllAndFlush(tables);
		gameLifecycleService.purgeForTableGroups(tableGroupIds);
		tableGroupMessageRepository.deleteAllByTableGroupIdIn(tableGroupIds);

		for (UUID tableGroupId : tableGroupIds) {
			recipientsByTable.getOrDefault(tableGroupId, List.of()).forEach(recipientId ->
					notificationOutboxService.enqueue(
						recipientId,
						NotificationType.TABLE_CANCELLED,
						"Masa iptal edildi",
						notificationBody,
						tablePayload(
							tableGroupId,
							"CANCELLED",
							Map.of("reason", closureReason)
						)
					));
			runAfterCommit("cancelled", () -> {
				unreadHelper.clearAllUnreadForTableGroup(tableGroupId);
				metrics.cancelled();
			});
		}
	}

	private void runAfterCommit(String operation, Runnable callback) {
		Runnable safeCallback = () -> {
			try {
				callback.run();
			} catch (RuntimeException exception) {
				log.warn("Table-group after-commit side effect failed. operation={}, exceptionType={}",
						operation, exception.getClass().getName());
			}
		};
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			safeCallback.run();
			return;
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				safeCallback.run();
			}
		});
	}

	private void requireActiveAndUnexpired(TableGroup tableGroup, Instant now) {
		if (tableGroup.getStatus() != TableGroupStatus.ACTIVE) {
			throw new SoundConnectException(ErrorType.TABLE_GROUP_NOT_FOUND);
		}
		if (tableGroup.getExpiresAt() == null || !tableGroup.getExpiresAt().isAfter(now)) {
			throw new SoundConnectException(ErrorType.TABLE_END_DATE_PASSED);
		}
	}

	private boolean isActiveAndUnexpired(TableGroup tableGroup, Instant now) {
		return tableGroup.getStatus() == TableGroupStatus.ACTIVE
				&& tableGroup.getExpiresAt() != null
				&& tableGroup.getExpiresAt().isAfter(now);
	}

	private Optional<TableGroupParticipant> findParticipant(TableGroup tableGroup, UUID userId) {
		return Optional.ofNullable(tableGroup.getParticipants()).orElseGet(Set::of).stream()
				.filter(participant -> Objects.equals(participant.getUserId(), userId))
				.findFirst();
	}

	private long acceptedParticipantCount(TableGroup tableGroup) {
		return Optional.ofNullable(tableGroup.getParticipants()).orElseGet(Set::of).stream()
				.filter(participant -> participant.getStatus() == ParticipantStatus.ACCEPTED)
				.count();
	}

	private long pendingParticipantCount(TableGroup tableGroup) {
		return Optional.ofNullable(tableGroup.getParticipants()).orElseGet(Set::of).stream()
				.filter(participant -> participant.getStatus() == ParticipantStatus.PENDING)
				.count();
	}

	private List<UUID> acceptedParticipantIdsExcludingOwner(TableGroup tableGroup) {
		return Optional.ofNullable(tableGroup.getParticipants()).orElseGet(Set::of).stream()
				.filter(participant -> participant.getStatus() == ParticipantStatus.ACCEPTED)
				.map(TableGroupParticipant::getUserId)
				.filter(Objects::nonNull)
				.filter(userId -> !userId.equals(tableGroup.getOwnerId()))
				.distinct()
				.toList();
	}

	private void removePendingParticipants(TableGroup tableGroup) {
		Set<TableGroupParticipant> participants = tableGroup.getParticipants();
		if (participants != null) {
			participants.removeIf(participant -> participant.getStatus() == ParticipantStatus.PENDING);
		}
	}

	private void pruneTerminalParticipantHistory(TableGroup tableGroup, Instant now, UUID preserveUserId) {
		Set<TableGroupParticipant> participants = tableGroup.getParticipants();
		if (participants == null || participants.isEmpty()) {
			return;
		}
		Instant cutoff = now.minus(TERMINAL_PARTICIPANT_RETENTION);
		List<TableGroupParticipant> candidates = participants.stream()
				.filter(participant -> isTerminalParticipantStatus(participant.getStatus()))
				.sorted(Comparator
						.comparing(
								TableGroupParticipant::getJoinedAt,
								Comparator.nullsLast(Comparator.reverseOrder())
						)
						.thenComparing(participant -> Objects.toString(participant.getUserId(), "")))
				.toList();
		Set<TableGroupParticipant> keep = Collections.newSetFromMap(new IdentityHashMap<>());
		candidates.stream()
				.filter(participant -> Objects.equals(participant.getUserId(), preserveUserId))
				.findFirst()
				.ifPresent(keep::add);
		for (TableGroupParticipant participant : candidates) {
			if (keep.size() >= MAX_TERMINAL_PARTICIPANT_RECORDS) {
				break;
			}
			if (participant.getJoinedAt() != null && !participant.getJoinedAt().isBefore(cutoff)) {
				keep.add(participant);
			}
		}
		participants.removeIf(participant -> isTerminalParticipantStatus(participant.getStatus())
				&& !keep.contains(participant));
	}

	private boolean isCurrentParticipantStatus(ParticipantStatus status) {
		return status == ParticipantStatus.ACCEPTED || status == ParticipantStatus.PENDING;
	}

	private boolean isTerminalParticipantStatus(ParticipantStatus status) {
		return status == ParticipantStatus.REJECTED
				|| status == ParticipantStatus.KICKED
				|| status == ParticipantStatus.LEFT;
	}

	private String normalizeJoinNote(String joinNote) {
		if (joinNote == null || joinNote.isBlank()) {
			return null;
		}
		String normalized = joinNote.trim();
		if (normalized.length() > 256) {
			throw new SoundConnectException(ErrorType.VALIDATION_ERROR);
		}
		return normalized;
	}

	private String normalizeVenueName(String venueName) {
		if (venueName == null || venueName.isBlank()) {
			return null;
		}
		String normalized = venueName.trim();
		if (normalized.length() > 64) {
			throw new SoundConnectException(ErrorType.VALIDATION_ERROR);
		}
		return normalized;
	}

	private String normalizeRequiredDescription(String description) {
		if (description == null) {
			throw new SoundConnectException(ErrorType.VALIDATION_ERROR);
		}
		int start = 0;
		int end = description.length();
		while (start < end) {
			int codePoint = description.codePointAt(start);
			if (!isDescriptionBoundaryWhitespace(codePoint)) {
				break;
			}
			start += Character.charCount(codePoint);
		}
		while (end > start) {
			int codePoint = description.codePointBefore(end);
			if (!isDescriptionBoundaryWhitespace(codePoint)) {
				break;
			}
			end -= Character.charCount(codePoint);
		}
		if (start == end) {
			throw new SoundConnectException(ErrorType.VALIDATION_ERROR);
		}
		String normalized = description.substring(start, end);
		if (normalized.codePointCount(0, normalized.length()) > MAX_DESCRIPTION_LENGTH) {
			throw new SoundConnectException(ErrorType.VALIDATION_ERROR);
		}
		return normalized;
	}

	private boolean isDescriptionBoundaryWhitespace(int codePoint) {
		return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
	}

	private String requireRegisteredVenueName(Venue venue) {
		String normalized = normalizeVenueName(venue.getName());
		if (normalized == null) {
			throw new SoundConnectException(ErrorType.VENUE_NOT_FOUND);
		}
		return normalized;
	}

	private TableGroupVenueOptionDto toVenueOptionDto(
			TableGroupVenueOptionProjection venue,
			String profilePictureUrl
	) {
		return new TableGroupVenueOptionDto(
				venue.getId(),
				venue.getName(),
				profilePictureUrl,
				venue.getAddress(),
				venue.getCityId(),
				venue.getCityName(),
				venue.getDistrictId(),
				venue.getDistrictName(),
				venue.getNeighborhoodId(),
				venue.getNeighborhoodName()
		);
	}

	private UUID createRequestKey(
			UUID ownerId,
			TableGroupCreateRequestDto request,
			String normalizedVenueName,
			String normalizedDescription
	) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			updateFingerprintField(digest, "schema", "table-group-create:v2");
			updateFingerprintField(digest, "ownerId", ownerId.toString());
			updateFingerprintField(digest, "venueId", Objects.toString(request.venueId(), null));
			updateFingerprintField(digest, "venueName", normalizedVenueName);
			updateFingerprintField(digest, "description", normalizedDescription);
			updateFingerprintField(digest, "maxPersonCount", Integer.toString(request.maxPersonCount()));
			updateFingerprintField(digest, "genderPrefs.count",
					Integer.toString(request.genderPrefs().size()));
			for (int index = 0; index < request.genderPrefs().size(); index++) {
				updateFingerprintField(digest, "genderPrefs[" + index + "]",
						request.genderPrefs().get(index));
			}
			updateFingerprintField(digest, "ageMin", Integer.toString(request.ageMin()));
			updateFingerprintField(digest, "ageMax", Integer.toString(request.ageMax()));
			// Keep the persisted field label unchanged so already-stored request
			// fingerprints remain stable across the strict-contract rollout.
			updateFingerprintField(digest, "expiresAt", request.meetingAt().toString());
			updateFingerprintField(digest, "cityId", request.cityId().toString());
			updateFingerprintField(digest, "districtId", Objects.toString(request.districtId(), null));
			updateFingerprintField(
					digest, "neighborhoodId", Objects.toString(request.neighborhoodId(), null));

			ByteBuffer bytes = ByteBuffer.wrap(digest.digest());
			long mostSignificant = bytes.getLong();
			long leastSignificant = bytes.getLong();
			mostSignificant = (mostSignificant & 0xffffffffffff0fffL) | 0x0000000000008000L;
			leastSignificant = (leastSignificant & 0x3fffffffffffffffL) | 0x8000000000000000L;
			return new UUID(mostSignificant, leastSignificant);
		} catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException("SHA-256 is unavailable", impossible);
		}
	}

	private void updateFingerprintField(MessageDigest digest, String fieldName, String value) {
		updateLengthPrefixedUtf8(digest, fieldName);
		digest.update(value == null ? (byte) 0 : (byte) 1);
		if (value != null) {
			updateLengthPrefixedUtf8(digest, value);
		}
	}

	private void updateLengthPrefixedUtf8(MessageDigest digest, String value) {
		byte[] valueBytes = value.getBytes(StandardCharsets.UTF_8);
		digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(valueBytes.length).array());
		digest.update(valueBytes);
	}

	private record UserMetadata(
			Map<UUID, String> usernames,
			Map<UUID, String> profileImages,
			Map<UUID, ListenerVisibilityMode> visibilityModes
	) {}

	private Map<String, Object> tablePayload(UUID tableGroupId, String action, Map<String, Object> extraPayload) {
		Map<String, Object> payload = new HashMap<>();
		payload.put("module", "TABLE");
		payload.put("action", action);
		payload.put("tableGroupId", tableGroupId.toString());
		if (extraPayload != null) {
			extraPayload.forEach((key, value) -> {
				if (key != null && value != null) {
					payload.put(key, value.toString());
				}
			});
		}
		return payload;
	}

}

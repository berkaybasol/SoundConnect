package com.berkayb.soundconnect.modules.tablegroup.service;

import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.location.entity.District;
import com.berkayb.soundconnect.modules.location.entity.Neighborhood;
import com.berkayb.soundconnect.modules.location.repository.CityRepository;
import com.berkayb.soundconnect.modules.location.repository.DistrictRepository;
import com.berkayb.soundconnect.modules.location.repository.NeighborhoodRepository;
import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.enums.ListenerVisibilityMode;
import com.berkayb.soundconnect.modules.profile.shared.avatar.PersonalProfileAvatarBatchResolver;
import com.berkayb.soundconnect.modules.profile.shared.identity.GhostListenerIdentity;
import com.berkayb.soundconnect.modules.profile.shared.identity.GhostListenerIdentityBatchResolver;
import com.berkayb.soundconnect.modules.profile.StudioProfile.repository.StudioProfileRepository;
import com.berkayb.soundconnect.modules.role.entity.Role;
import com.berkayb.soundconnect.modules.role.enums.RoleEnum;
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
import com.berkayb.soundconnect.modules.tablegroup.mapper.TableGroupMapper;
import com.berkayb.soundconnect.modules.tablegroup.game.service.TableGroupGameLifecycleService;
import com.berkayb.soundconnect.modules.tablegroup.notification.outbox.TableGroupNotificationOutboxService;
import com.berkayb.soundconnect.modules.tablegroup.observability.TableGroupMetrics;
import com.berkayb.soundconnect.modules.tablegroup.repository.TableGroupRepository;
import com.berkayb.soundconnect.modules.tablegroup.scheduler.TableGroupExpiryWorker;
import com.berkayb.soundconnect.modules.tablegroup.support.TableGroupEntityFinder;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.berkayb.soundconnect.modules.venue.entity.Venue;
import com.berkayb.soundconnect.modules.venue.enums.VenueStatus;
import com.berkayb.soundconnect.modules.venue.repository.VenueRepository;
import com.berkayb.soundconnect.modules.venue.repository.projection.TableGroupVenueOptionProjection;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.*;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;


import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Saf unit test: Spring context yok, sadece MockitoExtension.
 */
@ExtendWith(MockitoExtension.class)
class TableGroupServiceImplTest {
	
	@Mock
	private TableGroupNotificationOutboxService notificationOutboxService;
	
	@Mock
	private TableGroupRepository tableGroupRepository;
	
	@Mock
	private TableGroupMapper tableGroupMapper;
	
	@Mock
	private CityRepository cityRepository;
	
	@Mock
	private DistrictRepository districtRepository;
	
	@Mock
	private NeighborhoodRepository neighborhoodRepository;
	
	@Mock
	private TableGroupEntityFinder tableGroupEntityFinder;

	@Mock
	private TableGroupMessageRepository tableGroupMessageRepository;

	@Mock
	private TableGroupChatUnreadHelper unreadHelper;

	@Mock
	private UserRepository userRepository;

	@Mock
	private PersonalProfileAvatarBatchResolver personalProfileAvatarBatchResolver;

	@Mock
	private GhostListenerIdentityBatchResolver ghostListenerIdentityBatchResolver;

	@Mock
	private StudioProfileRepository studioProfileRepository;

	@Mock
	private VenueRepository venueRepository;

	@Mock
	private MediaAssetService mediaAssetService;

	@Mock
	private TableGroupRateLimitGuard rateLimitGuard;

	@Mock
	private TableGroupMetrics metrics;

	@Mock
	private TableGroupGameLifecycleService gameLifecycleService;

	@Mock
	private TableGroupExpiryWorker expiryWorker;
	
	@InjectMocks
	private TableGroupServiceImpl tableGroupService;
	
	private UUID ownerId;
	private UUID tableGroupId;
	private UUID participantId;
	
	@BeforeEach
	void setUp() {
		ownerId = UUID.randomUUID();
		tableGroupId = UUID.randomUUID();
		participantId = UUID.randomUUID();
		lenient().when(userRepository.findRoleNamesByUserId(any(UUID.class)))
				.thenReturn(Set.of(RoleEnum.ROLE_USER.name()));
		lenient().when(userRepository.existsById(any(UUID.class))).thenReturn(true);
		lenient().when(userRepository.findByIdForUpdate(any(UUID.class)))
				.thenAnswer(invocation -> Optional.of(userWithRoles(
						invocation.getArgument(0), RoleEnum.ROLE_USER)));
		lenient().when(tableGroupRepository.findOwnerIdById(any(UUID.class)))
				.thenReturn(Optional.of(ownerId));
		lenient().when(tableGroupRepository.findParticipantStatusForOwner(
				any(UUID.class), any(UUID.class), any(UUID.class)))
				.thenReturn(Optional.of(ParticipantStatus.PENDING));
		lenient().when(tableGroupRepository.findParticipantStatus(
				any(UUID.class), any(UUID.class)))
				.thenReturn(Optional.of(ParticipantStatus.ACCEPTED));
		lenient().when(tableGroupRepository.findStatusById(any(UUID.class)))
				.thenReturn(Optional.of(TableGroupStatus.ACTIVE));
	}

	private User userWithRoles(UUID userId, RoleEnum... roleNames) {
		Set<Role> roles = new HashSet<>();
		for (RoleEnum roleName : roleNames) {
			roles.add(Role.builder().id(UUID.randomUUID()).name(roleName.name()).build());
		}
		return User.builder()
				.id(userId)
				.username("user-" + userId)
				.roles(roles)
				.build();
	}

	private void stubLegacyInstitutionalFootprint(String footprint, UUID userId) {
		if ("VENUE".equals(footprint)) {
			when(venueRepository.existsByOwner_Id(userId)).thenReturn(true);
		} else {
			when(studioProfileRepository.existsByUserId(userId)).thenReturn(true);
		}
	}
	
	private TableGroup createActiveTableGroup(int maxPersonCount, Instant expiresAt) {
		TableGroup group = TableGroup.builder()
		                             .ownerId(ownerId)
		                             .createRequestKey(UUID.randomUUID())
		                             .description("Active table")
		                             .maxPersonCount(maxPersonCount)
		                             .genderPrefs(List.of("MALE", "FEMALE"))
		                             .ageMin(20)
		                             .ageMax(30)
		                             .startAt(expiresAt.minus(Duration.ofHours(1)))
		                             .meetingAt(expiresAt)
		                             .expiresAt(expiresAt)
		                             .status(TableGroupStatus.ACTIVE)
		                             .participants(new HashSet<>())
		                             .build();
		return group;
	}

	private void addRecentTerminalParticipantHistory(TableGroup group, int count, Instant now) {
		for (int index = 0; index < count; index++) {
			group.getParticipants().add(TableGroupParticipant.builder()
					.userId(UUID.randomUUID())
					.status(ParticipantStatus.REJECTED)
					.joinedAt(now.minusSeconds(index + 1L))
					.joinNote("retained terminal note " + index)
					.build());
		}
	}

	private long terminalParticipantCount(TableGroup group) {
		return group.getParticipants().stream()
				.filter(participant -> Set.of(
						ParticipantStatus.REJECTED,
						ParticipantStatus.KICKED,
						ParticipantStatus.LEFT
				).contains(participant.getStatus()))
				.count();
	}

	private TableGroupParticipantDto participantDto(
			UUID userId,
			ParticipantStatus status,
			String joinNote
	) {
		return TableGroupParticipantDto.builder()
				.userId(userId)
				.status(status)
				.joinedAt(Instant.now())
				.joinNote(joinNote)
				.build();
	}

	private TableGroupResponseDto responseWithParticipants(
			TableGroup entity,
			Set<TableGroupParticipantDto> participants
	) {
		return new TableGroupResponseDto(
				entity.getId(),
				entity.getOwnerId(),
				null,
				null,
				entity.getVenueId(),
				entity.getVenueName(),
				entity.getDescription(),
				entity.getMaxPersonCount(),
				entity.getGenderPrefs(),
				entity.getAgeMin(),
				entity.getAgeMax(),
				entity.getStartAt(),
				entity.getMeetingAt(),
				entity.getExpiresAt(),
				entity.getStatus(),
				participants,
				null,
				null,
				null
		);
	}

	private TableGroupCreateRequestDto createRequestWithDescription(String description) {
		return new TableGroupCreateRequestDto(
				null,
				"Venue",
				description,
				2,
				List.of("MALE", "FEMALE"),
				20,
				30,
				Instant.now().plusSeconds(3600),
				UUID.randomUUID(),
				null,
				null
		);
	}

	private TableGroupVenueOptionProjection venueOptionProjection(
			UUID id,
			String name,
			UUID profilePictureMediaId,
			String address,
			UUID cityId,
			String cityName,
			UUID districtId,
			String districtName,
			UUID neighborhoodId,
			String neighborhoodName
	) {
		TableGroupVenueOptionProjection projection = mock(TableGroupVenueOptionProjection.class);
		when(projection.getId()).thenReturn(id);
		when(projection.getName()).thenReturn(name);
		when(projection.getProfilePictureMediaId()).thenReturn(profilePictureMediaId);
		when(projection.getAddress()).thenReturn(address);
		when(projection.getCityId()).thenReturn(cityId);
		when(projection.getCityName()).thenReturn(cityName);
		when(projection.getDistrictId()).thenReturn(districtId);
		when(projection.getDistrictName()).thenReturn(districtName);
		when(projection.getNeighborhoodId()).thenReturn(neighborhoodId);
		when(projection.getNeighborhoodName()).thenReturn(neighborhoodName);
		return projection;
	}

	@Test
	void findVenueOptions_shouldTrimBoundAndMapOnlyTheMinimalProjection() {
		UUID venueId = UUID.randomUUID();
		UUID cityId = UUID.randomUUID();
		UUID districtId = UUID.randomUUID();
		UUID neighborhoodId = UUID.randomUUID();
		TableGroupVenueOptionProjection projection = venueOptionProjection(
				venueId, "Sound Hub", null, "Main Street 1",
				cityId, "Istanbul", districtId, "Kadikoy",
				neighborhoodId, "Caferaga"
		);
		when(venueRepository.searchTableGroupVenueOptions(
				"Sound", VenueStatus.APPROVED, PageRequest.of(0, 8)))
				.thenReturn(List.of(projection));

		List<TableGroupVenueOptionDto> result = tableGroupService.findVenueOptions("  Sound  ", 8);

		assertThat(result).containsExactly(new TableGroupVenueOptionDto(
				venueId, "Sound Hub", null, "Main Street 1",
				cityId, "Istanbul", districtId, "Kadikoy",
				neighborhoodId, "Caferaga"
		));
		assertThat(Arrays.stream(TableGroupVenueOptionDto.class.getRecordComponents())
				.map(java.lang.reflect.RecordComponent::getName))
				.containsExactly(
						"id", "name", "profilePictureUrl", "address", "cityId", "cityName",
						"districtId", "districtName", "neighborhoodId", "neighborhoodName"
				);
		verifyNoInteractions(mediaAssetService);
	}

	@Test
	void findVenueOptions_shouldResolveDistinctProfilePicturesInOneBatchAndKeepMissingOnesNullable() {
		UUID sharedMediaId = UUID.randomUUID();
		UUID missingMediaId = UUID.randomUUID();
		TableGroupVenueOptionProjection first = venueOptionProjection(
				UUID.randomUUID(), "Sound One", sharedMediaId, "Address 1",
				UUID.randomUUID(), "Istanbul", UUID.randomUUID(), "Kadikoy",
				UUID.randomUUID(), "Caferaga"
		);
		TableGroupVenueOptionProjection second = venueOptionProjection(
				UUID.randomUUID(), "Sound Two", sharedMediaId, "Address 2",
				UUID.randomUUID(), "Istanbul", UUID.randomUUID(), "Besiktas",
				UUID.randomUUID(), "Sinanpasa"
		);
		TableGroupVenueOptionProjection missingAsset = venueOptionProjection(
				UUID.randomUUID(), "Sound Three", missingMediaId, "Address 3",
				UUID.randomUUID(), "Ankara", UUID.randomUUID(), "Cankaya",
				UUID.randomUUID(), "Kizilay"
		);
		TableGroupVenueOptionProjection noProfilePicture = venueOptionProjection(
				UUID.randomUUID(), "Sound Four", null, "Address 4",
				UUID.randomUUID(), "Izmir", UUID.randomUUID(), "Konak",
				UUID.randomUUID(), "Alsancak"
		);
		when(venueRepository.searchTableGroupVenueOptions(
				"Sound", VenueStatus.APPROVED, PageRequest.of(0, 8)))
				.thenReturn(List.of(first, second, missingAsset, noProfilePicture));
		when(mediaAssetService.getDisplayUrlMap(List.of(sharedMediaId, missingMediaId)))
				.thenReturn(Map.of(sharedMediaId, "https://cdn.test/venue-thumb.jpg"));

		List<TableGroupVenueOptionDto> result = tableGroupService.findVenueOptions("Sound", 8);

		assertThat(result)
				.extracting(TableGroupVenueOptionDto::profilePictureUrl)
				.containsExactly(
						"https://cdn.test/venue-thumb.jpg",
						"https://cdn.test/venue-thumb.jpg",
						null,
						null
				);
		assertThat(result)
				.extracting(TableGroupVenueOptionDto::id)
				.containsExactly(
						first.getId(), second.getId(), missingAsset.getId(), noProfilePicture.getId());
		verify(mediaAssetService, times(1))
				.getDisplayUrlMap(List.of(sharedMediaId, missingMediaId));
		verify(mediaAssetService, never()).getDisplayUrl(any());
	}

	@Test
	void findVenueOptions_shouldRejectQueryOutsideTwoToSixtyFourCharacters() {
		for (String query : List.of("", " ", "a", "x".repeat(65))) {
			assertThatThrownBy(() -> tableGroupService.findVenueOptions(query, 8))
					.isInstanceOf(SoundConnectException.class)
					.hasFieldOrPropertyWithValue(
							"errorType", ErrorType.TABLE_GROUP_VENUE_OPTION_QUERY_INVALID);
		}
		assertThatThrownBy(() -> tableGroupService.findVenueOptions(null, 8))
				.isInstanceOf(SoundConnectException.class)
				.hasFieldOrPropertyWithValue(
						"errorType", ErrorType.TABLE_GROUP_VENUE_OPTION_QUERY_INVALID);
		verify(venueRepository, never()).searchTableGroupVenueOptions(anyString(), any(), any());
	}

	@Test
	void findVenueOptions_shouldRejectLimitOutsideOneToTen() {
		for (int limit : List.of(-1, 0, 11)) {
			assertThatThrownBy(() -> tableGroupService.findVenueOptions("Sound", limit))
					.isInstanceOf(SoundConnectException.class)
					.hasFieldOrPropertyWithValue(
							"errorType", ErrorType.TABLE_GROUP_VENUE_OPTION_LIMIT_INVALID);
		}
		verify(venueRepository, never()).searchTableGroupVenueOptions(anyString(), any(), any());
	}

	@Test
	void findVenueOptions_shouldAcceptInclusiveQueryAndLimitBounds() {
		when(venueRepository.searchTableGroupVenueOptions(
				anyString(), eq(VenueStatus.APPROVED), any(Pageable.class)))
				.thenReturn(List.of());

		assertThat(tableGroupService.findVenueOptions("ab", 1)).isEmpty();
		assertThat(tableGroupService.findVenueOptions("x".repeat(64), 10)).isEmpty();

		verify(venueRepository).searchTableGroupVenueOptions(
				"ab", VenueStatus.APPROVED, PageRequest.of(0, 1));
		verify(venueRepository).searchTableGroupVenueOptions(
				"x".repeat(64), VenueStatus.APPROVED, PageRequest.of(0, 10));
	}

	// -------------------- joinTableGroup --------------------

	@ParameterizedTest
	@EnumSource(value = RoleEnum.class, names = {"ROLE_VENUE", "ROLE_STUDIO"})
	void joinTableGroup_whenInstitutionalActor_shouldRejectWithoutMutation(
			RoleEnum institutionalRole
	) {
		when(userRepository.findRoleNamesByUserId(participantId))
				.thenReturn(Set.of(institutionalRole.name()));

		assertThatThrownBy(() -> tableGroupService.joinTableGroup(participantId, tableGroupId, null))
				.isInstanceOf(SoundConnectException.class)
				.hasFieldOrPropertyWithValue(
						"errorType", ErrorType.TABLE_GROUP_ACTOR_ROLE_FORBIDDEN);

		verify(rateLimitGuard, never()).checkJoin(any());
		verifyNoInteractions(tableGroupEntityFinder);
		verify(tableGroupRepository, never()).save(any());
	}

	@Test
	void joinTableGroup_whenActorIdIsNotAUserSuchAsBandId_shouldRejectWithoutMutation() {
		UUID bandId = UUID.randomUUID();
		when(userRepository.findRoleNamesByUserId(bandId)).thenReturn(Set.of());
		when(userRepository.existsById(bandId)).thenReturn(false);

		assertThatThrownBy(() -> tableGroupService.joinTableGroup(bandId, tableGroupId, null))
				.isInstanceOf(SoundConnectException.class)
				.hasFieldOrPropertyWithValue("errorType", ErrorType.UNAUTHORIZED);

		verify(rateLimitGuard, never()).checkJoin(any());
		verifyNoInteractions(tableGroupEntityFinder);
		verify(tableGroupRepository, never()).save(any());
	}

	@ParameterizedTest
	@ValueSource(strings = {"VENUE", "STUDIO"})
	void joinTableGroup_whenLegacyInstitutionalFootprintHasNoRole_shouldRejectBeforeQuota(
			String footprint
	) {
		when(userRepository.findRoleNamesByUserId(participantId))
				.thenReturn(Set.of(RoleEnum.ROLE_MUSICIAN.name()));
		stubLegacyInstitutionalFootprint(footprint, participantId);

		assertThatThrownBy(() -> tableGroupService.joinTableGroup(participantId, tableGroupId, null))
				.isInstanceOf(SoundConnectException.class)
				.hasFieldOrPropertyWithValue(
						"errorType", ErrorType.TABLE_GROUP_ACTOR_ROLE_FORBIDDEN);

		verify(rateLimitGuard, never()).checkJoin(any());
		verifyNoInteractions(tableGroupEntityFinder);
		verify(tableGroupRepository, never()).save(any());
	}

	@Test
	void joinTableGroup_whenRoleIsInstitutionalAtLockedDecision_shouldRejectWithoutMutation() {
		TableGroup tableGroup = createActiveTableGroup(3, Instant.now().plusSeconds(3600));
		when(userRepository.findRoleNamesByUserId(participantId))
				.thenReturn(Set.of(RoleEnum.ROLE_MUSICIAN.name()));
		when(userRepository.findByIdForUpdate(participantId))
				.thenReturn(Optional.of(userWithRoles(participantId, RoleEnum.ROLE_VENUE)));

		assertThatThrownBy(() -> tableGroupService.joinTableGroup(participantId, tableGroupId, null))
				.isInstanceOf(SoundConnectException.class)
				.hasFieldOrPropertyWithValue(
						"errorType", ErrorType.TABLE_GROUP_ACTOR_ROLE_FORBIDDEN);

		assertThat(tableGroup.getParticipants()).isEmpty();
		verify(tableGroupRepository, never()).save(any());
	}
	
	@Test
	void joinTableGroup_whenValidRequest_shouldAddPendingParticipantAndNotifyOwner() {
		// given
		UUID userId = UUID.randomUUID();
		Instant future = Instant.now().plusSeconds(7200);
		
		TableGroup tableGroup = createActiveTableGroup(3, future);
		// 🔴 ÖNEMLİ: ID null kalmasın
		tableGroup.setId(tableGroupId);
		
		// owner zaten accepted olsun
		TableGroupParticipant ownerParticipant = TableGroupParticipant.builder()
		                                                              .userId(ownerId)
		                                                              .joinedAt(Instant.now().minusSeconds(3600))
		                                                              .status(ParticipantStatus.ACCEPTED)
		                                                              .build();
		tableGroup.getParticipants().add(ownerParticipant);
		
		when(tableGroupEntityFinder.getTableGroupByIdForUpdate(tableGroupId))
				.thenReturn(tableGroup);
		
		when(tableGroupRepository.save(any(TableGroup.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));

		// when
		tableGroupService.joinTableGroup(userId, tableGroupId, null);
		
		// then
		assertThat(tableGroup.getParticipants())
				.anyMatch(p -> p.getUserId().equals(userId)
						&& p.getStatus() == ParticipantStatus.PENDING);
		
		verify(tableGroupRepository).save(tableGroup);
		verify(notificationOutboxService).enqueue(
				eq(ownerId),
				eq(NotificationType.TABLE_JOIN_REQUEST_RECEIVED),
				anyString(),
				anyString(),
				anyMap());
		verify(tableGroupRepository, never()).findOpenIdsByOwner(any(), any(), any());
		verifyNoInteractions(gameLifecycleService, tableGroupMessageRepository);
	}

	@Test
	void joinTableGroup_shouldRecordSuccessMetricOnlyAfterTransactionCommit() {
		UUID userId = UUID.randomUUID();
		TableGroup tableGroup = createActiveTableGroup(3, Instant.now().plusSeconds(3600));
		tableGroup.setId(tableGroupId);
		when(tableGroupEntityFinder.getTableGroupByIdForUpdate(tableGroupId)).thenReturn(tableGroup);

		TransactionSynchronizationManager.initSynchronization();
		try {
			tableGroupService.joinTableGroup(userId, tableGroupId, null);
			verify(metrics, never()).joinRequested();
			List<TransactionSynchronization> callbacks =
					TransactionSynchronizationManager.getSynchronizations();
			assertThat(callbacks).hasSize(1);
			callbacks.getFirst().afterCommit();
			verify(metrics).joinRequested();
		} finally {
			TransactionSynchronizationManager.clearSynchronization();
		}
	}
	
	@Test
	void joinTableGroup_whenTableGroupNotActive_shouldThrow() {
		// given
		TableGroup tableGroup = createActiveTableGroup(3, Instant.now().plusSeconds(3600));
		tableGroup.setStatus(TableGroupStatus.CANCELLED);
		
		when(tableGroupEntityFinder.getTableGroupByIdForUpdate(tableGroupId))
				.thenReturn(tableGroup);
		
		// when / then
		assertThatThrownBy(() -> tableGroupService.joinTableGroup(participantId, tableGroupId, null))
				.isInstanceOf(SoundConnectException.class)
				.hasFieldOrPropertyWithValue("errorType", ErrorType.TABLE_GROUP_NOT_FOUND);
	}
	
	@Test
	void joinTableGroup_whenExpired_shouldThrowTABLE_END_DATE_PASSED() {
		// given
		TableGroup tableGroup = createActiveTableGroup(3, Instant.now().minusSeconds(300));
		
		when(tableGroupEntityFinder.getTableGroupByIdForUpdate(tableGroupId))
				.thenReturn(tableGroup);
		
		// when / then
		assertThatThrownBy(() -> tableGroupService.joinTableGroup(participantId, tableGroupId, null))
				.isInstanceOf(SoundConnectException.class)
				.hasFieldOrPropertyWithValue("errorType", ErrorType.TABLE_END_DATE_PASSED);
	}
	
	@Test
	void joinTableGroup_whenMaxParticipantLimitReached_shouldThrow() {
		// given
		TableGroup tableGroup = createActiveTableGroup(2, Instant.now().plusSeconds(3600));
		
		TableGroupParticipant p1 = TableGroupParticipant.builder()
		                                                .userId(UUID.randomUUID())
		                                                .status(ParticipantStatus.ACCEPTED)
		                                                .joinedAt(Instant.now())
		                                                .build();
		TableGroupParticipant p2 = TableGroupParticipant.builder()
		                                                .userId(UUID.randomUUID())
		                                                .status(ParticipantStatus.ACCEPTED)
		                                                .joinedAt(Instant.now())
		                                                .build();
		
		tableGroup.getParticipants().addAll(List.of(p1, p2));
		
		when(tableGroupEntityFinder.getTableGroupByIdForUpdate(tableGroupId))
				.thenReturn(tableGroup);
		
		// when / then
		assertThatThrownBy(() -> tableGroupService.joinTableGroup(participantId, tableGroupId, null))
				.isInstanceOf(SoundConnectException.class)
				.hasFieldOrPropertyWithValue("errorType", ErrorType.MAX_PARTICIPANT_LIMIT);
	}
	
	@Test
	void joinTableGroup_whenAlreadyParticipant_shouldThrow() {
		// given
		TableGroup tableGroup = createActiveTableGroup(3, Instant.now().plusSeconds(3600));
		
		TableGroupParticipant existing = TableGroupParticipant.builder()
		                                                      .userId(participantId)
		                                                      .status(ParticipantStatus.ACCEPTED)
		                                                      .joinedAt(Instant.now())
		                                                      .build();
		tableGroup.getParticipants().add(existing);
		
		when(tableGroupEntityFinder.getTableGroupByIdForUpdate(tableGroupId))
				.thenReturn(tableGroup);
		
		// when / then
		assertThatThrownBy(() -> tableGroupService.joinTableGroup(participantId, tableGroupId, null))
				.isInstanceOf(SoundConnectException.class)
				.hasFieldOrPropertyWithValue("errorType", ErrorType.ALREADY_PARTICIPANT);
	}

	@Test
	void joinTableGroup_whenTerminalParticipantReapplies_shouldReuseAndReviveExistingRow() {
		TableGroup tableGroup = createActiveTableGroup(3, Instant.now().plusSeconds(3600));
		tableGroup.setId(tableGroupId);
		Instant previousJoinedAt = Instant.now().minusSeconds(3600);
		TableGroupParticipant rejected = TableGroupParticipant.builder()
				.userId(participantId)
				.status(ParticipantStatus.REJECTED)
				.joinedAt(previousJoinedAt)
				.joinNote("old note")
				.build();
		tableGroup.getParticipants().add(rejected);
		when(tableGroupEntityFinder.getTableGroupByIdForUpdate(tableGroupId)).thenReturn(tableGroup);

		tableGroupService.joinTableGroup(participantId, tableGroupId, "  new note  ");

		assertThat(tableGroup.getParticipants()).containsExactly(rejected);
		assertThat(rejected.getStatus()).isEqualTo(ParticipantStatus.PENDING);
		assertThat(rejected.getJoinedAt()).isAfter(previousJoinedAt);
		assertThat(rejected.getJoinNote()).isEqualTo("new note");
		verify(tableGroupRepository).save(tableGroup);
		verify(metrics).joinRequested();
	}

	@Test
	void joinTableGroup_whenAnotherRequestIsPending_shouldNotReserveLastSeat() {
		TableGroup tableGroup = createActiveTableGroup(2, Instant.now().plusSeconds(3600));
		tableGroup.setId(tableGroupId);
		tableGroup.getParticipants().add(TableGroupParticipant.builder()
				.userId(ownerId)
				.status(ParticipantStatus.ACCEPTED)
				.joinedAt(Instant.now())
				.build());
		tableGroup.getParticipants().add(TableGroupParticipant.builder()
				.userId(UUID.randomUUID())
				.status(ParticipantStatus.PENDING)
				.joinedAt(Instant.now())
				.build());
		when(tableGroupEntityFinder.getTableGroupByIdForUpdate(tableGroupId)).thenReturn(tableGroup);

		tableGroupService.joinTableGroup(participantId, tableGroupId, null);

		assertThat(tableGroup.getParticipants())
				.filteredOn(participant -> participant.getStatus() == ParticipantStatus.PENDING)
				.hasSize(2);
	}

	@Test
	void joinTableGroup_whenPendingQueueIsFull_shouldRejectWithoutGrowingAggregate() {
		TableGroup tableGroup = createActiveTableGroup(6, Instant.now().plusSeconds(3600));
		for (int index = 0; index < 50; index++) {
			tableGroup.getParticipants().add(TableGroupParticipant.builder()
					.userId(UUID.randomUUID())
					.status(ParticipantStatus.PENDING)
					.joinedAt(Instant.now())
					.build());
		}
		when(tableGroupEntityFinder.getTableGroupByIdForUpdate(tableGroupId)).thenReturn(tableGroup);

		assertThatThrownBy(() -> tableGroupService.joinTableGroup(participantId, tableGroupId, null))
				.isInstanceOf(SoundConnectException.class)
				.hasFieldOrPropertyWithValue(
						"errorType", ErrorType.TABLE_GROUP_APPLICATION_LIMIT_REACHED);
		assertThat(tableGroup.getParticipants()).hasSize(50);
		verify(tableGroupRepository, never()).save(any());
	}

	@Test
	void joinTableGroup_shouldPruneTerminalHistoryToABoundedRecentSet() {
		TableGroup tableGroup = createActiveTableGroup(6, Instant.now().plusSeconds(3600));
		tableGroup.setId(tableGroupId);
		for (int index = 0; index < 101; index++) {
			tableGroup.getParticipants().add(TableGroupParticipant.builder()
					.userId(UUID.randomUUID())
					.status(ParticipantStatus.REJECTED)
					.joinedAt(Instant.now().minusSeconds(index))
					.build());
		}
		when(tableGroupEntityFinder.getTableGroupByIdForUpdate(tableGroupId)).thenReturn(tableGroup);

		tableGroupService.joinTableGroup(participantId, tableGroupId, null);

		assertThat(tableGroup.getParticipants())
				.filteredOn(participant -> participant.getStatus() == ParticipantStatus.REJECTED)
				.hasSize(100);
		assertThat(tableGroup.getParticipants())
				.anyMatch(participant -> participant.getUserId().equals(participantId)
						&& participant.getStatus() == ParticipantStatus.PENDING);
	}

	// -------------------- approveJoinRequest --------------------

	@Test
	void approveJoinRequest_whenPendingApplicantBecameInstitutional_shouldRemainPending() {
		TableGroup tableGroup = createActiveTableGroup(3, Instant.now().plusSeconds(3600));
		tableGroup.setId(tableGroupId);
		TableGroupParticipant pending = TableGroupParticipant.builder()
				.userId(participantId)
				.status(ParticipantStatus.PENDING)
				.joinedAt(Instant.now())
				.build();
		tableGroup.getParticipants().add(pending);
		when(userRepository.findByIdForUpdate(participantId)).thenReturn(Optional.of(
				userWithRoles(participantId, RoleEnum.ROLE_MUSICIAN, RoleEnum.ROLE_STUDIO)));
		when(tableGroupEntityFinder.getTableGroupByIdForUpdate(tableGroupId)).thenReturn(tableGroup);

		assertThatThrownBy(() -> tableGroupService.approveJoinRequest(
				ownerId, tableGroupId, participantId))
				.isInstanceOf(SoundConnectException.class)
				.hasFieldOrPropertyWithValue(
						"errorType", ErrorType.TABLE_GROUP_ACTOR_ROLE_FORBIDDEN);

		assertThat(pending.getStatus()).isEqualTo(ParticipantStatus.PENDING);
		verify(tableGroupRepository, never()).save(any());
		verifyNoInteractions(notificationOutboxService);
	}

	@ParameterizedTest
	@ValueSource(strings = {"VENUE", "STUDIO"})
	void approveJoinRequest_whenPendingApplicantHasLegacyInstitutionalFootprint_shouldRemainPending(
			String footprint
	) {
		TableGroup tableGroup = createActiveTableGroup(3, Instant.now().plusSeconds(3600));
		tableGroup.setId(tableGroupId);
		TableGroupParticipant pending = TableGroupParticipant.builder()
				.userId(participantId)
				.status(ParticipantStatus.PENDING)
				.joinedAt(Instant.now())
				.build();
		tableGroup.getParticipants().add(pending);
		when(userRepository.findByIdForUpdate(participantId)).thenReturn(Optional.of(
				userWithRoles(participantId, RoleEnum.ROLE_MUSICIAN)));
		when(tableGroupEntityFinder.getTableGroupByIdForUpdate(tableGroupId)).thenReturn(tableGroup);
		stubLegacyInstitutionalFootprint(footprint, participantId);

		assertThatThrownBy(() -> tableGroupService.approveJoinRequest(
				ownerId, tableGroupId, participantId))
				.isInstanceOf(SoundConnectException.class)
				.hasFieldOrPropertyWithValue(
						"errorType", ErrorType.TABLE_GROUP_ACTOR_ROLE_FORBIDDEN);

		assertThat(pending.getStatus()).isEqualTo(ParticipantStatus.PENDING);
		verify(tableGroupRepository, never()).save(any());
		verifyNoInteractions(notificationOutboxService);
	}
	
	@Test
	void approveJoinRequest_whenOwnerApprovesPendingParticipant_shouldSetStatusAccepted() {
		// given
		TableGroup tableGroup = createActiveTableGroup(3, Instant.now().plusSeconds(3600));
		// 🔴 ID set
		tableGroup.setId(tableGroupId);
		
		TableGroupParticipant pending = TableGroupParticipant.builder()
		                                                     .userId(participantId)
		                                                     .status(ParticipantStatus.PENDING)
		                                                     .joinedAt(Instant.now())
		                                                     .build();
		tableGroup.getParticipants().add(pending);
		
		when(tableGroupEntityFinder.getTableGroupByIdForUpdate(tableGroupId))
				.thenReturn(tableGroup);
		when(tableGroupRepository.saveAndFlush(any(TableGroup.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));
		
		// when
		tableGroupService.approveJoinRequest(ownerId, tableGroupId, participantId);
		
		// then
		assertThat(pending.getStatus()).isEqualTo(ParticipantStatus.ACCEPTED);
		verify(tableGroupRepository).saveAndFlush(tableGroup);
		verify(notificationOutboxService).enqueue(any(UUID.class), any(NotificationType.class), anyString(), anyString(), anyMap());
	}

	@Test
	void approveJoinRequest_whenApplicantOwnsOpenTable_shouldAcceptThenCancelOwnedTableCompletely() {
		UUID ownedTableId = UUID.randomUUID();
		UUID existingMemberId = UUID.randomUUID();
		TableGroup target = createActiveTableGroup(3, Instant.now().plusSeconds(3600));
		target.setId(tableGroupId);
		TableGroupParticipant pending = TableGroupParticipant.builder()
				.userId(participantId)
				.status(ParticipantStatus.PENDING)
				.joinedAt(Instant.now())
				.build();
		target.getParticipants().add(pending);

		TableGroup owned = createActiveTableGroup(3, Instant.now().plusSeconds(3600));
		owned.setId(ownedTableId);
		owned.setOwnerId(participantId);
		owned.getParticipants().addAll(List.of(
				TableGroupParticipant.builder().userId(participantId)
						.status(ParticipantStatus.ACCEPTED).joinedAt(Instant.now()).build(),
				TableGroupParticipant.builder().userId(existingMemberId)
						.status(ParticipantStatus.ACCEPTED).joinedAt(Instant.now()).build()
		));
		when(tableGroupRepository.findOpenIdsByOwner(
				eq(participantId), eq(TableGroupStatus.ACTIVE), any(Instant.class)))
				.thenReturn(List.of(ownedTableId));
		when(tableGroupEntityFinder.getTableGroupByIdForUpdate(any(UUID.class)))
				.thenAnswer(invocation -> {
					UUID id = invocation.getArgument(0);
					if (tableGroupId.equals(id)) return target;
					if (ownedTableId.equals(id)) return owned;
					throw new AssertionError("Unexpected table lock: " + id);
				});
		when(tableGroupRepository.saveAndFlush(target)).thenReturn(target);
		when(tableGroupRepository.saveAllAndFlush(anyList()))
				.thenAnswer(invocation -> invocation.getArgument(0));
		doAnswer(invocation -> {
			owned.setParticipants(Collections.emptySet());
			return 1;
		}).when(gameLifecycleService).purgeForTableGroups(List.of(ownedTableId));

		tableGroupService.approveJoinRequest(ownerId, tableGroupId, participantId);

		assertThat(pending.getStatus()).isEqualTo(ParticipantStatus.ACCEPTED);
		assertThat(owned.getStatus()).isEqualTo(TableGroupStatus.CANCELLED);
		List<UUID> expectedLockOrder = new ArrayList<>(List.of(tableGroupId, ownedTableId));
		expectedLockOrder.sort(Comparator.naturalOrder());
		InOrder lockOrder = inOrder(tableGroupEntityFinder);
		expectedLockOrder.forEach(id ->
				lockOrder.verify(tableGroupEntityFinder).getTableGroupByIdForUpdate(id));

		InOrder lifecycleOrder = inOrder(
				tableGroupRepository, gameLifecycleService, tableGroupMessageRepository);
		lifecycleOrder.verify(tableGroupRepository).saveAndFlush(target);
		lifecycleOrder.verify(gameLifecycleService)
				.tableClosed(owned, "TABLE_OWNER_JOINED_ANOTHER_TABLE");
		lifecycleOrder.verify(tableGroupRepository).saveAllAndFlush(List.of(owned));
		lifecycleOrder.verify(gameLifecycleService).purgeForTableGroups(List.of(ownedTableId));
		lifecycleOrder.verify(tableGroupMessageRepository)
				.deleteAllByTableGroupIdIn(List.of(ownedTableId));

		ArgumentCaptor<UUID> recipientCaptor = ArgumentCaptor.forClass(UUID.class);
		ArgumentCaptor<NotificationType> typeCaptor = ArgumentCaptor.forClass(NotificationType.class);
		verify(notificationOutboxService, times(2)).enqueue(
				recipientCaptor.capture(), typeCaptor.capture(), anyString(), anyString(), anyMap());
		assertThat(recipientCaptor.getAllValues())
				.containsExactlyInAnyOrder(existingMemberId, participantId);
		assertThat(typeCaptor.getAllValues())
				.containsExactlyInAnyOrder(
						NotificationType.TABLE_CANCELLED,
						NotificationType.TABLE_JOIN_REQUEST_APPROVED);
	}

	@Test
	void approveJoinRequest_whenTargetIsFull_shouldNotCloseApplicantsOwnedTable() {
		UUID ownedTableId = UUID.randomUUID();
		TableGroup target = createActiveTableGroup(2, Instant.now().plusSeconds(3600));
		target.setId(tableGroupId);
		target.getParticipants().addAll(List.of(
				TableGroupParticipant.builder().userId(ownerId)
						.status(ParticipantStatus.ACCEPTED).joinedAt(Instant.now()).build(),
				TableGroupParticipant.builder().userId(UUID.randomUUID())
						.status(ParticipantStatus.ACCEPTED).joinedAt(Instant.now()).build(),
				TableGroupParticipant.builder().userId(participantId)
						.status(ParticipantStatus.PENDING).joinedAt(Instant.now()).build()
		));
		TableGroup owned = createActiveTableGroup(2, Instant.now().plusSeconds(3600));
		owned.setId(ownedTableId);
		owned.setOwnerId(participantId);
		when(tableGroupRepository.findOpenIdsByOwner(
				eq(participantId), eq(TableGroupStatus.ACTIVE), any(Instant.class)))
				.thenReturn(List.of(ownedTableId));
		when(tableGroupEntityFinder.getTableGroupByIdForUpdate(any(UUID.class)))
				.thenAnswer(invocation -> tableGroupId.equals(invocation.<UUID>getArgument(0))
						? target : owned);

		assertThatThrownBy(() -> tableGroupService.approveJoinRequest(
				ownerId, tableGroupId, participantId))
				.isInstanceOf(SoundConnectException.class)
				.hasFieldOrPropertyWithValue("errorType", ErrorType.MAX_PARTICIPANT_LIMIT);

		assertThat(target.getParticipants().stream()
				.filter(candidate -> participantId.equals(candidate.getUserId()))
				.findFirst().orElseThrow().getStatus())
				.isEqualTo(ParticipantStatus.PENDING);
		assertThat(owned.getStatus()).isEqualTo(TableGroupStatus.ACTIVE);
		verify(tableGroupRepository, never()).saveAndFlush(any());
		verify(tableGroupRepository, never()).saveAllAndFlush(anyList());
		verifyNoInteractions(gameLifecycleService, tableGroupMessageRepository, notificationOutboxService);
	}
	
	@Test
	void approveJoinRequest_whenNotOwner_shouldThrowForbidden() {
		UUID fakeOwner = UUID.randomUUID();
		
		// when / then
		assertThatThrownBy(() -> tableGroupService.approveJoinRequest(fakeOwner, tableGroupId, participantId))
				.isInstanceOf(SoundConnectException.class)
				.hasFieldOrPropertyWithValue("errorType", ErrorType.FORBIDDEN_ACCESS);
		verify(userRepository, never()).findByIdForUpdate(participantId);
		verifyNoInteractions(tableGroupEntityFinder);
	}

	@Test
	void approveJoinRequest_whenAlreadyAccepted_shouldBeIdempotent() {
		TableGroup tableGroup = createActiveTableGroup(3, Instant.now().plusSeconds(3600));
		tableGroup.setId(tableGroupId);
		TableGroupParticipant accepted = TableGroupParticipant.builder()
				.userId(participantId)
				.status(ParticipantStatus.ACCEPTED)
				.joinedAt(Instant.now())
				.build();
		tableGroup.getParticipants().add(accepted);
		when(tableGroupRepository.findParticipantStatusForOwner(
				tableGroupId, ownerId, participantId))
				.thenReturn(Optional.of(ParticipantStatus.ACCEPTED));
		when(tableGroupEntityFinder.getTableGroupByIdForUpdate(tableGroupId)).thenReturn(tableGroup);

		tableGroupService.approveJoinRequest(ownerId, tableGroupId, participantId);

		verify(tableGroupRepository, never()).save(any());
		verifyNoInteractions(notificationOutboxService);
		verify(metrics, never()).joinApproved();
	}

	@Test
	void approveJoinRequest_whenAcceptedApplicantLaterBecameInstitutional_shouldRemainIdempotent() {
		TableGroup tableGroup = createActiveTableGroup(3, Instant.now().minusSeconds(1));
		tableGroup.setId(tableGroupId);
		tableGroup.setStatus(TableGroupStatus.CANCELLED);
		tableGroup.getParticipants().add(TableGroupParticipant.builder()
				.userId(participantId)
				.status(ParticipantStatus.ACCEPTED)
				.joinedAt(Instant.now())
				.build());
		when(tableGroupRepository.findParticipantStatusForOwner(
				tableGroupId, ownerId, participantId))
				.thenReturn(Optional.of(ParticipantStatus.ACCEPTED));
		when(tableGroupEntityFinder.getTableGroupByIdForUpdate(tableGroupId)).thenReturn(tableGroup);

		tableGroupService.approveJoinRequest(ownerId, tableGroupId, participantId);

		verify(userRepository, never()).findByIdForUpdate(participantId);
		verify(tableGroupRepository, never()).findOpenIdsByOwner(any(), any(), any());
		verify(venueRepository, never()).existsByOwner_Id(participantId);
		verify(studioProfileRepository, never()).existsByUserId(participantId);
		verify(tableGroupRepository, never()).save(any());
		verifyNoInteractions(notificationOutboxService);
	}

	@Test
	void approveJoinRequest_whenParticipantIdDoesNotBelongToTable_shouldNotLockUserOrTable() {
		when(tableGroupRepository.findParticipantStatusForOwner(
				tableGroupId, ownerId, participantId)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> tableGroupService.approveJoinRequest(
				ownerId, tableGroupId, participantId))
				.isInstanceOf(SoundConnectException.class)
				.hasFieldOrPropertyWithValue("errorType", ErrorType.PARTICIPANT_NOT_FOUND);

		verify(userRepository, never()).findByIdForUpdate(participantId);
		verifyNoInteractions(tableGroupEntityFinder);
		verify(tableGroupRepository, never()).findOpenIdsByOwner(any(), any(), any());
	}

	@Test
	void approveJoinRequest_whenTargetExpiresWhileWaitingForLocks_shouldNotApproveOrCloseOwnedTable() {
		TableGroup target = createActiveTableGroup(3, Instant.now().plusSeconds(3600));
		target.setId(tableGroupId);
		TableGroupParticipant pending = TableGroupParticipant.builder()
				.userId(participantId).status(ParticipantStatus.PENDING)
				.joinedAt(Instant.now()).build();
		target.getParticipants().add(pending);
		Instant[] discoveryCutoff = new Instant[1];
		when(tableGroupRepository.findOpenIdsByOwner(
				eq(participantId), eq(TableGroupStatus.ACTIVE), any(Instant.class)))
				.thenAnswer(invocation -> {
					discoveryCutoff[0] = invocation.getArgument(2);
					return List.of();
				});
		when(tableGroupEntityFinder.getTableGroupByIdForUpdate(tableGroupId))
				.thenAnswer(invocation -> {
					Instant boundary = discoveryCutoff[0].plusMillis(5);
					target.setExpiresAt(boundary);
					while (!Instant.now().isAfter(boundary)) {
						Thread.onSpinWait();
					}
					return target;
				});

		assertThatThrownBy(() -> tableGroupService.approveJoinRequest(
				ownerId, tableGroupId, participantId))
				.isInstanceOf(SoundConnectException.class)
				.hasFieldOrPropertyWithValue("errorType", ErrorType.TABLE_END_DATE_PASSED);

		assertThat(pending.getStatus()).isEqualTo(ParticipantStatus.PENDING);
		verify(tableGroupRepository, never()).saveAndFlush(any());
		verify(tableGroupRepository, never()).saveAllAndFlush(anyList());
		verifyNoInteractions(gameLifecycleService, tableGroupMessageRepository, notificationOutboxService);
	}
	
	// -------------------- rejectJoinRequest --------------------
	
	@Test
	void rejectJoinRequest_whenOwnerRejectsPending_shouldSetStatusRejected() {
		// given
		TableGroup tableGroup = createActiveTableGroup(3, Instant.now().plusSeconds(3600));
		addRecentTerminalParticipantHistory(tableGroup, 100, Instant.now());
		// 🔴 ID set
		tableGroup.setId(tableGroupId);
		
		TableGroupParticipant pending = TableGroupParticipant.builder()
		                                                     .userId(participantId)
		                                                     .status(ParticipantStatus.PENDING)
		                                                     .joinedAt(Instant.now())
		                                                     .build();
		tableGroup.getParticipants().add(pending);
		
		when(tableGroupEntityFinder.getTableGroupByIdForUpdate(tableGroupId))
				.thenReturn(tableGroup);
		when(tableGroupRepository.save(any(TableGroup.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));
		
		// when
		tableGroupService.rejectJoinRequest(ownerId, tableGroupId, participantId);
		
		// then
		assertThat(pending.getStatus()).isEqualTo(ParticipantStatus.REJECTED);
		assertThat(tableGroup.getParticipants()).contains(pending);
		assertThat(terminalParticipantCount(tableGroup)).isEqualTo(100);
		verify(tableGroupRepository).save(tableGroup);
		verify(notificationOutboxService).enqueue(any(UUID.class), any(NotificationType.class), anyString(), anyString(), anyMap());
	}
	
	// -------------------- leaveTableGroup --------------------
	
	@Test
	void leaveTableGroup_whenOwnerTriesToLeave_shouldThrow() {
		// when / then
		assertThatThrownBy(() -> tableGroupService.leaveTableGroup(ownerId, tableGroupId))
				.isInstanceOf(SoundConnectException.class)
				.hasFieldOrPropertyWithValue("errorType", ErrorType.OWNER_CANNOT_LEAVE);
		verifyNoInteractions(tableGroupEntityFinder);
	}

	@Test
	void leaveTableGroup_whenCallerIsNotParticipant_shouldRejectBeforeAggregateLock() {
		when(tableGroupRepository.findParticipantStatus(tableGroupId, participantId))
				.thenReturn(Optional.empty());

		assertThatThrownBy(() -> tableGroupService.leaveTableGroup(participantId, tableGroupId))
				.isInstanceOf(SoundConnectException.class)
				.hasFieldOrPropertyWithValue("errorType", ErrorType.PARTICIPANT_NOT_FOUND);

		verifyNoInteractions(tableGroupEntityFinder);
	}
	
	@Test
	void leaveTableGroup_whenAcceptedParticipantLeaves_shouldSetStatusLeftAndNotifyOwner() {
		// given
		TableGroup tableGroup = createActiveTableGroup(3, Instant.now().plusSeconds(3600));
		addRecentTerminalParticipantHistory(tableGroup, 100, Instant.now());
		TableGroupParticipant accepted = TableGroupParticipant.builder()
		                                                      .userId(participantId)
		                                                      .status(ParticipantStatus.ACCEPTED)
		                                                      .joinedAt(Instant.now())
		                                                      .build();
		tableGroup.getParticipants().add(accepted);
		
		when(tableGroupEntityFinder.getTableGroupByIdForUpdate(tableGroupId))
				.thenReturn(tableGroup);
		when(tableGroupRepository.save(any(TableGroup.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));
		
		// when
		tableGroupService.leaveTableGroup(participantId, tableGroupId);
		
		// then
		assertThat(accepted.getStatus()).isEqualTo(ParticipantStatus.LEFT);
		assertThat(tableGroup.getParticipants()).contains(accepted);
		assertThat(terminalParticipantCount(tableGroup)).isEqualTo(100);
		verify(gameLifecycleService).participantRemoved(tableGroup, participantId, "PLAYER_LEFT");
		verify(tableGroupRepository).save(tableGroup);
		verify(notificationOutboxService).enqueue(any(UUID.class), any(NotificationType.class), anyString(), anyString(), anyMap());
	}
	
	// -------------------- removeParticipantFromTableGroup (kick) --------------------
	
	@Test
	void removeParticipant_whenOwnerKicksAcceptedParticipant_shouldSetStatusKickedAndNotify() {
		// given
		TableGroup tableGroup = createActiveTableGroup(3, Instant.now().plusSeconds(3600));
		addRecentTerminalParticipantHistory(tableGroup, 100, Instant.now());
		TableGroupParticipant accepted = TableGroupParticipant.builder()
		                                                      .userId(participantId)
		                                                      .status(ParticipantStatus.ACCEPTED)
		                                                      .joinedAt(Instant.now())
		                                                      .build();
		tableGroup.getParticipants().add(accepted);
		when(tableGroupRepository.findParticipantStatusForOwner(
				tableGroupId, ownerId, participantId))
				.thenReturn(Optional.of(ParticipantStatus.ACCEPTED));
		
		when(tableGroupEntityFinder.getTableGroupByIdForUpdate(tableGroupId))
				.thenReturn(tableGroup);
		when(tableGroupRepository.save(any(TableGroup.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));
		
		// when
		tableGroupService.removeParticipantFromTableGroup(ownerId, tableGroupId, participantId);
		
		// then
		assertThat(accepted.getStatus()).isEqualTo(ParticipantStatus.KICKED);
		assertThat(tableGroup.getParticipants()).contains(accepted);
		assertThat(terminalParticipantCount(tableGroup)).isEqualTo(100);
		verify(gameLifecycleService).participantRemoved(tableGroup, participantId, "PLAYER_REMOVED");
		verify(notificationOutboxService).enqueue(any(UUID.class), any(NotificationType.class), anyString(), anyString(), anyMap());
	}

	@Test
	void rejectJoinRequest_whenCallerIsNotOwner_shouldRejectBeforeAggregateLock() {
		UUID fakeOwner = UUID.randomUUID();

		assertThatThrownBy(() -> tableGroupService.rejectJoinRequest(
				fakeOwner, tableGroupId, participantId))
				.isInstanceOf(SoundConnectException.class)
				.hasFieldOrPropertyWithValue("errorType", ErrorType.FORBIDDEN_ACCESS);

		verify(tableGroupRepository, never()).findParticipantStatusForOwner(any(), any(), any());
		verifyNoInteractions(tableGroupEntityFinder);
	}

	@Test
	void removeParticipant_whenCallerIsNotOwner_shouldRejectBeforeAggregateLock() {
		UUID fakeOwner = UUID.randomUUID();

		assertThatThrownBy(() -> tableGroupService.removeParticipantFromTableGroup(
				fakeOwner, tableGroupId, participantId))
				.isInstanceOf(SoundConnectException.class)
				.hasFieldOrPropertyWithValue("errorType", ErrorType.FORBIDDEN_ACCESS);

		verifyNoInteractions(tableGroupEntityFinder);
	}
	
	// -------------------- cancelTableGroup --------------------
	
	@Test
	void cancelTableGroup_whenOwnerCancels_shouldSetStatusCancelledAndNotifyAcceptedParticipants() {
		// given
		TableGroup tableGroup = createActiveTableGroup(3, Instant.now().plusSeconds(3600));
		tableGroup.setId(tableGroupId);
		
		TableGroupParticipant p1 = TableGroupParticipant.builder()
		                                                .userId(ownerId) // owner
		                                                .status(ParticipantStatus.ACCEPTED)
		                                                .build();
		TableGroupParticipant p2 = TableGroupParticipant.builder()
		                                                .userId(participantId)
		                                                .status(ParticipantStatus.ACCEPTED)
		                                                .build();
		
		tableGroup.getParticipants().addAll(List.of(p1, p2));
		
		when(tableGroupEntityFinder.getTableGroupByIdForUpdate(tableGroupId))
				.thenReturn(tableGroup);
		when(tableGroupRepository.saveAllAndFlush(anyList()))
				.thenAnswer(invocation -> invocation.getArgument(0));
		doAnswer(invocation -> {
			// The real bulk deletes clear the persistence context. Simulate the
			// now-detached lazy collection becoming unavailable after purge.
			tableGroup.setParticipants(Collections.emptySet());
			return 1;
		}).when(gameLifecycleService).purgeForTableGroups(List.of(tableGroupId));

		// when
		tableGroupService.cancelTableGroup(ownerId, tableGroupId);
		
		// then
		assertThat(tableGroup.getStatus()).isEqualTo(TableGroupStatus.CANCELLED);
		verify(gameLifecycleService).tableClosed(tableGroup, "TABLE_CANCELLED");
		verify(gameLifecycleService).purgeForTableGroups(List.of(tableGroupId));
		verify(tableGroupRepository).saveAllAndFlush(List.of(tableGroup));
		verify(tableGroupMessageRepository).deleteAllByTableGroupIdIn(List.of(tableGroupId));
		// owner haric accepted olanlara notification gider
		verify(notificationOutboxService, times(1)).enqueue(any(UUID.class), any(NotificationType.class), anyString(), anyString(), anyMap());
	}

	@Test
	void cancelTableGroup_whenCallerIsNotOwner_shouldRejectBeforeAggregateLock() {
		UUID fakeOwner = UUID.randomUUID();

		assertThatThrownBy(() -> tableGroupService.cancelTableGroup(fakeOwner, tableGroupId))
				.isInstanceOf(SoundConnectException.class)
				.hasFieldOrPropertyWithValue("errorType", ErrorType.FORBIDDEN_ACCESS);

		verify(tableGroupRepository, never()).findStatusById(any());
		verifyNoInteractions(tableGroupEntityFinder);
	}

	@Test
	void cancelTableGroup_whenAlreadyCancelled_shouldBeIdempotent() {
		TableGroup tableGroup = createActiveTableGroup(3, Instant.now().plusSeconds(3600));
		tableGroup.setStatus(TableGroupStatus.CANCELLED);
		when(tableGroupEntityFinder.getTableGroupByIdForUpdate(tableGroupId)).thenReturn(tableGroup);

		tableGroupService.cancelTableGroup(ownerId, tableGroupId);

		verify(tableGroupRepository, never()).save(any());
		verifyNoInteractions(tableGroupMessageRepository, notificationOutboxService);
		verify(unreadHelper, never()).clearAllUnreadForTableGroup(any());
		verify(metrics, never()).cancelled();
	}

	@Test
	void cancelTableGroup_shouldRemovePendingApplicationsBeforePersistenceAndKeepAcceptedRecipients() {
		UUID acceptedId = UUID.randomUUID();
		UUID pendingId = UUID.randomUUID();
		TableGroup tableGroup = createActiveTableGroup(3, Instant.now().plusSeconds(3600));
		tableGroup.setId(tableGroupId);
		TableGroupParticipant accepted = TableGroupParticipant.builder()
				.userId(acceptedId)
				.status(ParticipantStatus.ACCEPTED)
				.joinedAt(Instant.now())
				.build();
		TableGroupParticipant pending = TableGroupParticipant.builder()
				.userId(pendingId)
				.status(ParticipantStatus.PENDING)
				.joinedAt(Instant.now())
				.joinNote("private application note")
				.build();
		tableGroup.getParticipants().addAll(List.of(accepted, pending));
		when(tableGroupEntityFinder.getTableGroupByIdForUpdate(tableGroupId)).thenReturn(tableGroup);

		tableGroupService.cancelTableGroup(ownerId, tableGroupId);

		assertThat(tableGroup.getParticipants()).containsExactly(accepted);
		verify(tableGroupRepository).saveAllAndFlush(List.of(tableGroup));
		verify(notificationOutboxService).enqueue(
				eq(acceptedId), eq(NotificationType.TABLE_CANCELLED),
				anyString(), anyString(), anyMap());
		verify(notificationOutboxService, never()).enqueue(
				eq(pendingId), any(), anyString(), anyString(), anyMap());
	}

	// -------------------- createTableGroup --------------------

	@Test
	void createTableGroup_whenOwnerHasOpenTable_shouldRejectBeforeRateLimitAndValidationReads() {
		UUID existingTableId = UUID.randomUUID();
		TableGroup existing = createActiveTableGroup(3, Instant.now().plusSeconds(3600));
		existing.setId(existingTableId);
		when(tableGroupRepository.findOpenIdsByOwner(
				eq(ownerId), eq(TableGroupStatus.ACTIVE), any(Instant.class)))
				.thenReturn(List.of(existingTableId));
		when(tableGroupEntityFinder.getTableGroupByIdForUpdate(existingTableId))
				.thenReturn(existing);

		assertThatThrownBy(() -> tableGroupService.createTableGroup(
				ownerId, createRequestWithDescription("Yeni masa")))
				.isInstanceOf(SoundConnectException.class)
				.hasFieldOrPropertyWithValue(
						"errorType", ErrorType.TABLE_GROUP_OWNER_ACTIVE_EXISTS);

		verify(rateLimitGuard, never()).checkCreate(any());
		verifyNoInteractions(cityRepository, districtRepository, neighborhoodRepository);
		verify(tableGroupMapper, never()).toEntity(any());
		verify(tableGroupRepository, never()).save(any());
	}

	@Test
	void createTableGroup_whenPersistedActiveTableAlreadyExpired_shouldNotWaitForScheduler() {
		UUID staleTableId = UUID.randomUUID();
		UUID cityId = UUID.randomUUID();
		Instant expiresAt = Instant.now().plusSeconds(3600);
		TableGroup stale = createActiveTableGroup(3, Instant.now().minusSeconds(1));
		stale.setId(staleTableId);
		TableGroupCreateRequestDto request = new TableGroupCreateRequestDto(
				null, "Venue", "Yeni masa", 2, List.of("MALE", "FEMALE"), 20, 30,
				expiresAt, cityId, null, null);
		City city = new City();
		city.setId(cityId);
		TableGroup created = createActiveTableGroup(2, expiresAt);
		created.setId(UUID.randomUUID());
		when(tableGroupRepository.findOpenIdsByOwner(
				eq(ownerId), eq(TableGroupStatus.ACTIVE), any(Instant.class)))
				.thenReturn(List.of(staleTableId));
		when(tableGroupEntityFinder.getTableGroupByIdForUpdate(staleTableId)).thenReturn(stale);
		when(cityRepository.findById(cityId)).thenReturn(Optional.of(city));
		when(tableGroupMapper.toEntity(request)).thenReturn(created);
		when(tableGroupRepository.save(created)).thenReturn(created);
		when(tableGroupMapper.toDto(created))
				.thenReturn(responseWithParticipants(created, Set.of()));

		TableGroupResponseDto result = tableGroupService.createTableGroup(ownerId, request);

		assertThat(result.id()).isEqualTo(created.getId());
		verify(rateLimitGuard).checkCreate(ownerId);
		verify(tableGroupRepository).save(created);
	}

	@Test
	void createTableGroup_whenOwnedTableExpiresWhileWaitingForLock_shouldAllowCreate() {
		UUID staleTableId = UUID.randomUUID();
		UUID cityId = UUID.randomUUID();
		Instant requestedExpiry = Instant.now().plusSeconds(3600);
		TableGroup stale = createActiveTableGroup(3, requestedExpiry);
		stale.setId(staleTableId);
		TableGroupCreateRequestDto request = new TableGroupCreateRequestDto(
				null, "Venue", "Yeni masa", 2, List.of("MALE", "FEMALE"), 20, 30,
				requestedExpiry, cityId, null, null);
		City city = new City();
		city.setId(cityId);
		TableGroup created = createActiveTableGroup(2, requestedExpiry);
		created.setId(UUID.randomUUID());
		Instant[] discoveryCutoff = new Instant[1];
		when(tableGroupRepository.findOpenIdsByOwner(
				eq(ownerId), eq(TableGroupStatus.ACTIVE), any(Instant.class)))
				.thenAnswer(invocation -> {
					discoveryCutoff[0] = invocation.getArgument(2);
					return List.of(staleTableId);
				});
		when(tableGroupEntityFinder.getTableGroupByIdForUpdate(staleTableId))
				.thenAnswer(invocation -> {
					Instant boundary = discoveryCutoff[0].plusMillis(5);
					stale.setExpiresAt(boundary);
					while (!Instant.now().isAfter(boundary)) {
						Thread.onSpinWait();
					}
					return stale;
				});
		when(cityRepository.findById(cityId)).thenReturn(Optional.of(city));
		when(tableGroupMapper.toEntity(request)).thenReturn(created);
		when(tableGroupRepository.save(created)).thenReturn(created);
		when(tableGroupMapper.toDto(created)).thenReturn(responseWithParticipants(created, Set.of()));

		assertThat(tableGroupService.createTableGroup(ownerId, request).id())
				.isEqualTo(created.getId());

		verify(rateLimitGuard).checkCreate(ownerId);
		verify(tableGroupRepository).save(created);
	}

	@Test
	void createTableGroup_whenDescriptionIsNullOrUnicodeBlank_shouldRejectBeforeSideEffects() {
		for (String description : Arrays.asList(null, " \t\n ", "\u00A0\u2003\u3000")) {
			TableGroupCreateRequestDto request = new TableGroupCreateRequestDto(
					null, "Venue", description, 2, List.of("MALE", "FEMALE"), 20, 30,
					Instant.now().plusSeconds(3600), UUID.randomUUID(), null, null);

			assertThatThrownBy(() -> tableGroupService.createTableGroup(ownerId, request))
					.isInstanceOf(SoundConnectException.class)
					.hasFieldOrPropertyWithValue("errorType", ErrorType.VALIDATION_ERROR);
		}

		verifyNoInteractions(tableGroupRepository, rateLimitGuard);
		verify(userRepository, never()).findByIdForUpdate(any());
	}

	@Test
	void createTableGroup_whenDescriptionExceedsMaximum_shouldRejectBeforeSideEffects() {
		assertThatThrownBy(() -> tableGroupService.createTableGroup(
				ownerId, createRequestWithDescription("x".repeat(281))))
				.isInstanceOf(SoundConnectException.class)
				.hasFieldOrPropertyWithValue("errorType", ErrorType.VALIDATION_ERROR);

		verifyNoInteractions(tableGroupRepository, rateLimitGuard);
		verify(userRepository, never()).findByIdForUpdate(any());
	}

	@Test
	void createTableGroup_shouldApplyMaximumAfterUnicodeTrimAndCountCodePoints() {
		UUID cityId = UUID.randomUUID();
		City city = new City();
		city.setId(cityId);
		String content = "😀".repeat(280);
		TableGroupCreateRequestDto request = new TableGroupCreateRequestDto(
				null, "Venue", "\u00A0" + content + "\u3000", 2,
				List.of("MALE", "FEMALE"), 20, 30,
				Instant.now().plusSeconds(3600), cityId, null, null);
		TableGroup entity = createActiveTableGroup(2, request.meetingAt());
		when(cityRepository.findById(cityId)).thenReturn(Optional.of(city));
		when(tableGroupMapper.toEntity(request)).thenReturn(entity);
		when(tableGroupRepository.save(entity)).thenReturn(entity);
		when(tableGroupMapper.toDto(entity)).thenAnswer(invocation ->
				responseWithParticipants(invocation.getArgument(0), Set.of()));

		TableGroupResponseDto result = tableGroupService.createTableGroup(ownerId, request);

		assertThat(entity.getDescription()).isEqualTo(content);
		assertThat(result.description()).isEqualTo(content);
		assertThat(result.description().codePointCount(0, result.description().length())).isEqualTo(280);
	}

	@ParameterizedTest
	@EnumSource(value = RoleEnum.class, names = {"ROLE_VENUE", "ROLE_STUDIO"})
	void createTableGroup_whenInstitutionalOwner_shouldRejectBeforeReplayOrRateLimit(
			RoleEnum institutionalRole
	) {
		TableGroupCreateRequestDto request = new TableGroupCreateRequestDto(
				null, "Venue", "Masa açıklaması", 2, List.of("MALE", "FEMALE"), 20, 30,
				Instant.now().plusSeconds(3600), UUID.randomUUID(), null, null);
		when(userRepository.findByIdForUpdate(ownerId))
				.thenReturn(Optional.of(userWithRoles(ownerId, institutionalRole)));

		assertThatThrownBy(() -> tableGroupService.createTableGroup(ownerId, request))
				.isInstanceOf(SoundConnectException.class)
				.hasFieldOrPropertyWithValue(
						"errorType", ErrorType.TABLE_GROUP_ACTOR_ROLE_FORBIDDEN);

		verify(tableGroupRepository, never()).findByOwnerIdAndCreateRequestKey(any(), any());
		verify(rateLimitGuard, never()).checkCreate(any());
		verify(tableGroupRepository, never()).save(any());
	}

	@Test
	void createTableGroup_whenMusicianAlsoHasVenueRole_shouldFailClosed() {
		TableGroupCreateRequestDto request = new TableGroupCreateRequestDto(
				null, "Venue", "Masa açıklaması", 2, List.of("MALE", "FEMALE"), 20, 30,
				Instant.now().plusSeconds(3600), UUID.randomUUID(), null, null);
		when(userRepository.findByIdForUpdate(ownerId)).thenReturn(Optional.of(
				userWithRoles(ownerId, RoleEnum.ROLE_MUSICIAN, RoleEnum.ROLE_VENUE)));

		assertThatThrownBy(() -> tableGroupService.createTableGroup(ownerId, request))
				.isInstanceOf(SoundConnectException.class)
				.hasFieldOrPropertyWithValue(
						"errorType", ErrorType.TABLE_GROUP_ACTOR_ROLE_FORBIDDEN);

		verify(tableGroupRepository, never()).findByOwnerIdAndCreateRequestKey(any(), any());
		verify(rateLimitGuard, never()).checkCreate(any());
	}

	@ParameterizedTest
	@ValueSource(strings = {"VENUE", "STUDIO"})
	void createTableGroup_whenLegacyInstitutionalFootprintHasNoRole_shouldReject(
			String footprint
	) {
		TableGroupCreateRequestDto request = new TableGroupCreateRequestDto(
				null, "Venue", "Masa açıklaması", 2, List.of("MALE", "FEMALE"), 20, 30,
				Instant.now().plusSeconds(3600), UUID.randomUUID(), null, null);
		when(userRepository.findByIdForUpdate(ownerId)).thenReturn(Optional.of(
				userWithRoles(ownerId, RoleEnum.ROLE_MUSICIAN)));
		stubLegacyInstitutionalFootprint(footprint, ownerId);

		assertThatThrownBy(() -> tableGroupService.createTableGroup(ownerId, request))
				.isInstanceOf(SoundConnectException.class)
				.hasFieldOrPropertyWithValue(
						"errorType", ErrorType.TABLE_GROUP_ACTOR_ROLE_FORBIDDEN);

		verify(tableGroupRepository, never()).findByOwnerIdAndCreateRequestKey(any(), any());
		verify(rateLimitGuard, never()).checkCreate(any());
		verify(tableGroupRepository, never()).save(any());
	}

	@Test
	void createTableGroup_whenValidRequestWithOnlyCity_shouldCreateActiveGroupAndAddOwnerAsAccepted() {
		// given
		UUID cityId = UUID.randomUUID();

		TableGroupCreateRequestDto request = new TableGroupCreateRequestDto(
				null,               // venueId
				"Some Venue",       // venueName
				"\u00A0\u2003Masa açıklaması\u3000\n", // description
				3,                  // maxPersonCount
				List.of("MALE", "FEMALE", "OTHER"), // genderPrefs size == maxPersonCount? burada 3
				20,                 // ageMin
				30,                 // ageMax
				Instant.now().plusSeconds(7200), // meetingAt future
				cityId,
				null,
				null
		);

		City city = new City();
		city.setId(cityId);

		when(cityRepository.findById(cityId)).thenReturn(Optional.of(city));
		when(userRepository.findByIdForUpdate(ownerId)).thenReturn(Optional.of(
				User.builder().id(ownerId).username("owner").build()));

		TableGroup entity = createActiveTableGroup(3, request.meetingAt());
		when(tableGroupMapper.toEntity(request)).thenReturn(entity);
		when(tableGroupRepository.save(any(TableGroup.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));
		when(tableGroupMapper.toDto(any(TableGroup.class))).thenAnswer(invocation ->
				responseWithParticipants(invocation.getArgument(0), Set.of()));
		
		// when
		Instant beforeCreate = Instant.now();
		TableGroupResponseDto result = tableGroupService.createTableGroup(ownerId, request);
		Instant afterCreate = Instant.now();
		
		// then
		assertThat(result).isNotNull();
		assertThat(entity.getOwnerId()).isEqualTo(ownerId);
		assertThat(entity.getStatus()).isEqualTo(TableGroupStatus.ACTIVE);
		assertThat(entity.getDescription()).isEqualTo("Masa açıklaması");
		assertThat(result.description()).isEqualTo("Masa açıklaması");
		assertThat(entity.getStartAt()).isBetween(beforeCreate, afterCreate);
		assertThat(entity.getMeetingAt()).isEqualTo(request.meetingAt());
		assertThat(entity.getExpiresAt())
				.isEqualTo(entity.getStartAt().plus(Duration.ofHours(24)));
		assertThat(result.meetingAt()).isEqualTo(request.meetingAt());
		assertThat(result.expiresAt()).isEqualTo(entity.getExpiresAt());
		assertThat(entity.getParticipants())
				.anyMatch(p -> p.getUserId().equals(ownerId)
						&& p.getStatus() == ParticipantStatus.ACCEPTED);
		assertThat(entity.getCity()).isEqualTo(city);
	}

	@Test
	void createTableGroup_whenVenueIsOmitted_shouldPersistAndReturnNullableVenueFields() {
		UUID cityId = UUID.randomUUID();
		City city = new City();
		city.setId(cityId);
		District district = District.builder().id(UUID.randomUUID()).city(city).build();
		Neighborhood neighborhood = Neighborhood.builder()
				.id(UUID.randomUUID())
				.district(district)
				.build();
		TableGroupCreateRequestDto request = new TableGroupCreateRequestDto(
				null,
				null,
				"Mekanı daha sonra netleştireceğiz",
				2,
				List.of("MALE", "FEMALE"),
				20,
				30,
				Instant.now().plusSeconds(3600),
				cityId,
				district.getId(),
				neighborhood.getId()
		);
		TableGroup entity = createActiveTableGroup(2, request.meetingAt());
		entity.setId(UUID.randomUUID());
		when(cityRepository.findById(cityId)).thenReturn(Optional.of(city));
		when(districtRepository.findById(district.getId())).thenReturn(Optional.of(district));
		when(neighborhoodRepository.findById(neighborhood.getId()))
				.thenReturn(Optional.of(neighborhood));
		when(tableGroupMapper.toEntity(request)).thenReturn(entity);
		when(tableGroupRepository.save(entity)).thenReturn(entity);
		when(tableGroupMapper.toDto(entity)).thenAnswer(invocation ->
				responseWithParticipants(invocation.getArgument(0), Set.of()));

		TableGroupResponseDto result = tableGroupService.createTableGroup(ownerId, request);

		assertThat(entity.getVenueId()).isNull();
		assertThat(entity.getVenueName()).isNull();
		assertThat(result.venueId()).isNull();
		assertThat(result.venueName()).isNull();
		assertThat(result.description()).isEqualTo("Mekanı daha sonra netleştireceğiz");
		assertThat(entity.getCity()).isSameAs(city);
		assertThat(entity.getDistrict()).isSameAs(district);
		assertThat(entity.getNeighborhood()).isSameAs(neighborhood);
		verify(venueRepository, never()).findById(any());
		verify(tableGroupRepository).save(entity);
	}

	@Test
	void createTableGroup_whenVenueIdAndAnyNameFieldAreBothProvided_shouldThrowConflict() {
		for (String venueName : List.of("VenueName", " \t ")) {
			TableGroupCreateRequestDto request = new TableGroupCreateRequestDto(
					UUID.randomUUID(),
					venueName,
					"Masa açıklaması",
					3,
					List.of("MALE", "FEMALE", "OTHER"),
					20,
					30,
					Instant.now().plusSeconds(3600),
					UUID.randomUUID(),
					null,
					null
			);

			assertThatThrownBy(() -> tableGroupService.createTableGroup(ownerId, request))
					.isInstanceOf(SoundConnectException.class)
					.hasFieldOrPropertyWithValue(
							"errorType", ErrorType.VENUE_ID_AND_NAME_CONFLICT);
		}
	}
	
	@Test
	void createTableGroup_whenMeetingAtIsInPast_shouldThrowTABLE_END_DATE_PASSED() {
		// given
		TableGroupCreateRequestDto request = new TableGroupCreateRequestDto(
				null,
				"Some Venue",
				"Masa açıklaması",
				3,
				List.of("MALE", "FEMALE", "OTHER"),
				20,
				30,
				Instant.now().minusSeconds(3600),
				UUID.randomUUID(),
				null,
				null
		);
		
		// when / then
		assertThatThrownBy(() -> tableGroupService.createTableGroup(ownerId, request))
				.isInstanceOf(SoundConnectException.class)
				.hasFieldOrPropertyWithValue("errorType", ErrorType.TABLE_END_DATE_PASSED);
	}

	@Test
	void createTableGroup_whenMeetingAtExceedsTwentyFourHours_shouldRejectDuration() {
		TableGroupCreateRequestDto request = new TableGroupCreateRequestDto(
				null,
				"Some Venue",
				"Masa açıklaması",
				3,
				List.of("MALE", "FEMALE", "OTHER"),
				20,
				30,
				Instant.now().plusSeconds(25 * 3600L),
				UUID.randomUUID(),
				null,
				null
		);

		assertThatThrownBy(() -> tableGroupService.createTableGroup(ownerId, request))
				.isInstanceOf(SoundConnectException.class)
				.hasFieldOrPropertyWithValue("errorType", ErrorType.TABLE_GROUP_DURATION_INVALID);
	}

	@Test
	void createTableGroup_whenNeighborhoodHasNoDistrict_shouldRejectHierarchy() {
		UUID cityId = UUID.randomUUID();
		City city = new City();
		city.setId(cityId);
		when(cityRepository.findById(cityId)).thenReturn(Optional.of(city));
		TableGroupCreateRequestDto request = new TableGroupCreateRequestDto(
				null,
				"Some Venue",
				"Masa açıklaması",
				2,
				List.of("MALE", "FEMALE"),
				20,
				30,
				Instant.now().plusSeconds(3600),
				cityId,
				null,
				UUID.randomUUID()
		);

		assertThatThrownBy(() -> tableGroupService.createTableGroup(ownerId, request))
				.isInstanceOf(SoundConnectException.class)
				.hasFieldOrPropertyWithValue("errorType", ErrorType.NEIGHBORHOOD_DISTRICT_MISMATCH);
	}

	@Test
	void createTableGroup_whenRegisteredVenueDoesNotExist_shouldRejectVenue() {
		UUID venueId = UUID.randomUUID();
		when(venueRepository.findById(venueId)).thenReturn(Optional.empty());
		TableGroupCreateRequestDto request = new TableGroupCreateRequestDto(
				venueId,
				null,
				"Masa açıklaması",
				2,
				List.of("MALE", "FEMALE"),
				20,
				30,
				Instant.now().plusSeconds(3600),
				UUID.randomUUID(),
				null,
				null
		);

		assertThatThrownBy(() -> tableGroupService.createTableGroup(ownerId, request))
				.isInstanceOf(SoundConnectException.class)
				.hasFieldOrPropertyWithValue("errorType", ErrorType.VENUE_NOT_FOUND);
	}

	@Test
	void createTableGroup_whenRegisteredVenueIsPending_shouldRejectVenue() {
		UUID venueId = UUID.randomUUID();
		when(venueRepository.findById(venueId)).thenReturn(Optional.of(
				Venue.builder().id(venueId).status(VenueStatus.PENDING).build()));
		TableGroupCreateRequestDto request = new TableGroupCreateRequestDto(
				venueId, null, "Masa açıklaması", 2, List.of("MALE", "FEMALE"), 20, 30,
				Instant.now().plusSeconds(3600), UUID.randomUUID(), null, null);

		assertThatThrownBy(() -> tableGroupService.createTableGroup(ownerId, request))
				.isInstanceOf(SoundConnectException.class)
				.hasFieldOrPropertyWithValue("errorType", ErrorType.VENUE_NOT_FOUND);
	}

	@Test
	void createTableGroup_whenApprovedVenueLocationDiffers_shouldRejectMismatch() {
		UUID venueId = UUID.randomUUID();
		City venueCity = new City();
		venueCity.setId(UUID.randomUUID());
		District venueDistrict = District.builder().id(UUID.randomUUID()).city(venueCity).build();
		Neighborhood venueNeighborhood = Neighborhood.builder()
				.id(UUID.randomUUID()).district(venueDistrict).build();
		Venue venue = Venue.builder()
				.id(venueId).name("Venue").status(VenueStatus.APPROVED).city(venueCity)
				.district(venueDistrict).neighborhood(venueNeighborhood).build();
		UUID requestedCityId = UUID.randomUUID();
		City requestedCity = new City();
		requestedCity.setId(requestedCityId);
		when(venueRepository.findById(venueId)).thenReturn(Optional.of(venue));
		when(cityRepository.findById(requestedCityId)).thenReturn(Optional.of(requestedCity));
		TableGroupCreateRequestDto request = new TableGroupCreateRequestDto(
				venueId, null, "Masa açıklaması", 2, List.of("MALE", "FEMALE"), 20, 30,
				Instant.now().plusSeconds(3600), requestedCityId, null, null);

		assertThatThrownBy(() -> tableGroupService.createTableGroup(ownerId, request))
				.isInstanceOf(SoundConnectException.class)
				.hasFieldOrPropertyWithValue("errorType", ErrorType.TABLE_GROUP_VENUE_LOCATION_MISMATCH);
	}

	@Test
	void createTableGroup_whenApprovedVenueLocationMatches_shouldDeriveFullHierarchy() {
		UUID venueId = UUID.randomUUID();
		UUID cityId = UUID.randomUUID();
		City city = new City();
		city.setId(cityId);
		District district = District.builder().id(UUID.randomUUID()).city(city).build();
		Neighborhood neighborhood = Neighborhood.builder()
				.id(UUID.randomUUID()).district(district).build();
		Venue venue = Venue.builder()
				.id(venueId).name("  Canonical Venue  ").status(VenueStatus.APPROVED).city(city)
				.district(district).neighborhood(neighborhood).build();
		TableGroupCreateRequestDto request = new TableGroupCreateRequestDto(
				venueId, null, "Masa açıklaması", 2, List.of("MALE", "FEMALE"), 20, 30,
				Instant.now().plusSeconds(3600), cityId, null, null);
		TableGroup entity = createActiveTableGroup(2, request.meetingAt());
		entity.setVenueId(venueId);
		when(venueRepository.findById(venueId)).thenReturn(Optional.of(venue));
		when(cityRepository.findById(cityId)).thenReturn(Optional.of(city));
		when(userRepository.findByIdForUpdate(ownerId)).thenReturn(Optional.of(
				User.builder().id(ownerId).username("owner").build()));
		when(tableGroupMapper.toEntity(request)).thenReturn(entity);
		when(tableGroupRepository.save(entity)).thenReturn(entity);
		when(tableGroupMapper.toDto(entity)).thenAnswer(invocation ->
				responseWithParticipants(invocation.getArgument(0), Set.of()));

		TableGroupResponseDto result = tableGroupService.createTableGroup(ownerId, request);

		assertThat(entity.getCity()).isSameAs(city);
		assertThat(entity.getDistrict()).isSameAs(district);
		assertThat(entity.getNeighborhood()).isSameAs(neighborhood);
		assertThat(entity.getVenueId()).isEqualTo(venueId);
		assertThat(entity.getVenueName()).isEqualTo("Canonical Venue");
		assertThat(result.venueName()).isEqualTo("Canonical Venue");
	}

	@Test
	void createTableGroup_whenApprovedVenueNameIsBlank_shouldFailClosed() {
		UUID venueId = UUID.randomUUID();
		TableGroupCreateRequestDto request = new TableGroupCreateRequestDto(
				venueId, null, "Masa açıklaması", 2, List.of("MALE", "FEMALE"), 20, 30,
				Instant.now().plusSeconds(3600), UUID.randomUUID(), null, null);
		when(venueRepository.findById(venueId)).thenReturn(Optional.of(
				Venue.builder()
						.id(venueId)
						.name("   ")
						.status(VenueStatus.APPROVED)
						.build()));

		assertThatThrownBy(() -> tableGroupService.createTableGroup(ownerId, request))
				.isInstanceOf(SoundConnectException.class)
				.hasFieldOrPropertyWithValue("errorType", ErrorType.VENUE_NOT_FOUND);

		verify(tableGroupRepository, never()).save(any());
	}

	@Test
	void createTableGroup_whenManualNameEqualsRegisteredName_shouldRemainCustomAndUnlinked() {
		UUID cityId = UUID.randomUUID();
		City city = new City();
		city.setId(cityId);
		TableGroupCreateRequestDto request = new TableGroupCreateRequestDto(
				null, "Canonical Venue", "Masa açıklaması", 2, List.of("MALE", "FEMALE"), 20, 30,
				Instant.now().plusSeconds(3600), cityId, null, null);
		TableGroup entity = createActiveTableGroup(2, request.meetingAt());
		when(cityRepository.findById(cityId)).thenReturn(Optional.of(city));
		when(tableGroupMapper.toEntity(request)).thenReturn(entity);
		when(tableGroupRepository.save(entity)).thenReturn(entity);
		when(tableGroupMapper.toDto(entity)).thenAnswer(invocation ->
				responseWithParticipants(invocation.getArgument(0), Set.of()));

		TableGroupResponseDto result = tableGroupService.createTableGroup(ownerId, request);

		assertThat(entity.getVenueId()).isNull();
		assertThat(entity.getVenueName()).isEqualTo("Canonical Venue");
		assertThat(result.venueId()).isNull();
		assertThat(result.venueName()).isEqualTo("Canonical Venue");
		verify(venueRepository, never()).findById(any());
	}

	@Test
	void createTableGroup_whenSameRequestIsRetried_shouldReplayExistingAggregate() {
		UUID cityId = UUID.randomUUID();
		TableGroupCreateRequestDto request = new TableGroupCreateRequestDto(
				null, "Venue", "Masa açıklaması", 2, List.of("MALE", "FEMALE"), 20, 30,
				Instant.parse("2026-08-18T12:00:00Z"), cityId, null, null);
		TableGroup existing = createActiveTableGroup(2, request.meetingAt());
		existing.setId(tableGroupId);
		when(userRepository.findByIdForUpdate(ownerId)).thenReturn(Optional.of(
				User.builder().id(ownerId).username("owner").build()));
		when(tableGroupRepository.findByOwnerIdAndCreateRequestKey(eq(ownerId), any(UUID.class)))
				.thenReturn(Optional.of(existing));
		when(tableGroupMapper.toDto(existing)).thenReturn(responseWithParticipants(existing, Set.of()));

		TableGroupResponseDto replayed = tableGroupService.createTableGroup(ownerId, request);

		assertThat(replayed.id()).isEqualTo(tableGroupId);
		verify(tableGroupRepository, never()).save(any());
		verify(tableGroupMapper, never()).toEntity(any());
		verify(rateLimitGuard, never()).checkCreate(any());
		verify(tableGroupRepository, never())
				.findOpenIdsByOwner(any(), any(), any());
		verify(venueRepository).existsByOwner_Id(ownerId);
		verify(studioProfileRepository).existsByUserId(ownerId);
		verify(venueRepository, never()).findById(any());
		verifyNoInteractions(cityRepository, districtRepository, neighborhoodRepository);
		verify(metrics, never()).created();
	}

	@Test
	void createTableGroup_shouldUseNormalizedDescriptionInIdempotencyFingerprint() {
		UUID cityId = UUID.randomUUID();
		Instant meetingAt = Instant.parse("2026-09-02T12:00:00Z");
		TableGroupCreateRequestDto first = new TableGroupCreateRequestDto(
				null, "Venue", "Açıklama A", 2, List.of("MALE", "FEMALE"), 20, 30,
				meetingAt, cityId, null, null);
		TableGroupCreateRequestDto equivalentAfterTrim = new TableGroupCreateRequestDto(
				null, "Venue", " \tAçıklama A\n ", 2, List.of("MALE", "FEMALE"), 20, 30,
				meetingAt, cityId, null, null);
		TableGroupCreateRequestDto different = new TableGroupCreateRequestDto(
				null, "Venue", "Açıklama B", 2, List.of("MALE", "FEMALE"), 20, 30,
				meetingAt, cityId, null, null);
		TableGroup existing = createActiveTableGroup(2, meetingAt);
		existing.setId(tableGroupId);
		when(tableGroupRepository.findByOwnerIdAndCreateRequestKey(eq(ownerId), any(UUID.class)))
				.thenReturn(Optional.of(existing));
		when(tableGroupMapper.toDto(existing))
				.thenReturn(responseWithParticipants(existing, Set.of()));

		tableGroupService.createTableGroup(ownerId, first);
		tableGroupService.createTableGroup(ownerId, equivalentAfterTrim);
		tableGroupService.createTableGroup(ownerId, different);

		ArgumentCaptor<UUID> keyCaptor = ArgumentCaptor.forClass(UUID.class);
		verify(tableGroupRepository, times(3))
				.findByOwnerIdAndCreateRequestKey(eq(ownerId), keyCaptor.capture());
		assertThat(keyCaptor.getAllValues().get(0))
				.isEqualTo(keyCaptor.getAllValues().get(1))
				.isNotEqualTo(keyCaptor.getAllValues().get(2));
		verify(tableGroupRepository, never()).save(any());
		verify(rateLimitGuard, never()).checkCreate(any());
	}

	@Test
	void createTableGroup_shouldFrameFingerprintFieldsWithoutDelimiterCollisions() {
		UUID cityId = UUID.randomUUID();
		Instant meetingAt = Instant.parse("2026-09-02T12:00:00Z");
		TableGroupCreateRequestDto delimiterInVenue = new TableGroupCreateRequestDto(
				null, "Salon|Akustik", "Sohbet", 2, List.of("MALE", "FEMALE"), 20, 30,
				meetingAt, cityId, null, null);
		TableGroupCreateRequestDto delimiterAcrossFields = new TableGroupCreateRequestDto(
				null, "Salon", "Akustik|Sohbet", 2, List.of("MALE", "FEMALE"), 20, 30,
				meetingAt, cityId, null, null);
		TableGroup existing = createActiveTableGroup(2, meetingAt);
		existing.setId(tableGroupId);
		when(tableGroupRepository.findByOwnerIdAndCreateRequestKey(eq(ownerId), any(UUID.class)))
				.thenReturn(Optional.of(existing));
		when(tableGroupMapper.toDto(existing))
				.thenReturn(responseWithParticipants(existing, Set.of()));

		tableGroupService.createTableGroup(ownerId, delimiterInVenue);
		tableGroupService.createTableGroup(ownerId, delimiterAcrossFields);

		ArgumentCaptor<UUID> keyCaptor = ArgumentCaptor.forClass(UUID.class);
		verify(tableGroupRepository, times(2))
				.findByOwnerIdAndCreateRequestKey(eq(ownerId), keyCaptor.capture());
		assertThat(keyCaptor.getAllValues()).hasSize(2).doesNotHaveDuplicates();
	}
	
	// -------------------- listActiveTableGroups --------------------

	@Test
	void listActiveTableGroups_whenLocationIsOmitted_shouldUseGlobalQuery() {
		Pageable pageable = PageRequest.of(0, 10);
		Pageable expected = PageRequest.of(0, 10, Sort.by(
				Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
		when(tableGroupRepository.findByStatusAndExpiresAtAfter(
				eq(TableGroupStatus.ACTIVE), any(Instant.class), eq(expected)))
				.thenReturn(Page.empty(expected));

		Page<TableGroupResponseDto> result = tableGroupService.listActiveTableGroups(
				ownerId, null, null, null, pageable);

		assertThat(result).isEmpty();
		verify(tableGroupRepository).findByStatusAndExpiresAtAfter(
				eq(TableGroupStatus.ACTIVE), any(Instant.class), eq(expected));
		verify(tableGroupRepository, never()).findByCityIdAndStatusAndExpiresAtAfter(
				any(), any(), any(), any());
	}

	@Test
	void listMyActiveTableGroups_shouldUseDirectMembershipQuery() {
		Pageable requested = PageRequest.of(0, 10);
		Pageable expected = PageRequest.of(0, 10, Sort.by(
				Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
		when(tableGroupRepository.findActiveAccessibleByUser(
				eq(ownerId),
				eq(TableGroupStatus.ACTIVE),
				eq(ParticipantStatus.ACCEPTED),
				any(Instant.class),
				eq(expected)
		)).thenReturn(Page.empty(expected));

		Page<TableGroupResponseDto> result = tableGroupService.listMyActiveTableGroups(
				ownerId, requested);

		assertThat(result).isEmpty();
		verify(tableGroupRepository).findActiveAccessibleByUser(
				eq(ownerId),
				eq(TableGroupStatus.ACTIVE),
				eq(ParticipantStatus.ACCEPTED),
				any(Instant.class),
				eq(expected)
		);
		verify(tableGroupRepository, never()).findByStatusAndExpiresAtAfter(
				any(), any(), any());
	}

	@Test
	void listActiveTableGroups_whenDistrictHasNoCity_shouldRejectFilter() {
		assertThatThrownBy(() -> tableGroupService.listActiveTableGroups(
				ownerId, null, UUID.randomUUID(), null, PageRequest.of(0, 20)))
				.isInstanceOf(SoundConnectException.class)
				.hasFieldOrPropertyWithValue("errorType", ErrorType.DISTRICT_CITY_MISMATCH);

		verifyNoInteractions(tableGroupRepository);
	}

	@Test
	void listActiveTableGroups_whenNeighborhoodHasNoDistrict_shouldPreserveValidationRule() {
		assertThatThrownBy(() -> tableGroupService.listActiveTableGroups(
				ownerId, null, null, UUID.randomUUID(), PageRequest.of(0, 20)))
				.isInstanceOf(SoundConnectException.class)
				.hasFieldOrPropertyWithValue("errorType", ErrorType.DISTRICT_NOT_FOUND);

		verifyNoInteractions(tableGroupRepository);
	}

	@Test
	void listActiveTableGroups_whenDistrictAndNeighborhoodHaveNoCity_shouldRejectFilter() {
		assertThatThrownBy(() -> tableGroupService.listActiveTableGroups(
				ownerId, null, UUID.randomUUID(), UUID.randomUUID(), PageRequest.of(0, 20)))
				.isInstanceOf(SoundConnectException.class)
				.hasFieldOrPropertyWithValue("errorType", ErrorType.DISTRICT_CITY_MISMATCH);

		verifyNoInteractions(tableGroupRepository);
	}
	
	@Test
	void listActiveTableGroups_whenOnlyCityProvided_shouldUseCityQuery() {
		// given
		UUID cityId = UUID.randomUUID();
		Pageable pageable = PageRequest.of(0, 10);
		Pageable expected = PageRequest.of(0, 10, Sort.by(
				Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
		Page<TableGroup> page = new PageImpl<>(List.of());
		
		when(tableGroupRepository.findByCityIdAndStatusAndExpiresAtAfter(
				eq(cityId),
				eq(TableGroupStatus.ACTIVE),
				any(Instant.class),
				eq(expected)
		)).thenReturn(page);
		
		// when
		Page<TableGroupResponseDto> result =
				tableGroupService.listActiveTableGroups(ownerId, cityId, null, null, pageable);
		
		// then
		assertThat(result).isNotNull();
		verify(tableGroupRepository).findByCityIdAndStatusAndExpiresAtAfter(
				eq(cityId),
				eq(TableGroupStatus.ACTIVE),
				any(Instant.class),
				eq(expected)
		);
	}

	@Test
	void listActiveTableGroups_shouldBatchUsernamesAndNeverFanOutProfileResolution() {
		UUID cityId = UUID.randomUUID();
		UUID acceptedId = UUID.randomUUID();
		String ownerAvatarUrl = "https://cdn.example/owner-avatar.jpg";
		Pageable pageable = PageRequest.of(0, 20);
		Pageable expected = PageRequest.of(0, 20, Sort.by(
				Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
		TableGroup entity = createActiveTableGroup(3, Instant.now().plusSeconds(3600));
		entity.setId(tableGroupId);
		entity.setDescription("Kahve ve sohbet için buluşuyoruz");
		when(tableGroupRepository.findByCityIdAndStatusAndExpiresAtAfter(
				eq(cityId),
				eq(TableGroupStatus.ACTIVE),
				any(Instant.class),
				eq(expected)
		)).thenReturn(new PageImpl<>(List.of(entity), expected, 1));
		when(tableGroupMapper.toDto(entity)).thenReturn(responseWithParticipants(entity, Set.of(
				participantDto(ownerId, ParticipantStatus.ACCEPTED, null),
				participantDto(acceptedId, ParticipantStatus.ACCEPTED, null)
		)));
		when(userRepository.findAllById(any())).thenReturn(List.of(
				User.builder().id(ownerId).username("owner").build(),
				User.builder().id(acceptedId).username("accepted").build()
		));
		when(personalProfileAvatarBatchResolver.resolve(Set.of(ownerId)))
				.thenReturn(Map.of(ownerId, ownerAvatarUrl));

		Page<TableGroupResponseDto> result = tableGroupService.listActiveTableGroups(
				ownerId, cityId, null, null, pageable);

		assertThat(result.getContent()).hasSize(1);
		assertThat(result.getContent().getFirst().ownerUsername()).isEqualTo("owner");
		assertThat(result.getContent().getFirst().ownerProfileImageUrl()).isEqualTo(ownerAvatarUrl);
		assertThat(result.getContent().getFirst().description())
				.isEqualTo("Kahve ve sohbet için buluşuyoruz");
		assertThat(result.getContent().getFirst().participants())
				.extracting(TableGroupParticipantDto::profilePictureUrl)
				.containsOnlyNulls();
		verify(userRepository, times(1)).findAllById(any());
		verify(personalProfileAvatarBatchResolver).resolve(Set.of(ownerId));
	}

	@Test
	void listActiveTableGroups_shouldKeepOwnerAvatarNullWhenNoPersonalAvatarExists() {
		UUID cityId = UUID.randomUUID();
		Pageable requested = PageRequest.of(0, 20);
		Pageable expected = PageRequest.of(0, 20, Sort.by(
				Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
		TableGroup entity = createActiveTableGroup(3, Instant.now().plusSeconds(3600));
		entity.setId(tableGroupId);
		when(tableGroupRepository.findByCityIdAndStatusAndExpiresAtAfter(
				eq(cityId), eq(TableGroupStatus.ACTIVE), any(Instant.class), eq(expected)))
				.thenReturn(new PageImpl<>(List.of(entity), expected, 1));
		when(tableGroupMapper.toDto(entity)).thenReturn(responseWithParticipants(entity, Set.of()));
		when(userRepository.findAllById(any())).thenReturn(List.of(
				User.builder().id(ownerId).username("owner").build()));
		when(personalProfileAvatarBatchResolver.resolve(Set.of(ownerId))).thenReturn(Map.of());

		Page<TableGroupResponseDto> result = tableGroupService.listActiveTableGroups(
				ownerId, cityId, null, null, requested);

		assertThat(result.getContent()).singleElement()
				.extracting(TableGroupResponseDto::ownerProfileImageUrl)
				.isNull();
	}

	@Test
	void listActiveTableGroups_shouldFailOpenWhenOwnerAvatarEnrichmentThrows() {
		UUID cityId = UUID.randomUUID();
		Pageable requested = PageRequest.of(0, 20);
		Pageable expected = PageRequest.of(0, 20, Sort.by(
				Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
		TableGroup entity = createActiveTableGroup(3, Instant.now().plusSeconds(3600));
		entity.setId(tableGroupId);
		when(tableGroupRepository.findByCityIdAndStatusAndExpiresAtAfter(
				eq(cityId), eq(TableGroupStatus.ACTIVE), any(Instant.class), eq(expected)))
				.thenReturn(new PageImpl<>(List.of(entity), expected, 1));
		when(tableGroupMapper.toDto(entity)).thenReturn(responseWithParticipants(entity, Set.of()));
		when(userRepository.findAllById(any())).thenReturn(List.of(
				User.builder().id(ownerId).username("owner").build()));
		when(personalProfileAvatarBatchResolver.resolve(Set.of(ownerId)))
				.thenThrow(new IllegalStateException("avatar projection unavailable"));

		Page<TableGroupResponseDto> result = tableGroupService.listActiveTableGroups(
				ownerId, cityId, null, null, requested);

		assertThat(result.getContent()).singleElement()
				.satisfies(dto -> {
					assertThat(dto.ownerUsername()).isEqualTo("owner");
					assertThat(dto.ownerProfileImageUrl()).isNull();
				});
	}

	@Test
	void listActiveTableGroups_shouldCapSizeForceStableSortAndRejectDeepOffsets() {
		UUID cityId = UUID.randomUUID();
		Pageable expected = PageRequest.of(2, 50, Sort.by(
				Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
		when(tableGroupRepository.findByCityIdAndStatusAndExpiresAtAfter(
				eq(cityId), eq(TableGroupStatus.ACTIVE), any(Instant.class), eq(expected)))
				.thenReturn(Page.empty(expected));

		tableGroupService.listActiveTableGroups(
				ownerId, cityId, null, null, PageRequest.of(2, 500, Sort.by("ownerId")));

		verify(tableGroupRepository).findByCityIdAndStatusAndExpiresAtAfter(
				eq(cityId), eq(TableGroupStatus.ACTIVE), any(Instant.class), eq(expected));
		assertThatThrownBy(() -> tableGroupService.listActiveTableGroups(
				ownerId, cityId, null, null, PageRequest.of(1_001, 20)))
				.isInstanceOf(SoundConnectException.class)
				.hasFieldOrPropertyWithValue("errorType", ErrorType.TABLE_GROUP_PAGE_REQUEST_INVALID);
	}
	
	// -------------------- getTableGroupDetail --------------------
	
	@Test
	void getTableGroupDetail_whenExists_shouldReturnDto() {
		// given
		TableGroup entity = createActiveTableGroup(3, Instant.now().plusSeconds(3600));
		entity.setStartAt(Instant.now().minusSeconds(60));
		when(tableGroupRepository.findById(tableGroupId)).thenReturn(Optional.of(entity));
		
		TableGroupResponseDto dto = new TableGroupResponseDto(
				tableGroupId,
				ownerId,
				null,
				null,
				null,
				"Venue",
				null,
				3,
				List.of("MALE", "FEMALE", "OTHER"),
				20,
				30,
				entity.getStartAt(),
				entity.getMeetingAt(),
				entity.getExpiresAt(),
				TableGroupStatus.ACTIVE,
				Set.of(),
				null,
				null,
				null
		);
		when(tableGroupMapper.toDto(entity)).thenReturn(dto);
		
		// when
		TableGroupResponseDto result = tableGroupService.getTableGroupDetail(ownerId, tableGroupId);
		
		// then
		assertThat(result).isEqualTo(dto);
	}

	@Test
	void getTableGroupDetail_shouldProjectParticipantPrivacyByViewerRole() {
		UUID acceptedId = UUID.randomUUID();
		UUID pendingId = UUID.randomUUID();
		UUID otherPendingId = UUID.randomUUID();
		UUID terminalId = UUID.randomUUID();
		TableGroup entity = createActiveTableGroup(6, Instant.now().plusSeconds(3600));
		entity.setId(tableGroupId);
		TableGroupResponseDto raw = responseWithParticipants(entity, Set.of(
				participantDto(ownerId, ParticipantStatus.ACCEPTED, null),
				participantDto(acceptedId, ParticipantStatus.ACCEPTED, "accepted note"),
				participantDto(pendingId, ParticipantStatus.PENDING, "private note"),
				participantDto(otherPendingId, ParticipantStatus.PENDING, "other private note"),
				participantDto(terminalId, ParticipantStatus.REJECTED, "old note")
		));
		when(tableGroupRepository.findById(tableGroupId)).thenReturn(Optional.of(entity));
		when(tableGroupMapper.toDto(entity)).thenReturn(raw);

		TableGroupResponseDto outsider = tableGroupService.getTableGroupDetail(UUID.randomUUID(), tableGroupId);
		assertThat(outsider.participants())
				.extracting(TableGroupParticipantDto::userId)
				.containsExactlyInAnyOrder(ownerId, acceptedId);
		assertThat(outsider.participants())
				.extracting(TableGroupParticipantDto::joinNote)
				.containsOnlyNulls();

		TableGroupResponseDto pendingViewer = tableGroupService.getTableGroupDetail(pendingId, tableGroupId);
		assertThat(pendingViewer.participants())
				.extracting(TableGroupParticipantDto::userId)
				.containsExactlyInAnyOrder(ownerId, acceptedId, pendingId)
				.doesNotContain(otherPendingId, terminalId);
		assertThat(pendingViewer.participants())
				.extracting(TableGroupParticipantDto::joinNote)
				.containsOnlyNulls();

		TableGroupResponseDto owner = tableGroupService.getTableGroupDetail(ownerId, tableGroupId);
		assertThat(owner.participants())
				.extracting(TableGroupParticipantDto::userId)
				.containsExactlyInAnyOrder(ownerId, acceptedId, pendingId, otherPendingId)
				.doesNotContain(terminalId);
		assertThat(owner.participants().stream()
				.filter(participant -> participant.userId().equals(pendingId))
				.findFirst().orElseThrow().joinNote()).isEqualTo("private note");
	}

	@Test
	void getTableGroupDetail_shouldBatchVisibleUsernamesInOneRepositoryRead() {
		UUID acceptedId = UUID.randomUUID();
		TableGroup entity = createActiveTableGroup(3, Instant.now().plusSeconds(3600));
		entity.setId(tableGroupId);
		TableGroupResponseDto raw = responseWithParticipants(entity, Set.of(
				participantDto(ownerId, ParticipantStatus.ACCEPTED, null),
				participantDto(acceptedId, ParticipantStatus.ACCEPTED, null)
		));
		when(tableGroupRepository.findById(tableGroupId)).thenReturn(Optional.of(entity));
		when(tableGroupMapper.toDto(entity)).thenReturn(raw);
		when(userRepository.findAllById(any())).thenReturn(List.of(
				User.builder().id(ownerId).username("owner").build(),
				User.builder().id(acceptedId).username("accepted").build()
		));

		TableGroupResponseDto result = tableGroupService.getTableGroupDetail(UUID.randomUUID(), tableGroupId);

		assertThat(result.ownerUsername()).isEqualTo("owner");
		assertThat(result.participants())
				.extracting(TableGroupParticipantDto::username)
				.containsExactlyInAnyOrder("owner", "accepted");
		verify(userRepository, times(1)).findAllById(any());
		verify(userRepository, never()).findById(any());
	}

	@Test
	void getTableGroupDetail_shouldOverrideGhostOwnerAndParticipantIdentityInOneBatch() {
		UUID ghostParticipantId = UUID.randomUUID();
		UUID standardParticipantId = UUID.randomUUID();
		TableGroup entity = createActiveTableGroup(4, Instant.now().plusSeconds(3600));
		entity.setId(tableGroupId);
		TableGroupResponseDto raw = responseWithParticipants(entity, Set.of(
				participantDto(ghostParticipantId, ParticipantStatus.ACCEPTED, null),
				participantDto(standardParticipantId, ParticipantStatus.ACCEPTED, null)
		));
		when(tableGroupRepository.findById(tableGroupId)).thenReturn(Optional.of(entity));
		when(tableGroupMapper.toDto(entity)).thenReturn(raw);
		when(userRepository.findAllById(any())).thenReturn(List.of(
				User.builder().id(ownerId).username("wrong-owner-name").build(),
				User.builder().id(ghostParticipantId).username("wrong-participant-name").build(),
				User.builder().id(standardParticipantId).username("standard").build()
		));
		when(personalProfileAvatarBatchResolver.resolve(anyCollection())).thenReturn(Map.of(
				ownerId, "wrong-owner-avatar.jpg",
				ghostParticipantId, "wrong-participant-avatar.jpg",
				standardParticipantId, "standard.jpg"
		));
		when(ghostListenerIdentityBatchResolver.resolve(anyCollection())).thenReturn(Map.of(
				ownerId, new GhostListenerIdentity(
						ownerId,
						"ghost-owner",
						"listener-owner.jpg",
						ListenerVisibilityMode.GHOST
				),
				ghostParticipantId, new GhostListenerIdentity(
						ghostParticipantId,
						"ghost-participant",
						"listener-participant.jpg",
						ListenerVisibilityMode.GHOST
				)
		));

		TableGroupResponseDto result = tableGroupService.getTableGroupDetail(
				UUID.randomUUID(),
				tableGroupId
		);

		assertThat(result.ownerUsername()).isEqualTo("ghost-owner");
		assertThat(result.ownerProfileImageUrl()).isEqualTo("listener-owner.jpg");
		assertThat(result.ownerVisibilityMode()).isEqualTo(ListenerVisibilityMode.GHOST);
		assertThat(result.participants())
				.filteredOn(participant -> participant.userId().equals(ghostParticipantId))
				.singleElement()
				.satisfies(participant -> {
					assertThat(participant.username()).isEqualTo("ghost-participant");
					assertThat(participant.profilePictureUrl()).isEqualTo("listener-participant.jpg");
					assertThat(participant.visibilityMode()).isEqualTo(ListenerVisibilityMode.GHOST);
				});
		assertThat(result.participants())
				.filteredOn(participant -> participant.userId().equals(standardParticipantId))
				.singleElement()
				.satisfies(participant -> {
					assertThat(participant.username()).isEqualTo("standard");
					assertThat(participant.profilePictureUrl()).isEqualTo("standard.jpg");
					assertThat(participant.visibilityMode()).isNull();
				});
		verify(ghostListenerIdentityBatchResolver).resolve(argThat(ids ->
				ids.size() == 3
						&& ids.containsAll(Set.of(ownerId, ghostParticipantId, standardParticipantId))));
	}

	@Test
	void getTableGroupDetail_whenPersonalAvatarMissing_shouldNotFallBackToBandIdentity() {
		TableGroup entity = createActiveTableGroup(3, Instant.now().plusSeconds(3600));
		entity.setId(tableGroupId);
		TableGroupResponseDto raw = responseWithParticipants(entity, Set.of());
		when(tableGroupRepository.findById(tableGroupId)).thenReturn(Optional.of(entity));
		when(tableGroupMapper.toDto(entity)).thenReturn(raw);
		when(userRepository.findAllById(any())).thenReturn(List.of(
				userWithRoles(ownerId, RoleEnum.ROLE_MUSICIAN)));
		when(personalProfileAvatarBatchResolver.resolve(Set.of(ownerId))).thenReturn(Map.of());

		TableGroupResponseDto result = tableGroupService.getTableGroupDetail(ownerId, tableGroupId);

		assertThat(result.ownerProfileImageUrl()).isNull();
		verify(personalProfileAvatarBatchResolver).resolve(Set.of(ownerId));
	}

	@Test
	void getTableGroupDetail_whenPersonalAvatarExists_shouldUseItInsteadOfBandIdentity() {
		TableGroup entity = createActiveTableGroup(3, Instant.now().plusSeconds(3600));
		entity.setId(tableGroupId);
		TableGroupResponseDto raw = responseWithParticipants(entity, Set.of());
		when(tableGroupRepository.findById(tableGroupId)).thenReturn(Optional.of(entity));
		when(tableGroupMapper.toDto(entity)).thenReturn(raw);
		when(userRepository.findAllById(any())).thenReturn(List.of(
				userWithRoles(ownerId, RoleEnum.ROLE_MUSICIAN)));
		when(personalProfileAvatarBatchResolver.resolve(Set.of(ownerId)))
				.thenReturn(Map.of(ownerId, "personal-avatar.jpg"));

		TableGroupResponseDto result = tableGroupService.getTableGroupDetail(ownerId, tableGroupId);

		assertThat(result.ownerProfileImageUrl()).isEqualTo("personal-avatar.jpg");
	}

	@Test
	void getTableGroupDetail_shouldBatchOwnerAcceptedAndPendingPersonalAvatars() {
		UUID acceptedId = UUID.randomUUID();
		UUID organizerPendingId = UUID.randomUUID();
		UUID producerPendingId = UUID.randomUUID();
		TableGroup entity = createActiveTableGroup(6, Instant.now().plusSeconds(3600));
		entity.setId(tableGroupId);
		TableGroupResponseDto raw = responseWithParticipants(entity, Set.of(
				participantDto(acceptedId, ParticipantStatus.ACCEPTED, null),
				participantDto(organizerPendingId, ParticipantStatus.PENDING, "organizer"),
				participantDto(producerPendingId, ParticipantStatus.PENDING, "producer")
		));
		when(tableGroupRepository.findById(tableGroupId)).thenReturn(Optional.of(entity));
		when(tableGroupMapper.toDto(entity)).thenReturn(raw);
		when(personalProfileAvatarBatchResolver.resolve(anyCollection())).thenReturn(Map.of(
				ownerId, "owner.jpg",
				acceptedId, "accepted.jpg",
				organizerPendingId, "organizer.jpg",
				producerPendingId, "producer.jpg"
		));

		TableGroupResponseDto result = tableGroupService.getTableGroupDetail(ownerId, tableGroupId);

		assertThat(result.ownerProfileImageUrl()).isEqualTo("owner.jpg");
		assertThat(result.participants())
				.extracting(TableGroupParticipantDto::userId, TableGroupParticipantDto::profilePictureUrl)
				.containsExactlyInAnyOrder(
						tuple(acceptedId, "accepted.jpg"),
						tuple(organizerPendingId, "organizer.jpg"),
						tuple(producerPendingId, "producer.jpg")
				);
		verify(personalProfileAvatarBatchResolver).resolve(argThat(ids ->
				ids.size() == 4
						&& ids.containsAll(Set.of(ownerId, acceptedId, organizerPendingId, producerPendingId))));
	}

	@Test
	void getTableGroupDetail_shouldChunkFiftyPendingApplicantsAndKeepSuccessfulChunkWhenAnotherFails() {
		Set<TableGroupParticipantDto> pendingParticipants = new HashSet<>();
		Set<UUID> pendingIds = new HashSet<>();
		for (int index = 0; index < 50; index++) {
			UUID pendingId = UUID.randomUUID();
			pendingIds.add(pendingId);
			pendingParticipants.add(participantDto(pendingId, ParticipantStatus.PENDING, "pending"));
		}
		TableGroup entity = createActiveTableGroup(6, Instant.now().plusSeconds(3600));
		entity.setId(tableGroupId);
		when(tableGroupRepository.findById(tableGroupId)).thenReturn(Optional.of(entity));
		when(tableGroupMapper.toDto(entity)).thenReturn(responseWithParticipants(entity, pendingParticipants));
		List<Set<UUID>> capturedBatches = new ArrayList<>();
		when(personalProfileAvatarBatchResolver.resolve(anyCollection())).thenAnswer(invocation -> {
			Set<UUID> batch = new LinkedHashSet<>(invocation.<Collection<UUID>>getArgument(0));
			capturedBatches.add(batch);
			if (batch.contains(ownerId)) {
				return Map.of(ownerId, "owner.jpg");
			}
			throw new IllegalStateException("second avatar chunk unavailable");
		});

		TableGroupResponseDto result = tableGroupService.getTableGroupDetail(ownerId, tableGroupId);

		assertThat(result.ownerProfileImageUrl()).isEqualTo("owner.jpg");
		assertThat(result.participants()).hasSize(50);
		assertThat(capturedBatches).hasSize(2);
		assertThat(capturedBatches).allSatisfy(batch -> assertThat(batch).hasSizeLessThanOrEqualTo(50));
		Set<UUID> expectedIds = new HashSet<>(pendingIds);
		expectedIds.add(ownerId);
		Set<UUID> batchedIds = new HashSet<>();
		capturedBatches.forEach(batchedIds::addAll);
		assertThat(batchedIds).containsExactlyInAnyOrderElementsOf(expectedIds);
	}

	@Test
	void getTableGroupDetail_whenInactive_shouldHideHistoryFromNonMembers() {
		UUID acceptedId = UUID.randomUUID();
		TableGroup entity = createActiveTableGroup(3, Instant.now().minusSeconds(60));
		entity.setId(tableGroupId);
		entity.setStatus(TableGroupStatus.INACTIVE);
		entity.getParticipants().add(TableGroupParticipant.builder()
				.userId(acceptedId)
				.status(ParticipantStatus.ACCEPTED)
				.joinedAt(Instant.now().minusSeconds(600))
				.build());
		when(tableGroupRepository.findById(tableGroupId)).thenReturn(Optional.of(entity));
		when(tableGroupMapper.toDto(entity)).thenReturn(responseWithParticipants(
				entity,
				Set.of(participantDto(acceptedId, ParticipantStatus.ACCEPTED, null))
		));

		assertThatThrownBy(() -> tableGroupService.getTableGroupDetail(UUID.randomUUID(), tableGroupId))
				.isInstanceOf(SoundConnectException.class)
				.hasFieldOrPropertyWithValue("errorType", ErrorType.TABLE_GROUP_NOT_FOUND);
		assertThat(tableGroupService.getTableGroupDetail(acceptedId, tableGroupId)).isNotNull();
	}
	
	// -------------------- expireExpiredTableGroups --------------------

	@Test
	void expireExpiredTableGroupsRunsOnlyAsANonTransactionalCoordinator() throws NoSuchMethodException {
		org.springframework.transaction.annotation.Transactional transactional =
				TableGroupServiceImpl.class.getMethod("expireExpiredTableGroups")
						.getAnnotation(org.springframework.transaction.annotation.Transactional.class);

		assertThat(transactional).isNotNull();
		assertThat(transactional.propagation())
				.isEqualTo(org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED);
	}
	
	@Test
	void expireExpiredTableGroups_whenThereAreCandidates_shouldDelegateEachToTheWorker() {
		UUID firstId = UUID.randomUUID();
		UUID secondId = UUID.randomUUID();

		when(tableGroupRepository.findExpiredIds(
				eq(TableGroupStatus.ACTIVE),
				any(Instant.class),
				any(Pageable.class)
		)).thenReturn(List.of(firstId, secondId));
		when(expiryWorker.expireIfDue(any(), any())).thenReturn(true);
		
		tableGroupService.expireExpiredTableGroups();
		
		ArgumentCaptor<Instant> scanTime = ArgumentCaptor.forClass(Instant.class);
		verify(expiryWorker).expireIfDue(eq(firstId), scanTime.capture());
		verify(expiryWorker).expireIfDue(eq(secondId), scanTime.capture());
		assertThat(scanTime.getAllValues()).hasSize(2).allMatch(scanTime.getValue()::equals);
	}

	@Test
	void expireExpiredTableGroups_shouldAcquireSelectedRowsInUuidOrder() {
		UUID lowerId = UUID.fromString("00000000-0000-0000-0000-000000000001");
		UUID higherId = UUID.fromString("00000000-0000-0000-0000-000000000002");
		when(tableGroupRepository.findExpiredIds(
				eq(TableGroupStatus.ACTIVE), any(Instant.class), any(Pageable.class)))
				.thenReturn(List.of(higherId, lowerId));

		tableGroupService.expireExpiredTableGroups();

		InOrder order = inOrder(expiryWorker);
		order.verify(expiryWorker).expireIfDue(eq(lowerId), any(Instant.class));
		order.verify(expiryWorker).expireIfDue(eq(higherId), any(Instant.class));
	}
	
	@Test
	void expireExpiredTableGroups_whenNoExpiredActives_shouldReturnSilently() {
		// given
		when(tableGroupRepository.findExpiredIds(
				eq(TableGroupStatus.ACTIVE),
				any(Instant.class),
				any(Pageable.class)
		)).thenReturn(List.of());
		
		// when
		tableGroupService.expireExpiredTableGroups();
		
		// then
		verifyNoInteractions(expiryWorker);
	}
	
	@Test
	void expireExpiredTableGroups_whenOneCandidateFails_shouldContinueWithTheRest() {
		UUID poisonId = UUID.fromString("00000000-0000-0000-0000-000000000001");
		UUID healthyId = UUID.fromString("00000000-0000-0000-0000-000000000002");
		when(tableGroupRepository.findExpiredIds(
				eq(TableGroupStatus.ACTIVE),
				any(Instant.class),
				any(Pageable.class)
		)).thenReturn(List.of(poisonId, healthyId));
		when(expiryWorker.expireIfDue(eq(poisonId), any(Instant.class)))
				.thenThrow(new IllegalStateException("poison row"));
		when(expiryWorker.expireIfDue(eq(healthyId), any(Instant.class))).thenReturn(true);
		
		tableGroupService.expireExpiredTableGroups();
		
		InOrder order = inOrder(expiryWorker);
		order.verify(expiryWorker).expireIfDue(eq(poisonId), any(Instant.class));
		order.verify(expiryWorker).expireIfDue(eq(healthyId), any(Instant.class));
		verify(metrics).expiryTransitionFailed();
	}

	@Test
	void purgeRetainedChatMessages_shouldDeleteOnlyBoundedEligibleGroups() {
		List<UUID> eligible = List.of(UUID.randomUUID(), UUID.randomUUID());
		when(tableGroupMessageRepository.findTableGroupIdsEligibleForRetention(
				any(Instant.class), eq(PageRequest.of(0, 20))))
				.thenReturn(eligible);
		when(tableGroupMessageRepository.deleteAllByTableGroupIdIn(eligible)).thenReturn(42);

		assertThat(tableGroupService.purgeRetainedChatMessages()).isEqualTo(42);
		verify(gameLifecycleService).purgeForTableGroups(eligible);
		verify(tableGroupMessageRepository).deleteAllByTableGroupIdIn(eligible);
	}

	@Test
	void purgeRetainedParticipantHistory_shouldLockAndDeleteExpiredTerminalRows() {
		UUID groupId = UUID.randomUUID();
		TableGroup group = createActiveTableGroup(2, Instant.now().plusSeconds(3600));
		group.setId(groupId);
		TableGroupParticipant accepted = TableGroupParticipant.builder()
				.userId(ownerId).status(ParticipantStatus.ACCEPTED).joinedAt(Instant.now()).build();
		TableGroupParticipant expiredTerminal = TableGroupParticipant.builder()
				.userId(UUID.randomUUID()).status(ParticipantStatus.LEFT)
				.joinedAt(Instant.now().minus(Duration.ofDays(8))).build();
		group.setParticipants(new HashSet<>(Set.of(accepted, expiredTerminal)));
		when(tableGroupRepository.findIdsWithTerminalParticipantsBefore(
				anySet(), any(Instant.class), eq(PageRequest.of(0, 20))))
				.thenReturn(List.of(groupId));
		when(tableGroupEntityFinder.getTableGroupByIdForUpdate(groupId)).thenReturn(group);

		assertThat(tableGroupService.purgeRetainedParticipantHistory()).isEqualTo(1);
		assertThat(group.getParticipants()).containsExactly(accepted);
		verify(tableGroupRepository).save(group);
	}
	
}

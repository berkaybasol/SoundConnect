package com.berkayb.soundconnect.modules.tablegroup.service;

import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.location.repository.CityRepository;
import com.berkayb.soundconnect.modules.location.repository.DistrictRepository;
import com.berkayb.soundconnect.modules.location.repository.NeighborhoodRepository;
import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.modules.tablegroup.dto.request.TableGroupCreateRequestDto;
import com.berkayb.soundconnect.modules.tablegroup.dto.response.TableGroupResponseDto;
import com.berkayb.soundconnect.modules.tablegroup.entity.TableGroup;
import com.berkayb.soundconnect.modules.tablegroup.entity.TableGroupParticipant;
import com.berkayb.soundconnect.modules.tablegroup.enums.ParticipantStatus;
import com.berkayb.soundconnect.modules.tablegroup.enums.TableGroupStatus;
import com.berkayb.soundconnect.modules.tablegroup.mapper.TableGroupMapper;
import com.berkayb.soundconnect.modules.tablegroup.repository.TableGroupRepository;
import com.berkayb.soundconnect.modules.tablegroup.support.TableGroupEntityFinder;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.berkayb.soundconnect.shared.messaging.events.notification.NotificationInboundEvent;
import com.berkayb.soundconnect.shared.messaging.events.notification.NotificationProducer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.*;


import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Saf unit test: Spring context yok, sadece MockitoExtension.
 */
@ExtendWith(MockitoExtension.class)
class TableGroupServiceImplTest {
	
	@Mock
	private NotificationProducer notificationProducer;
	
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
	}
	
	private TableGroup createActiveTableGroup(int maxPersonCount, LocalDateTime expiresAt) {
		TableGroup group = TableGroup.builder()
		                             .ownerId(ownerId)
		                             .maxPersonCount(maxPersonCount)
		                             .genderPrefs(List.of("MALE", "FEMALE"))
		                             .ageMin(20)
		                             .ageMax(30)
		                             .expiresAt(expiresAt)
		                             .status(TableGroupStatus.ACTIVE)
		                             .participants(new HashSet<>())
		                             .build();
		return group;
	}
	
	// -------------------- joinTableGroup --------------------
	
	@Test
	void joinTableGroup_whenValidRequest_shouldAddPendingParticipantAndNotifyOwner() {
		// given
		UUID userId = UUID.randomUUID();
		LocalDateTime future = LocalDateTime.now().plusHours(2);
		
		TableGroup tableGroup = createActiveTableGroup(3, future);
		// 🔴 ÖNEMLİ: ID null kalmasın
		tableGroup.setId(tableGroupId);
		
		// owner zaten accepted olsun
		TableGroupParticipant ownerParticipant = TableGroupParticipant.builder()
		                                                              .userId(ownerId)
		                                                              .joinedAt(LocalDateTime.now().minusHours(1))
		                                                              .status(ParticipantStatus.ACCEPTED)
		                                                              .build();
		tableGroup.getParticipants().add(ownerParticipant);
		
		when(tableGroupEntityFinder.GetTableGroupByTableGroupId(tableGroupId))
				.thenReturn(tableGroup);
		
		when(tableGroupRepository.save(any(TableGroup.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));
		
		// when
		tableGroupService.joinTableGroup(userId, tableGroupId);
		
		// then
		assertThat(tableGroup.getParticipants())
				.anyMatch(p -> p.getUserId().equals(userId)
						&& p.getStatus() == ParticipantStatus.PENDING);
		
		verify(tableGroupRepository).save(tableGroup);
		verify(notificationProducer).publish(any(NotificationInboundEvent.class));
	}
	
	@Test
	void joinTableGroup_whenTableGroupNotActive_shouldThrow() {
		// given
		TableGroup tableGroup = createActiveTableGroup(3, LocalDateTime.now().plusHours(1));
		tableGroup.setStatus(TableGroupStatus.CANCELLED);
		
		when(tableGroupEntityFinder.GetTableGroupByTableGroupId(tableGroupId))
				.thenReturn(tableGroup);
		
		// when / then
		assertThatThrownBy(() -> tableGroupService.joinTableGroup(participantId, tableGroupId))
				.isInstanceOf(SoundConnectException.class)
				.hasFieldOrPropertyWithValue("errorType", ErrorType.TABLE_GROUP_NOT_FOUND);
	}
	
	@Test
	void joinTableGroup_whenExpired_shouldThrowTABLE_END_DATE_PASSED() {
		// given
		TableGroup tableGroup = createActiveTableGroup(3, LocalDateTime.now().minusMinutes(5));
		
		when(tableGroupEntityFinder.GetTableGroupByTableGroupId(tableGroupId))
				.thenReturn(tableGroup);
		
		// when / then
		assertThatThrownBy(() -> tableGroupService.joinTableGroup(participantId, tableGroupId))
				.isInstanceOf(SoundConnectException.class)
				.hasFieldOrPropertyWithValue("errorType", ErrorType.TABLE_END_DATE_PASSED);
	}
	
	@Test
	void joinTableGroup_whenMaxParticipantLimitReached_shouldThrow() {
		// given
		TableGroup tableGroup = createActiveTableGroup(2, LocalDateTime.now().plusHours(1));
		
		TableGroupParticipant p1 = TableGroupParticipant.builder()
		                                                .userId(UUID.randomUUID())
		                                                .status(ParticipantStatus.ACCEPTED)
		                                                .joinedAt(LocalDateTime.now())
		                                                .build();
		TableGroupParticipant p2 = TableGroupParticipant.builder()
		                                                .userId(UUID.randomUUID())
		                                                .status(ParticipantStatus.PENDING)
		                                                .joinedAt(LocalDateTime.now())
		                                                .build();
		
		tableGroup.getParticipants().addAll(List.of(p1, p2));
		
		when(tableGroupEntityFinder.GetTableGroupByTableGroupId(tableGroupId))
				.thenReturn(tableGroup);
		
		// when / then
		assertThatThrownBy(() -> tableGroupService.joinTableGroup(participantId, tableGroupId))
				.isInstanceOf(SoundConnectException.class)
				.hasFieldOrPropertyWithValue("errorType", ErrorType.MAX_PARTICIPANT_LIMIT);
	}
	
	@Test
	void joinTableGroup_whenAlreadyParticipant_shouldThrow() {
		// given
		TableGroup tableGroup = createActiveTableGroup(3, LocalDateTime.now().plusHours(1));
		
		TableGroupParticipant existing = TableGroupParticipant.builder()
		                                                      .userId(participantId)
		                                                      .status(ParticipantStatus.ACCEPTED)
		                                                      .joinedAt(LocalDateTime.now())
		                                                      .build();
		tableGroup.getParticipants().add(existing);
		
		when(tableGroupEntityFinder.GetTableGroupByTableGroupId(tableGroupId))
				.thenReturn(tableGroup);
		
		// when / then
		assertThatThrownBy(() -> tableGroupService.joinTableGroup(participantId, tableGroupId))
				.isInstanceOf(SoundConnectException.class)
				.hasFieldOrPropertyWithValue("errorType", ErrorType.ALREADY_PARTICIPANT);
	}
	
	// -------------------- approveJoinRequest --------------------
	
	@Test
	void approveJoinRequest_whenOwnerApprovesPendingParticipant_shouldSetStatusAccepted() {
		// given
		TableGroup tableGroup = createActiveTableGroup(3, LocalDateTime.now().plusHours(1));
		// 🔴 ID set
		tableGroup.setId(tableGroupId);
		
		TableGroupParticipant pending = TableGroupParticipant.builder()
		                                                     .userId(participantId)
		                                                     .status(ParticipantStatus.PENDING)
		                                                     .joinedAt(LocalDateTime.now())
		                                                     .build();
		tableGroup.getParticipants().add(pending);
		
		when(tableGroupEntityFinder.GetTableGroupByTableGroupId(tableGroupId))
				.thenReturn(tableGroup);
		when(tableGroupRepository.save(any(TableGroup.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));
		
		// when
		tableGroupService.approveJoinRequest(ownerId, tableGroupId, participantId);
		
		// then
		assertThat(pending.getStatus()).isEqualTo(ParticipantStatus.ACCEPTED);
		verify(tableGroupRepository).save(tableGroup);
		verify(notificationProducer).publish(any(NotificationInboundEvent.class));
	}
	
	@Test
	void approveJoinRequest_whenNotOwner_shouldThrowUnauthorized() {
		// given
		TableGroup tableGroup = createActiveTableGroup(3, LocalDateTime.now().plusHours(1));
		when(tableGroupEntityFinder.GetTableGroupByTableGroupId(tableGroupId))
				.thenReturn(tableGroup);
		
		UUID fakeOwner = UUID.randomUUID();
		
		// when / then
		assertThatThrownBy(() -> tableGroupService.approveJoinRequest(fakeOwner, tableGroupId, participantId))
				.isInstanceOf(SoundConnectException.class)
				.hasFieldOrPropertyWithValue("errorType", ErrorType.UNAUTHORIZED);
	}
	
	// -------------------- rejectJoinRequest --------------------
	
	@Test
	void rejectJoinRequest_whenOwnerRejectsPending_shouldSetStatusRejected() {
		// given
		TableGroup tableGroup = createActiveTableGroup(3, LocalDateTime.now().plusHours(1));
		// 🔴 ID set
		tableGroup.setId(tableGroupId);
		
		TableGroupParticipant pending = TableGroupParticipant.builder()
		                                                     .userId(participantId)
		                                                     .status(ParticipantStatus.PENDING)
		                                                     .joinedAt(LocalDateTime.now())
		                                                     .build();
		tableGroup.getParticipants().add(pending);
		
		when(tableGroupEntityFinder.GetTableGroupByTableGroupId(tableGroupId))
				.thenReturn(tableGroup);
		when(tableGroupRepository.save(any(TableGroup.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));
		
		// when
		tableGroupService.rejectJoinRequest(ownerId, tableGroupId, participantId);
		
		// then
		assertThat(pending.getStatus()).isEqualTo(ParticipantStatus.REJECTED);
		verify(tableGroupRepository).save(tableGroup);
		verify(notificationProducer).publish(any(NotificationInboundEvent.class));
	}
	
	// -------------------- leaveTableGroup --------------------
	
	@Test
	void leaveTableGroup_whenOwnerTriesToLeave_shouldThrow() {
		// given
		TableGroup tableGroup = createActiveTableGroup(3, LocalDateTime.now().plusHours(1));
		when(tableGroupEntityFinder.GetTableGroupByTableGroupId(tableGroupId))
				.thenReturn(tableGroup);
		
		// when / then
		assertThatThrownBy(() -> tableGroupService.leaveTableGroup(ownerId, tableGroupId))
				.isInstanceOf(SoundConnectException.class)
				.hasFieldOrPropertyWithValue("errorType", ErrorType.OWNER_CANNOT_LEAVE);
	}
	
	@Test
	void leaveTableGroup_whenAcceptedParticipantLeaves_shouldSetStatusLeftAndNotifyOwner() {
		// given
		TableGroup tableGroup = createActiveTableGroup(3, LocalDateTime.now().plusHours(1));
		TableGroupParticipant accepted = TableGroupParticipant.builder()
		                                                      .userId(participantId)
		                                                      .status(ParticipantStatus.ACCEPTED)
		                                                      .joinedAt(LocalDateTime.now())
		                                                      .build();
		tableGroup.getParticipants().add(accepted);
		
		when(tableGroupEntityFinder.GetTableGroupByTableGroupId(tableGroupId))
				.thenReturn(tableGroup);
		when(tableGroupRepository.save(any(TableGroup.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));
		
		// when
		tableGroupService.leaveTableGroup(participantId, tableGroupId);
		
		// then
		assertThat(accepted.getStatus()).isEqualTo(ParticipantStatus.LEFT);
		verify(tableGroupRepository).save(tableGroup);
		verify(notificationProducer).publish(any(NotificationInboundEvent.class));
	}
	
	// -------------------- removeParticipantFromTableGroup (kick) --------------------
	
	@Test
	void removeParticipant_whenOwnerKicksAcceptedParticipant_shouldSetStatusKickedAndNotify() {
		// given
		TableGroup tableGroup = createActiveTableGroup(3, LocalDateTime.now().plusHours(1));
		TableGroupParticipant accepted = TableGroupParticipant.builder()
		                                                      .userId(participantId)
		                                                      .status(ParticipantStatus.ACCEPTED)
		                                                      .joinedAt(LocalDateTime.now())
		                                                      .build();
		tableGroup.getParticipants().add(accepted);
		
		when(tableGroupEntityFinder.GetTableGroupByTableGroupId(tableGroupId))
				.thenReturn(tableGroup);
		when(tableGroupRepository.save(any(TableGroup.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));
		
		// when
		tableGroupService.removeParticipantFromTableGroup(ownerId, tableGroupId, participantId);
		
		// then
		assertThat(accepted.getStatus()).isEqualTo(ParticipantStatus.KICKED);
		verify(notificationProducer).publish(any(NotificationInboundEvent.class));
	}
	
	// -------------------- cancelTableGroup --------------------
	
	@Test
	void cancelTableGroup_whenOwnerCancels_shouldSetStatusCancelledAndNotifyAcceptedParticipants() {
		// given
		TableGroup tableGroup = createActiveTableGroup(3, LocalDateTime.now().plusHours(1));
		
		TableGroupParticipant p1 = TableGroupParticipant.builder()
		                                                .userId(ownerId) // owner
		                                                .status(ParticipantStatus.ACCEPTED)
		                                                .build();
		TableGroupParticipant p2 = TableGroupParticipant.builder()
		                                                .userId(participantId)
		                                                .status(ParticipantStatus.ACCEPTED)
		                                                .build();
		
		tableGroup.getParticipants().addAll(List.of(p1, p2));
		
		when(tableGroupEntityFinder.GetTableGroupByTableGroupId(tableGroupId))
				.thenReturn(tableGroup);
		when(tableGroupRepository.save(any(TableGroup.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));
		
		// when
		tableGroupService.cancelTableGroup(ownerId, tableGroupId);
		
		// then
		assertThat(tableGroup.getStatus()).isEqualTo(TableGroupStatus.CANCELLED);
		verify(tableGroupRepository).save(tableGroup);
		// owner haric accepted olanlara notification gider
		verify(notificationProducer, times(1)).publish(any(NotificationInboundEvent.class));
	}
	
	// -------------------- createTableGroup --------------------
	
	@Test
	void createTableGroup_whenValidRequestWithOnlyCity_shouldCreateActiveGroupAndAddOwnerAsAccepted() {
		// given
		UUID cityId = UUID.randomUUID();
		
		TableGroupCreateRequestDto request = new TableGroupCreateRequestDto(
				null,               // venueId
				"Some Venue",       // venueName
				3,                  // maxPersonCount
				List.of("MALE", "FEMALE", "OTHER"), // genderPrefs size == maxPersonCount? burada 3
				20,                 // ageMin
				30,                 // ageMax
				LocalDateTime.now().plusHours(2), // expiresAt future
				cityId,
				null,
				null
		);
		
		City city = new City();
		city.setId(cityId);
		
		when(cityRepository.findById(cityId)).thenReturn(Optional.of(city));
		
		TableGroup entity = createActiveTableGroup(3, request.expiresAt());
		when(tableGroupMapper.toEntity(request)).thenReturn(entity);
		when(tableGroupRepository.save(any(TableGroup.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));
		when(tableGroupMapper.toDto(any(TableGroup.class)))
				.thenReturn(new TableGroupResponseDto(
						UUID.randomUUID(),
						ownerId,
						null,
						"Some Venue",
						3,
						request.genderPrefs(),
						20,
						30,
						request.expiresAt(),
						TableGroupStatus.ACTIVE,
						Set.of(),
						null,
						null,
						null
				));
		
		// when
		TableGroupResponseDto result = tableGroupService.createTableGroup(ownerId, request);
		
		// then
		assertThat(result).isNotNull();
		assertThat(entity.getOwnerId()).isEqualTo(ownerId);
		assertThat(entity.getStatus()).isEqualTo(TableGroupStatus.ACTIVE);
		assertThat(entity.getParticipants())
				.anyMatch(p -> p.getUserId().equals(ownerId)
						&& p.getStatus() == ParticipantStatus.ACCEPTED);
		assertThat(entity.getCity()).isEqualTo(city);
	}
	
	@Test
	void createTableGroup_whenVenueIdAndNameBothProvided_shouldThrowConflict() {
		// given
		TableGroupCreateRequestDto request = new TableGroupCreateRequestDto(
				UUID.randomUUID(),
				"VenueName",
				3,
				List.of("MALE", "FEMALE", "OTHER"),
				20,
				30,
				LocalDateTime.now().plusHours(1),
				UUID.randomUUID(),
				null,
				null
		);
		
		// when / then
		assertThatThrownBy(() -> tableGroupService.createTableGroup(ownerId, request))
				.isInstanceOf(SoundConnectException.class)
				.hasFieldOrPropertyWithValue("errorType", ErrorType.VENUE_ID_AND_NAME_CONFLICT);
	}
	
	@Test
	void createTableGroup_whenExpiresAtInPast_shouldThrowTABLE_END_DATE_PASSED() {
		// given
		TableGroupCreateRequestDto request = new TableGroupCreateRequestDto(
				null,
				"Some Venue",
				3,
				List.of("MALE", "FEMALE", "OTHER"),
				20,
				30,
				LocalDateTime.now().minusHours(1),
				UUID.randomUUID(),
				null,
				null
		);
		
		// when / then
		assertThatThrownBy(() -> tableGroupService.createTableGroup(ownerId, request))
				.isInstanceOf(SoundConnectException.class)
				.hasFieldOrPropertyWithValue("errorType", ErrorType.TABLE_END_DATE_PASSED);
	}
	
	// -------------------- listActiveTableGroups --------------------
	
	@Test
	void listActiveTableGroups_whenOnlyCityProvided_shouldUseCityQuery() {
		// given
		UUID cityId = UUID.randomUUID();
		Pageable pageable = PageRequest.of(0, 10);
		Page<TableGroup> page = new PageImpl<>(List.of());
		
		when(tableGroupRepository.findByCityIdAndStatusAndExpiresAtAfter(
				eq(cityId),
				eq(TableGroupStatus.ACTIVE),
				any(LocalDateTime.class),
				eq(pageable)
		)).thenReturn(page);
		
		// when
		Page<TableGroupResponseDto> result =
				tableGroupService.listActiveTableGroups(cityId, null, null, pageable);
		
		// then
		assertThat(result).isNotNull();
		verify(tableGroupRepository).findByCityIdAndStatusAndExpiresAtAfter(
				eq(cityId),
				eq(TableGroupStatus.ACTIVE),
				any(LocalDateTime.class),
				eq(pageable)
		);
	}
	
	// -------------------- getTableGroupDetail --------------------
	
	@Test
	void getTableGroupDetail_whenExists_shouldReturnDto() {
		// given
		TableGroup entity = createActiveTableGroup(3, LocalDateTime.now().plusHours(1));
		when(tableGroupRepository.findById(tableGroupId)).thenReturn(Optional.of(entity));
		
		TableGroupResponseDto dto = new TableGroupResponseDto(
				tableGroupId,
				ownerId,
				null,
				"Venue",
				3,
				List.of("MALE", "FEMALE", "OTHER"),
				20,
				30,
				entity.getExpiresAt(),
				TableGroupStatus.ACTIVE,
				Set.of(),
				null,
				null,
				null
		);
		when(tableGroupMapper.toDto(entity)).thenReturn(dto);
		
		// when
		TableGroupResponseDto result = tableGroupService.getTableGroupDetail(tableGroupId);
		
		// then
		assertThat(result).isEqualTo(dto);
	}
	
	// -------------------- expireExpiredTableGroups --------------------
	
	@Test
	void expireExpiredTableGroups_whenThereAreActiveExpiredGroups_shouldMarkInactiveAndSave() {
		// given
		TableGroup g1 = createActiveTableGroup(3, LocalDateTime.now().minusMinutes(10));
		TableGroup g2 = createActiveTableGroup(4, LocalDateTime.now().minusMinutes(5));
		
		when(tableGroupRepository.findByStatusAndExpiresAtBefore(
				eq(TableGroupStatus.ACTIVE),
				any(LocalDateTime.class)
		)).thenReturn(List.of(g1, g2));
		
		when(tableGroupRepository.save(any(TableGroup.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));
		
		// when
		tableGroupService.expireExpiredTableGroups();
		
		// then
		assertThat(g1.getStatus()).isEqualTo(TableGroupStatus.INACTIVE);
		assertThat(g2.getStatus()).isEqualTo(TableGroupStatus.INACTIVE);
		verify(tableGroupRepository, times(2)).save(any(TableGroup.class));
	}
	
	@Test
	void expireExpiredTableGroups_whenNoExpiredActives_shouldReturnSilently() {
		// given
		when(tableGroupRepository.findByStatusAndExpiresAtBefore(
				eq(TableGroupStatus.ACTIVE),
				any(LocalDateTime.class)
		)).thenReturn(List.of());
		
		// when
		tableGroupService.expireExpiredTableGroups();
		
		// then
		verify(tableGroupRepository, never()).save(any(TableGroup.class));
	}
	
	@Test
	void expireExpiredTableGroups_whenGroupHasAcceptedParticipants_shouldNotifyThem() {
		// given
		UUID ownerId = UUID.randomUUID();
		UUID accepted1 = UUID.randomUUID();
		UUID accepted2 = UUID.randomUUID();
		UUID pending = UUID.randomUUID();
		
		TableGroup group = new TableGroup();
		group.setId(UUID.randomUUID());
		group.setOwnerId(ownerId);
		group.setStatus(TableGroupStatus.ACTIVE);
		group.setExpiresAt(LocalDateTime.now().minusMinutes(5));
		
		TableGroupParticipant ownerParticipant = TableGroupParticipant.builder()
		                                                              .userId(ownerId)
		                                                              .status(ParticipantStatus.ACCEPTED)
		                                                              .build();
		
		TableGroupParticipant acceptedParticipant1 = TableGroupParticipant.builder()
		                                                                  .userId(accepted1)
		                                                                  .status(ParticipantStatus.ACCEPTED)
		                                                                  .build();
		
		TableGroupParticipant acceptedParticipant2 = TableGroupParticipant.builder()
		                                                                  .userId(accepted2)
		                                                                  .status(ParticipantStatus.ACCEPTED)
		                                                                  .build();
		
		TableGroupParticipant pendingParticipant = TableGroupParticipant.builder()
		                                                                .userId(pending)
		                                                                .status(ParticipantStatus.PENDING)
		                                                                .build();
		
		group.setParticipants(new java.util.HashSet<>(
				java.util.List.of(
						ownerParticipant,
						acceptedParticipant1,
						acceptedParticipant2,
						pendingParticipant
				)
		));
		
		when(tableGroupRepository.findByStatusAndExpiresAtBefore(eq(TableGroupStatus.ACTIVE), any()))
				.thenReturn(java.util.List.of(group));
		
		// when
		tableGroupService.expireExpiredTableGroups();
		
		// then: status INACTIVE kaydedilmiş mi?
		verify(tableGroupRepository).save(argThat(g ->
				                                          g.getId().equals(group.getId())
						                                          && g.getStatus() == TableGroupStatus.INACTIVE
		));
		
		// then: accepted katılımcılara TABLE_EXPIRED notification gitmiş mi?
		ArgumentCaptor<NotificationInboundEvent> eventCaptor =
				ArgumentCaptor.forClass(NotificationInboundEvent.class);
		
		verify(notificationProducer, times(2)).publish(eventCaptor.capture());
		
		var events = eventCaptor.getAllValues();
		
		// accepted1 ve accepted2 dışında kimseye gitmemeli
		assertThat(events)
				.extracting(NotificationInboundEvent::recipientId)
				.containsExactlyInAnyOrder(accepted1, accepted2);
		
		// type ve payload doğru mu?
		events.forEach(event -> {
			assertThat(event.type()).isEqualTo(NotificationType.TABLE_EXPIRED);
			assertThat(event.payload().get("tableGroupId")).isEqualTo(group.getId());
			assertThat(event.payload().get("ownerId")).isEqualTo(ownerId);
		});
	}
	
}
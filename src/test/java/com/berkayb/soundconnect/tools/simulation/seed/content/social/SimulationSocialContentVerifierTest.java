package com.berkayb.soundconnect.tools.simulation.seed.content.social;

import com.berkayb.soundconnect.modules.event.audience.EventAudienceService;
import com.berkayb.soundconnect.modules.event.audience.EventIntent;
import com.berkayb.soundconnect.modules.event.audience.EventIntentResponse;
import com.berkayb.soundconnect.modules.overthinking.profileshare.OverthinkingProfileShareRepository;
import com.berkayb.soundconnect.modules.overthinking.repository.OverthinkingPostRepository;
import com.berkayb.soundconnect.modules.tablegroup.dto.request.TableGroupCreateRequestDto;
import com.berkayb.soundconnect.modules.tablegroup.dto.response.TableGroupParticipantDto;
import com.berkayb.soundconnect.modules.tablegroup.dto.response.TableGroupResponseDto;
import com.berkayb.soundconnect.modules.tablegroup.enums.ParticipantStatus;
import com.berkayb.soundconnect.modules.tablegroup.enums.TableGroupStatus;
import com.berkayb.soundconnect.modules.tablegroup.profileshare.TableGroupProfileShareRepository;
import com.berkayb.soundconnect.modules.tablegroup.service.TableGroupService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionAttribute;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SimulationSocialContentVerifierTest {

	@Mock private OverthinkingPostRepository overthinkingPosts;
	@Mock private OverthinkingProfileShareRepository overthinkingShares;
	@Mock private TableGroupService tableGroups;
	@Mock private TableGroupProfileShareRepository tableGroupShares;
	@Mock private EventAudienceService eventAudience;

	private SimulationSocialContentVerifier verifier;

	@BeforeEach
	void setUp() {
		verifier = new SimulationSocialContentVerifier(
				overthinkingPosts, overthinkingShares, tableGroups, tableGroupShares, eventAudience);
	}

	@Test
	void acceptsAnEndedEventWhenTheOriginalPublicationIdentityStillMatches() {
		UUID listenerId = UUID.randomUUID();
		UUID eventId = UUID.randomUUID();
		UUID postId = UUID.randomUUID();
		String note = "Takvime ekledim.";
		var item = new SimulationSocialContentCheckpoint.EventPublication(
				"event-post-listener-event-01", "listener", listenerId, "event-01",
				eventId, postId, EventIntent.GOING, note, 1);
		when(eventAudience.get(listenerId, eventId)).thenReturn(new EventIntentResponse.State(
				eventId, EventIntent.GOING, true, note, 1, Instant.parse("2026-09-11T10:00:00Z"),
				true, true, false, false, true, null, postId));

		assertThatCode(() -> verifier.verifyEventPublication(item)).doesNotThrowAnyException();
	}

	@Test
	void everyVerificationBoundaryPermitsPostgresSharedLocks() throws NoSuchMethodException {
		assertThat(transactionFor("verifyTableGroup",
				SimulationSocialContentCheckpoint.TableGroupAggregate.class,
				TableGroupCreateRequestDto.class,
				Map.class).isReadOnly()).isFalse();
		assertThat(transactionFor("verifyEventPublication",
				SimulationSocialContentCheckpoint.EventPublication.class).isReadOnly()).isFalse();
		assertThat(transactionFor("verifyOverthinking",
				SimulationSocialContentCheckpoint.OverthinkingPublication.class,
				String.class, String.class, String.class).isReadOnly()).isFalse();
		assertThat(transactionFor("verifyTableGroupPublication",
				SimulationSocialContentCheckpoint.TableGroupPublication.class,
				String.class).isReadOnly()).isFalse();
	}

	@Test
	void acceptsExpiredTableIdentityButRejectsAnyMembershipDrift() {
		UUID ownerId = UUID.randomUUID();
		UUID memberId = UUID.randomUUID();
		UUID tableId = UUID.randomUUID();
		UUID cityId = UUID.randomUUID();
		UUID districtId = UUID.randomUUID();
		UUID neighborhoodId = UUID.randomUUID();
		Instant startAt = Instant.parse("2026-09-10T10:00:00Z");
		Instant meetingAt = startAt.plus(Duration.ofHours(4));
		var item = new SimulationSocialContentCheckpoint.TableGroupAggregate(
				"table-group-01", "listener-owner", ownerId, tableId, meetingAt);
		var request = new TableGroupCreateRequestDto(
				null, "Plak Arası", "Bir açıklama", 6,
				List.of("FEMALE", "MALE", "OTHER", "FEMALE", "MALE", "OTHER"),
				21, 45, meetingAt, cityId, districtId, neighborhoodId);
		String joinNote = "Dinleyici: müzik keşifleri üzerine sohbete katılmak istiyorum.";
		Map<UUID, String> expectedParticipants = new LinkedHashMap<>();
		expectedParticipants.put(ownerId, null);
		expectedParticipants.put(memberId, joinNote);
		when(tableGroups.getTableGroupDetail(ownerId, tableId)).thenReturn(table(
				tableId, ownerId, startAt, meetingAt, cityId, districtId, neighborhoodId,
				Set.of(participant(ownerId, null), participant(memberId, joinNote))));

		assertThatCode(() -> verifier.verifyTableGroup(item, request, expectedParticipants))
				.doesNotThrowAnyException();

		when(tableGroups.getTableGroupDetail(ownerId, tableId)).thenReturn(table(
				tableId, ownerId, startAt, meetingAt, cityId, districtId, neighborhoodId,
				Set.of(participant(ownerId, null), participant(memberId, "değiştirilmiş not"))));
		assertThatThrownBy(() -> verifier.verifyTableGroup(item, request, expectedParticipants))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("membership differs");
	}

	private static TableGroupResponseDto table(
			UUID tableId,
			UUID ownerId,
			Instant startAt,
			Instant meetingAt,
			UUID cityId,
			UUID districtId,
			UUID neighborhoodId,
			Set<TableGroupParticipantDto> participants
	) {
		return new TableGroupResponseDto(
				tableId, ownerId, "listener-owner", null, null, "Plak Arası", "Bir açıklama", 6,
				List.of("OTHER", "MALE", "FEMALE", "OTHER", "MALE", "FEMALE"),
				21, 45, startAt, meetingAt, startAt.plus(Duration.ofHours(24)),
				TableGroupStatus.INACTIVE, participants,
				new TableGroupResponseDto.LocationDto(cityId, "İstanbul"),
				new TableGroupResponseDto.LocationDto(districtId, "Kadıköy"),
				new TableGroupResponseDto.LocationDto(neighborhoodId, "Göztepe"));
	}

	private static TableGroupParticipantDto participant(UUID userId, String note) {
		return new TableGroupParticipantDto(
				userId, Instant.parse("2026-09-10T10:05:00Z"), ParticipantStatus.ACCEPTED,
				note, "listener", null);
	}

	private static TransactionAttribute transactionFor(
			String methodName,
			Class<?>... parameterTypes
	) throws NoSuchMethodException {
		var method = SimulationSocialContentVerifier.class.getMethod(methodName, parameterTypes);
		TransactionAttribute attribute = new AnnotationTransactionAttributeSource()
				.getTransactionAttribute(method, SimulationSocialContentVerifier.class);
		assertThat(attribute).as("transaction attribute for %s", methodName).isNotNull();
		assertThat(attribute.getTimeout()).as("transaction timeout for %s", methodName).isEqualTo(10);
		return attribute;
	}
}

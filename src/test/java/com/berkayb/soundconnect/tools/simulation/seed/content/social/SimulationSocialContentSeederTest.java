package com.berkayb.soundconnect.tools.simulation.seed.content.social;

import com.berkayb.soundconnect.modules.event.audience.EventAudienceService;
import com.berkayb.soundconnect.modules.event.audience.EventIntent;
import com.berkayb.soundconnect.modules.event.audience.EventIntentResponse;
import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.location.entity.District;
import com.berkayb.soundconnect.modules.location.entity.Neighborhood;
import com.berkayb.soundconnect.modules.overthinking.dto.response.OverthinkingPostResponseDto;
import com.berkayb.soundconnect.modules.overthinking.profileshare.OverthinkingProfileShareResponse;
import com.berkayb.soundconnect.modules.overthinking.profileshare.OverthinkingProfileShareService;
import com.berkayb.soundconnect.modules.overthinking.service.OverthinkingPostCommandService;
import com.berkayb.soundconnect.modules.tablegroup.dto.response.TableGroupParticipantDto;
import com.berkayb.soundconnect.modules.tablegroup.dto.response.TableGroupResponseDto;
import com.berkayb.soundconnect.modules.tablegroup.enums.ParticipantStatus;
import com.berkayb.soundconnect.modules.tablegroup.profileshare.TableGroupProfileShareResponse;
import com.berkayb.soundconnect.modules.tablegroup.profileshare.TableGroupProfileShareService;
import com.berkayb.soundconnect.modules.tablegroup.service.TableGroupService;
import com.berkayb.soundconnect.tools.simulation.SimulationMode;
import com.berkayb.soundconnect.tools.simulation.SimulationProperties;
import com.berkayb.soundconnect.tools.simulation.SimulationRuntimeGuard;
import com.berkayb.soundconnect.tools.simulation.report.SimulationRunLedger;
import com.berkayb.soundconnect.tools.simulation.seed.content.opportunity.SimulationOpportunityPlan;
import com.berkayb.soundconnect.tools.simulation.seed.content.opportunity.SimulationOpportunityPlanner;
import com.berkayb.soundconnect.tools.simulation.seed.content.opportunity.SimulationOpportunitySeedResult;
import com.berkayb.soundconnect.tools.simulation.seed.content.opportunity.SimulationOpportunityWindow;
import com.berkayb.soundconnect.tools.simulation.seed.support.SimulationPreflightResult;
import com.berkayb.soundconnect.tools.simulation.seed.support.SimulationResolvedLocation;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifestLoader;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SimulationSocialContentSeederTest {

	@Mock private SimulationRuntimeGuard guard;
	@Mock private OverthinkingPostCommandService overthinkingPosts;
	@Mock private OverthinkingProfileShareService overthinkingShares;
	@Mock private TableGroupService tableGroups;
	@Mock private TableGroupProfileShareService tableShares;
	@Mock private EventAudienceService eventAudience;
	@Mock private SimulationSocialContentVerifier verifier;
	@TempDir private Path reportDirectory;

	private SimulationWorldManifest manifest;
	private Map<String, UUID> userIds;
	private SimulationOpportunityPlan opportunityPlan;
	private SimulationOpportunitySeedResult opportunityResult;
	private SimulationPreflightResult preflight;
	private Clock clock;
	private SimulationProperties properties;
	private SimulationSocialContentCheckpointStore checkpoints;

	@BeforeEach
	void setUp() {
		manifest = new SimulationWorldManifestLoader(new ObjectMapper()).loadDefault();
		userIds = new LinkedHashMap<>();
		for (var account : manifest.accounts()) userIds.put(account.key(), stable("user|" + account.key()));
		clock = Clock.fixed(Instant.parse("2026-09-11T10:00:00Z"), ZoneOffset.UTC);
		properties = new SimulationProperties();
		properties.setMode(SimulationMode.FRESH);
		properties.setReportDirectory(reportDirectory);
		checkpoints = new SimulationSocialContentCheckpointStore(guard, properties, new ObjectMapper());
		SimulationOpportunityWindow window = new SimulationOpportunityWindow(
				clock.instant(), LocalDate.of(2026, 9, 11));
		opportunityPlan = new SimulationOpportunityPlanner().plan(manifest, window);
		Map<String, UUID> eventIds = new LinkedHashMap<>();
		for (var story : opportunityPlan.events()) {
			if (story.finalState() == SimulationOpportunityPlan.EventFinalState.VISIBLE) {
				eventIds.put(story.key(), stable("event|" + story.key()));
			}
		}
		opportunityResult = new SimulationOpportunitySeedResult(
				new SimulationOpportunitySeedResult.ModuleResult(36, 36, 0, 0, Map.of()),
				new SimulationOpportunitySeedResult.ModuleResult(30, 30, 0, 0, eventIds));

		City city = mock(City.class);
		District district = mock(District.class);
		Neighborhood neighborhood = mock(Neighborhood.class);
		when(city.getId()).thenReturn(stable("city"));
		when(district.getId()).thenReturn(stable("district"));
		when(district.getCity()).thenReturn(city);
		org.mockito.Mockito.lenient().when(neighborhood.getId()).thenReturn(stable("neighborhood"));
		when(neighborhood.getDistrict()).thenReturn(district);
		SimulationResolvedLocation location = new SimulationResolvedLocation(city, district, neighborhood, null);
		Map<String, SimulationResolvedLocation> locations = new LinkedHashMap<>();
		for (var account : manifest.accounts()) locations.put(account.key(), location);
		preflight = new SimulationPreflightResult(locations, Map.of());
	}

	@Test
	void materializesExactListenerPublicationWorldThroughDomainServices() {
		when(overthinkingPosts.create(any(), any())).thenAnswer(invocation -> {
			var request = (com.berkayb.soundconnect.modules.overthinking.dto.request.OverthinkingPostSaveRequestDto)
					invocation.getArgument(1);
			OverthinkingPostResponseDto response = mock(OverthinkingPostResponseDto.class);
			when(response.id()).thenReturn(stable("post|" + request.clientRequestId()));
			return response;
		});
		when(overthinkingShares.publish(any(), any(), any())).thenAnswer(invocation -> {
			UUID postId = invocation.getArgument(1);
			return new OverthinkingProfileShareResponse.State(
					postId, stable("ot-share|" + postId), true, null, clock.instant(), true);
		});

		List<UUID> tableIds = List.of(stable("table|1"), stable("table|2"), stable("table|3"));
		List<UUID> tableOwnerIds = manifest.accounts().stream()
				.filter(account -> account.role() == SimulationWorldManifest.AccountRole.LISTENER)
				.filter(account -> account.listenerVisibility() == SimulationWorldManifest.ListenerVisibilityState.STANDARD)
				.filter(account -> List.of(
						SimulationWorldManifest.Scene.ISTANBUL_ALTERNATIVE_ROCK,
						SimulationWorldManifest.Scene.ANKARA_POP_ELECTRONIC,
						SimulationWorldManifest.Scene.IZMIR_JAZZ_FUNK).contains(account.scene()))
				.collect(java.util.stream.Collectors.toMap(
						SimulationWorldManifest.Account::scene,
						account -> userIds.get(account.key()),
						(first, ignored) -> first,
						LinkedHashMap::new))
				.values().stream().toList();
		when(tableGroups.listMyActiveTableGroups(any(), any())).thenReturn(Page.empty());
		// Mockito's consecutive answers preserve the three scene-selected owner calls.
		List<TableGroupResponseDto> created = new ArrayList<>();
		for (int index = 0; index < tableIds.size(); index++) {
			TableGroupResponseDto response = mock(TableGroupResponseDto.class);
			when(response.id()).thenReturn(tableIds.get(index));
			when(response.ownerId()).thenReturn(tableOwnerIds.get(index));
			when(response.meetingAt()).thenReturn(clock.instant().plus(Duration.ofHours(4L + index * 2L)));
			created.add(response);
		}
		when(tableGroups.createTableGroup(any(), any()))
				.thenReturn(created.get(0), created.get(1), created.get(2));

		Map<UUID, Map<UUID, ParticipantStatus>> membership = new LinkedHashMap<>();
		for (UUID tableId : tableIds) membership.put(tableId, new LinkedHashMap<>());
		doAnswer(invocation -> {
			UUID participantId = invocation.getArgument(0);
			UUID tableId = invocation.getArgument(1);
			membership.get(tableId).put(participantId, ParticipantStatus.PENDING);
			return null;
		}).when(tableGroups).joinTableGroup(any(), any(), any());
		doAnswer(invocation -> {
			UUID tableId = invocation.getArgument(1);
			UUID participantId = invocation.getArgument(2);
			membership.get(tableId).put(participantId, ParticipantStatus.ACCEPTED);
			return null;
		}).when(tableGroups).approveJoinRequest(any(), any(), any());
		when(tableGroups.getTableGroupDetail(any(), any())).thenAnswer(invocation -> {
			UUID tableId = invocation.getArgument(1);
			Set<TableGroupParticipantDto> participants = new LinkedHashSet<>();
			membership.get(tableId).forEach((userId, status) -> participants.add(
					new TableGroupParticipantDto(userId, clock.instant(), status, null, "listener", null)));
			TableGroupResponseDto response = mock(TableGroupResponseDto.class);
			when(response.participants()).thenReturn(participants);
			return response;
		});
		when(tableShares.publish(any(), any(), any())).thenAnswer(invocation -> {
			UUID owner = invocation.getArgument(0);
			UUID table = invocation.getArgument(1);
			return new TableGroupProfileShareResponse.State(
					table, stable("table-share|" + owner + "|" + table), true,
					null, clock.instant(), true, null);
		});

		when(eventAudience.get(any(), any())).thenAnswer(invocation -> new EventIntentResponse.State(
				invocation.getArgument(1), EventIntent.NONE, false, null, 0,
				null, true, false, true, true, false, null, null));
		when(eventAudience.update(any(), any(), any())).thenAnswer(invocation -> {
			UUID eventId = invocation.getArgument(1);
			var command = (com.berkayb.soundconnect.modules.event.audience.EventIntentUpdate)
					invocation.getArgument(2);
			return new EventIntentResponse.State(
					eventId, command.intent(), true, command.note(), 1,
					clock.instant(), true, false, true, true, true, null,
					stable("event-post|" + invocation.getArgument(0) + "|" + eventId));
		});

		SimulationSocialContentSeeder seeder = new SimulationSocialContentSeeder(
				guard, overthinkingPosts, overthinkingShares, tableGroups, tableShares,
				eventAudience, Validation.buildDefaultValidatorFactory().getValidator(), clock,
				properties, checkpoints, verifier);
		SimulationSocialContentSeedResult result = seeder.seed(
				manifest, userIds, preflight, opportunityPlan, opportunityResult,
				new SimulationRunLedger(manifest.worldId(), manifest.seed(), clock));
		properties.setMode(SimulationMode.RESUME);
		SimulationSocialContentSeedResult resumed = seeder.seed(
				manifest, userIds, preflight, opportunityPlan, opportunityResult,
				new SimulationRunLedger(manifest.worldId(), manifest.seed(), clock));

		assertThat(result.overthinkingSourceIds()).hasSize(18);
		assertThat(result.tableGroupIds()).hasSize(3);
		assertThat(result.engagementTargets()).hasSize(42);
		assertThat(result.engagementTargets().stream()
				.collect(java.util.stream.Collectors.groupingBy(
						SimulationEngagementTarget::targetType, java.util.stream.Collectors.counting())))
				.containsEntry(com.berkayb.soundconnect.modules.engagement.enums.EngagementTargetType.OVERTHINKING_PROFILE_SHARE, 18L)
				.containsEntry(com.berkayb.soundconnect.modules.engagement.enums.EngagementTargetType.TABLE_GROUP_POST, 14L)
				.containsEntry(com.berkayb.soundconnect.modules.engagement.enums.EngagementTargetType.EVENT_POST, 10L);
		assertThat(resumed).isEqualTo(result);
		assertThat(checkpoints.path(manifest)).exists();
		verify(overthinkingPosts, times(18)).create(any(), any());
		verify(overthinkingShares, times(18)).publish(any(), any(), any());
		verify(tableGroups, times(3)).createTableGroup(any(), any());
		verify(tableGroups, times(11)).joinTableGroup(any(), any(), any());
		verify(tableGroups, times(11)).approveJoinRequest(any(), any(), any());
		verify(tableShares, times(14)).publish(any(), any(), any());
		verify(eventAudience, times(10)).update(any(), any(), any());
	}

	@Test
	void resumeFailsClosedBeforeCallingAnyProductionMutationWithoutCheckpoint() {
		properties.setMode(SimulationMode.RESUME);
		SimulationSocialContentSeeder seeder = new SimulationSocialContentSeeder(
				guard, overthinkingPosts, overthinkingShares, tableGroups, tableShares,
				eventAudience, Validation.buildDefaultValidatorFactory().getValidator(), clock,
				properties, checkpoints, verifier);

		assertThatThrownBy(() -> seeder.seed(
				manifest, userIds, preflight, opportunityPlan, opportunityResult,
				new SimulationRunLedger(manifest.worldId(), manifest.seed(), clock)))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("completed FRESH run");

		verifyNoInteractions(overthinkingPosts, overthinkingShares, tableGroups,
				tableShares, eventAudience, verifier);
	}

	private static UUID stable(String value) {
		return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
	}
}

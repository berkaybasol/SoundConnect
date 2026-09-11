package com.berkayb.soundconnect.tools.simulation.seed.content.opportunity;

import com.berkayb.soundconnect.modules.event.dto.request.EventCreateRequestDto;
import com.berkayb.soundconnect.modules.event.dto.response.EventResponseDto;
import com.berkayb.soundconnect.modules.event.enums.EventOrigin;
import com.berkayb.soundconnect.modules.event.enums.EventVenueApprovalStatus;
import com.berkayb.soundconnect.modules.event.enums.PerformerType;
import com.berkayb.soundconnect.modules.event.performer.dto.EventPerformerRequestResponseDto;
import com.berkayb.soundconnect.modules.event.performer.enums.EventPerformerRequestStatus;
import com.berkayb.soundconnect.modules.event.performer.service.EventPerformerRequestService;
import com.berkayb.soundconnect.modules.event.service.EventService;
import com.berkayb.soundconnect.shared.response.PageResponse;
import com.berkayb.soundconnect.tools.simulation.SimulationRuntimeGuard;
import com.berkayb.soundconnect.tools.simulation.report.SimulationRunLedger;
import com.berkayb.soundconnect.tools.simulation.seed.content.opportunity.SimulationOpportunityPlan.EventFinalState;
import com.berkayb.soundconnect.tools.simulation.seed.content.opportunity.SimulationOpportunityPlan.EventPerformerStory;
import com.berkayb.soundconnect.tools.simulation.seed.content.opportunity.SimulationOpportunityPlan.EventStory;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest.Account;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest.AccountRole;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest.EmailVerificationState;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest.InstitutionState;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifestLoader;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SimulationEventMaterializerTest {

	@Mock
	private SimulationRuntimeGuard runtimeGuard;
	@Mock
	private EventService eventService;
	@Mock
	private EventPerformerRequestService performerRequestService;
	@Mock
	private Validator validator;

	private SimulationWorldManifest manifest;
	private Account venue;
	private Account musician;
	private UUID venueOwnerId;
	private UUID musicianUserId;
	private UUID venueId;
	private UUID musicianProfileId;
	private SimulationRunLedger ledger;

	@BeforeEach
	void setUp() {
		manifest = new SimulationWorldManifestLoader(
				new ObjectMapper().findAndRegisterModules()).loadDefault();
		venue = manifest.accounts().stream()
				.filter(account -> account.role() == AccountRole.VENUE)
				.filter(account -> account.institutionState() == InstitutionState.APPROVED)
				.findFirst().orElseThrow();
		musician = manifest.accounts().stream()
				.filter(account -> account.role() == AccountRole.MUSICIAN)
				.filter(account -> account.emailVerification() == EmailVerificationState.VERIFIED)
				.findFirst().orElseThrow();
		venueOwnerId = UUID.randomUUID();
		musicianUserId = UUID.randomUUID();
		venueId = UUID.randomUUID();
		musicianProfileId = UUID.randomUUID();
		ledger = new SimulationRunLedger(
				manifest.worldId(), manifest.seed(),
				Clock.fixed(Instant.parse("2026-09-11T12:00:00Z"), ZoneOffset.UTC));
	}

	@Test
	void eventCreationAlwaysUsesTheApprovedVenueOwnerNeverAMusician() {
		EventStory story = story(EventPerformerStory.NONE, null, null, null);
		UUID eventId = UUID.randomUUID();
		when(validator.validate(any(EventCreateRequestDto.class))).thenReturn(Set.of());
		when(eventService.getOwnerEventsByVenue(venueOwnerId, venueId)).thenReturn(List.of());
		when(eventService.createEvent(any(), any())).thenReturn(response(eventId, story, null, null, null));

		SimulationOpportunitySeedResult.ModuleResult result = materializer().materialize(
				List.of(story), manifest,
				Map.of(venue.key(), venueOwnerId, musician.key(), musicianUserId),
				Map.of(), Map.of(venue.key(), venueId), Map.of(), false, ledger);

		assertThat(result.created()).isEqualTo(1);
		assertThat(result.ids()).containsEntry(story.key(), eventId);
		ArgumentCaptor<UUID> creator = ArgumentCaptor.forClass(UUID.class);
		verify(eventService).createEvent(creator.capture(), any(EventCreateRequestDto.class));
		assertThat(creator.getValue()).isEqualTo(venueOwnerId).isNotEqualTo(musicianUserId);
		verify(eventService, never()).createEvent(eq(musicianUserId), any(EventCreateRequestDto.class));
	}

	@Test
	void acceptedMusicianStoryUsesTheRealPerformerRequestService() {
		EventStory story = story(
				EventPerformerStory.MUSICIAN_ACCEPTED,
				musician.key(), musician.key(), null);
		UUID eventId = UUID.randomUUID();
		UUID requestId = UUID.randomUUID();
		EventPerformerRequestResponseDto pending =
				org.mockito.Mockito.mock(EventPerformerRequestResponseDto.class);
		EventPerformerRequestResponseDto accepted =
				org.mockito.Mockito.mock(EventPerformerRequestResponseDto.class);
		when(pending.id()).thenReturn(requestId);
		when(pending.eventId()).thenReturn(eventId);
		when(accepted.status()).thenReturn(EventPerformerRequestStatus.ACCEPTED);
		when(accepted.profileCalendarApproved()).thenReturn(true);
		when(validator.validate(any(EventCreateRequestDto.class))).thenReturn(Set.of());
		when(eventService.getOwnerEventsByVenue(venueOwnerId, venueId)).thenReturn(List.of());
		when(eventService.createEvent(any(), any())).thenReturn(
				response(eventId, story, PerformerType.MANUAL, null, null));
		when(performerRequestService.getMine(
				musicianUserId, EventPerformerRequestStatus.ACCEPTED,
				PerformerType.MUSICIAN, musicianProfileId, 0, 50))
				.thenReturn(page(List.of()));
		when(performerRequestService.getMine(
				musicianUserId, EventPerformerRequestStatus.PENDING,
				PerformerType.MUSICIAN, musicianProfileId, 0, 50))
				.thenReturn(page(List.of(pending)));
		when(performerRequestService.accept(musicianUserId, requestId, true))
				.thenReturn(accepted);

		SimulationOpportunitySeedResult.ModuleResult result = materializer().materialize(
				List.of(story), manifest,
				Map.of(venue.key(), venueOwnerId, musician.key(), musicianUserId),
				Map.of(musician.key(), musicianProfileId),
				Map.of(venue.key(), venueId), Map.of(), false, ledger);

		assertThat(result.created()).isEqualTo(1);
		verify(performerRequestService).accept(musicianUserId, requestId, true);
		ArgumentCaptor<EventCreateRequestDto> request = ArgumentCaptor.forClass(EventCreateRequestDto.class);
		verify(eventService).createEvent(eq(venueOwnerId), request.capture());
		assertThat(request.getValue().musicianProfileId()).isEqualTo(musicianProfileId);
		assertThat(request.getValue().bandId()).isNull();
	}

	@Test
	void exactResumeUsesOwnerReadSeamAndDoesNotDuplicateEvent() {
		EventStory story = story(EventPerformerStory.NONE, null, null, null);
		UUID eventId = UUID.randomUUID();
		EventResponseDto existing = response(eventId, story, null, null, null);
		when(validator.validate(any(EventCreateRequestDto.class))).thenReturn(Set.of());
		when(eventService.getOwnerEventsByVenue(venueOwnerId, venueId)).thenReturn(List.of(existing));

		SimulationOpportunitySeedResult.ModuleResult result = materializer().materialize(
				List.of(story), manifest,
				Map.of(venue.key(), venueOwnerId), Map.of(),
				Map.of(venue.key(), venueId), Map.of(), true, ledger);

		assertThat(result.created()).isZero();
		assertThat(result.skipped()).isEqualTo(1);
		assertThat(result.ids()).containsEntry(story.key(), eventId);
		verify(eventService, never()).createEvent(any(), any());
	}

	@Test
	void musicianManifestKeyCanNeverBeUsedAsEventCreator() {
		EventStory invalid = new EventStory(
				"event-invalid", musician.key(), "Yetkisiz Etkinlik",
				"Müzisyen hesabının etkinlik oluşturamadığını kanıtlayan negatif kontrol.",
				LocalDate.of(2026, 9, 20), LocalTime.of(20, 0), LocalTime.of(22, 0),
				EventPerformerStory.NONE, null, null, null, false, EventFinalState.VISIBLE);

		assertThatThrownBy(() -> materializer().materialize(
				List.of(invalid), manifest,
				Map.of(musician.key(), musicianUserId), Map.of(), Map.of(), Map.of(),
				false, ledger))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("not a verified approved venue");

		verify(eventService, never()).createEvent(any(), any());
	}

	private SimulationEventMaterializer materializer() {
		return new SimulationEventMaterializer(
				runtimeGuard, eventService, performerRequestService, validator);
	}

	private EventStory story(
			EventPerformerStory performerStory,
			String performerKey,
			String actorKey,
			String manualName
	) {
		return new EventStory(
				"event-01", venue.key(), "Geceye Açılan Sahne",
				"Onaylı mekan tarafından hazırlanan, sahne akışı önceden duyurulmuş canlı müzik gecesi.",
				LocalDate.of(2026, 9, 20), LocalTime.of(20, 0), LocalTime.of(22, 0),
				performerStory, performerKey, actorKey, manualName,
				performerStory == EventPerformerStory.MUSICIAN_ACCEPTED,
				EventFinalState.VISIBLE);
	}

	private EventResponseDto response(
			UUID eventId,
			EventStory story,
			PerformerType performerType,
			UUID responseMusicianProfileId,
			UUID responseBandId
	) {
		String performerName = switch (story.performerStory()) {
			case MUSICIAN_PENDING, MUSICIAN_ACCEPTED -> musician.username();
			case MANUAL -> story.manualPerformerName();
			case BAND_PENDING, BAND_ACCEPTED -> "Test Grubu";
			case NONE -> "Belirtilmemiş";
		};
		return new EventResponseDto(
				eventId, story.title(), null, performerName,
				responseMusicianProfileId, responseBandId, performerType, Set.of(),
				venueId, venue.displayName(), venue.location().city(), venue.location().district(),
				venue.location().neighborhood(), story.eventDate(), story.startTime(), story.endTime(),
				story.description(), "https://soundconnect.invalid/events/" + eventId,
				EventOrigin.VENUE, EventVenueApprovalStatus.APPROVED, true);
	}

	private PageResponse<EventPerformerRequestResponseDto> page(
			List<EventPerformerRequestResponseDto> content
	) {
		return new PageResponse<>(content, 0, 50, content.size(), content.isEmpty() ? 0 : 1, true, true);
	}
}

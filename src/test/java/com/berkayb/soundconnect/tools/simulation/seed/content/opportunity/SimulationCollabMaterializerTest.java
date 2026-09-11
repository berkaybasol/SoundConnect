package com.berkayb.soundconnect.tools.simulation.seed.content.opportunity;

import com.berkayb.soundconnect.modules.collab.dto.request.CollabDraftCreateRequest;
import com.berkayb.soundconnect.modules.collab.dto.request.ExpectedVersionRequest;
import com.berkayb.soundconnect.modules.collab.dto.response.CollabActorSummary;
import com.berkayb.soundconnect.modules.collab.dto.response.CollabCitySummary;
import com.berkayb.soundconnect.modules.collab.dto.response.CollabInstrumentSummary;
import com.berkayb.soundconnect.modules.collab.dto.response.CollabListingResponse;
import com.berkayb.soundconnect.modules.collab.entity.Collab;
import com.berkayb.soundconnect.modules.collab.entity.CollabActor;
import com.berkayb.soundconnect.modules.collab.enums.CollabCadence;
import com.berkayb.soundconnect.modules.collab.enums.CollabFeeStatus;
import com.berkayb.soundconnect.modules.collab.enums.CollabListingStatus;
import com.berkayb.soundconnect.modules.collab.enums.CollabWantedType;
import com.berkayb.soundconnect.modules.collab.repository.CollabRepository;
import com.berkayb.soundconnect.modules.collab.service.CollabService;
import com.berkayb.soundconnect.modules.instrument.entity.Instrument;
import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.location.entity.District;
import com.berkayb.soundconnect.modules.location.entity.Neighborhood;
import com.berkayb.soundconnect.modules.profile.shared.media.enums.ProfileType;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.tools.simulation.SimulationRuntimeGuard;
import com.berkayb.soundconnect.tools.simulation.report.SimulationRunLedger;
import com.berkayb.soundconnect.tools.simulation.seed.content.opportunity.SimulationOpportunityPlan.CollabFinalState;
import com.berkayb.soundconnect.tools.simulation.seed.content.opportunity.SimulationOpportunityPlan.CollabStory;
import com.berkayb.soundconnect.tools.simulation.seed.content.opportunity.SimulationOpportunityPlan.Publisher;
import com.berkayb.soundconnect.tools.simulation.seed.support.SimulationPreflightResult;
import com.berkayb.soundconnect.tools.simulation.seed.support.SimulationResolvedLocation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SimulationCollabMaterializerTest {

	private static final String ACCOUNT_KEY = "musician-test";
	private static final String STORY_KEY = "collab-01";

	@Mock
	private SimulationRuntimeGuard runtimeGuard;
	@Mock
	private CollabService collabService;
	@Mock
	private CollabRepository collabRepository;
	@Mock
	private Validator validator;

	private UUID userId;
	private UUID profileId;
	private UUID actorId;
	private UUID cityId;
	private UUID instrumentId;
	private CollabActorSummary actor;
	private CollabStory story;
	private SimulationPreflightResult preflight;
	private SimulationRunLedger ledger;

	@BeforeEach
	void setUp() {
		userId = UUID.randomUUID();
		profileId = UUID.randomUUID();
		actorId = UUID.randomUUID();
		cityId = UUID.randomUUID();
		instrumentId = UUID.randomUUID();
		actor = new CollabActorSummary(
				actorId, ProfileType.MUSICIAN, profileId, userId, "testmuzisyen",
				"Test Müzisyen", null, BigDecimal.ZERO, 0, 0);
		story = new CollabStory(
				STORY_KEY,
				UUID.nameUUIDFromBytes("world|collab|001".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
				new Publisher(ProfileType.MUSICIAN, ACCOUNT_KEY, ACCOUNT_KEY),
				ACCOUNT_KEY,
				CollabCadence.REGULAR,
				CollabWantedType.MUSICIAN,
				"Gitar",
				null,
				null,
				"Gitarist aranıyor: Gece Provası",
				"İstanbul alternatif sahnesinde düzenli prova için uyumlu bir gitarist arıyoruz.",
				List.of("Alternatif Rock", "Indie"),
				null,
				null,
				null,
				CollabFinalState.OPEN);

		City city = City.builder().id(cityId).name("İstanbul").build();
		District district = District.builder().id(UUID.randomUUID()).name("Kadıköy").city(city).build();
		Neighborhood neighborhood = Neighborhood.builder()
				.id(UUID.randomUUID()).name("Caferağa").district(district).build();
		Instrument instrument = Instrument.builder().name("Gitar").build();
		instrument.setId(instrumentId);
		preflight = new SimulationPreflightResult(
				Map.of(ACCOUNT_KEY, new SimulationResolvedLocation(city, district, neighborhood, null)),
				Map.of("Gitar", instrument));
		ledger = new SimulationRunLedger(
				"world", 42L, Clock.fixed(Instant.parse("2026-09-11T12:00:00Z"), ZoneOffset.UTC));
	}

	@Test
	void createsDraftAndPublishesThroughProductionLifecycle() {
		UUID listingId = UUID.randomUUID();
		when(validator.validate(any())).thenReturn(Set.of());
		when(collabService.actorsMine(userId)).thenReturn(List.of(actor));
		when(collabRepository.findByOwnerIdAndClientRequestId(userId, story.clientRequestId()))
				.thenReturn(Optional.empty());
		when(collabService.createDraft(any(), any())).thenReturn(
				response(listingId, 0, CollabListingStatus.DRAFT));
		when(collabService.publish(any(), any(), any())).thenReturn(
				response(listingId, 1, CollabListingStatus.OPEN));

		SimulationOpportunitySeedResult.ModuleResult result = materializer().materialize(
				List.of(story), Map.of(ACCOUNT_KEY, userId), Map.of(ACCOUNT_KEY, profileId),
				Map.of(), Map.of(), preflight, false, ledger);

		assertThat(result.planned()).isEqualTo(1);
		assertThat(result.created()).isEqualTo(1);
		assertThat(result.skipped()).isZero();
		assertThat(result.ids()).containsEntry(STORY_KEY, listingId);
		ArgumentCaptor<CollabDraftCreateRequest> draft =
				ArgumentCaptor.forClass(CollabDraftCreateRequest.class);
		verify(collabService).createDraft(org.mockito.ArgumentMatchers.eq(userId), draft.capture());
		assertThat(draft.getValue().publisherActorId()).isEqualTo(actorId);
		assertThat(draft.getValue().cityId()).isEqualTo(cityId);
		assertThat(draft.getValue().instrumentId()).isEqualTo(instrumentId);
		ArgumentCaptor<ExpectedVersionRequest> expected =
				ArgumentCaptor.forClass(ExpectedVersionRequest.class);
		verify(collabService).publish(
				org.mockito.ArgumentMatchers.eq(userId),
				org.mockito.ArgumentMatchers.eq(listingId), expected.capture());
		assertThat(expected.getValue().expectedVersion()).isZero();
		assertThat(ledger.snapshot().totals().get(SimulationRunLedger.Status.SUCCEEDED)).isEqualTo(1L);
	}

	@Test
	void exactResumeSkipsCreationAndLifecycleReplay() {
		UUID listingId = UUID.randomUUID();
		Collab identity = org.mockito.Mockito.mock(Collab.class);
		Collab detailed = org.mockito.Mockito.mock(Collab.class);
		User owner = User.builder().id(userId).build();
		CollabActor publisher = CollabActor.builder().id(actorId).build();
		City city = preflight.locationsByAccountKey().get(ACCOUNT_KEY).city();
		Instrument instrument = preflight.instrumentsByName().get("Gitar");
		when(identity.getId()).thenReturn(listingId);
		when(detailed.getId()).thenReturn(listingId);
		when(detailed.getOwner()).thenReturn(owner);
		when(detailed.getPublisherActor()).thenReturn(publisher);
		when(detailed.getClientRequestId()).thenReturn(story.clientRequestId());
		when(detailed.getCadence()).thenReturn(story.cadence());
		when(detailed.getWantedType()).thenReturn(story.wantedType());
		when(detailed.getInstrument()).thenReturn(instrument);
		when(detailed.getBranch()).thenReturn(story.branch());
		when(detailed.getCustomSpecialty()).thenReturn(story.customSpecialty());
		when(detailed.getTitle()).thenReturn(story.title());
		when(detailed.getDescription()).thenReturn(story.description());
		when(detailed.getCity()).thenReturn(city);
		when(detailed.getGenres()).thenReturn(story.genres());
		when(detailed.getScheduledAt()).thenReturn(story.scheduledAt());
		when(detailed.getFeeAmountMinor()).thenReturn(story.feeAmountMinor());
		when(detailed.getCurrency()).thenReturn(story.currency());
		when(validator.validate(any())).thenReturn(Set.of());
		when(collabService.actorsMine(userId)).thenReturn(List.of(actor));
		when(collabRepository.findByOwnerIdAndClientRequestId(userId, story.clientRequestId()))
				.thenReturn(Optional.of(identity));
		when(collabRepository.findDetailedById(listingId)).thenReturn(Optional.of(detailed));
		when(collabService.detail(userId, listingId)).thenReturn(
				response(listingId, 1, CollabListingStatus.OPEN));

		SimulationOpportunitySeedResult.ModuleResult result = materializer().materialize(
				List.of(story), Map.of(ACCOUNT_KEY, userId), Map.of(ACCOUNT_KEY, profileId),
				Map.of(), Map.of(), preflight, true, ledger);

		assertThat(result.created()).isZero();
		assertThat(result.skipped()).isEqualTo(1);
		verify(collabService, never()).createDraft(any(), any());
		verify(collabService, never()).publish(any(), any(), any());
		assertThat(ledger.snapshot().totals().get(SimulationRunLedger.Status.SKIPPED)).isEqualTo(1L);
	}

	private SimulationCollabMaterializer materializer() {
		return new SimulationCollabMaterializer(runtimeGuard, collabService, collabRepository, validator);
	}

	private CollabListingResponse response(UUID id, long version, CollabListingStatus status) {
		return new CollabListingResponse(
				id, version, status, null, story.cadence(), story.wantedType(),
				new CollabInstrumentSummary(instrumentId, "Gitar"), story.branch(), story.customSpecialty(),
				story.title(), story.description(), new CollabCitySummary(cityId, "İstanbul"),
				story.genres(), story.scheduledAt(), null, story.feeAmountMinor(), story.currency(),
				CollabFeeStatus.NOT_APPLICABLE, null, null, Instant.parse("2026-09-11T12:00:00Z"),
				actor, 0, true, false, false);
	}
}
